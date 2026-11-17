package com.heonezen.raytrace.config;

import com.heonezen.raytrace.TestPlugins;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaytraceConfigTest {

    // The bundled config.yml, with block layers removed (block names need the server's registry).
    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(RaytraceConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        for (String world : file.getConfigurationSection("worlds").getKeys(false)) {
            file.set("worlds." + world + ".layers", null);
        }
        return file;
    }

    private static RaytraceConfig parse(TestPlugins plugins, String yaml) throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString(yaml);
        file.setDefaults(bundled());
        return new RaytraceConfig(plugins.plugin, file);
    }

    @Test
    void codeDefaultsMatchTheBundledFile() throws Exception {
        TestPlugins plugins = new TestPlugins();
        RaytraceConfig fromFile = new RaytraceConfig(plugins.plugin, bundled());
        RaytraceConfig fromDefaults = parse(plugins, "worlds:\n  world: {}\n");
        assertEquals(fromFile.blockGrid(), fromDefaults.blockGrid(), "grids.blocks defaults drifted from config.yml");
        assertEquals(fromFile.entityGrid(), fromDefaults.entityGrid(), "grids.entities defaults drifted from config.yml");
        assertEquals(fromFile.hideEffects(), fromDefaults.hideEffects());
        assertEquals(fromFile.hideLocatorPositions(), fromDefaults.hideLocatorPositions());
        assertEquals(fromFile.farSoundDistance(), fromDefaults.farSoundDistance());
        assertEquals(fromFile.importantSounds().length, fromDefaults.importantSounds().length);
        assertTrue(plugins.messages.isEmpty(), "the bundled file must parse without warnings: " + plugins.messages);
    }

    @Test
    void worldDefaultsMatchTheBundledFile() throws Exception {
        ConfigurationSection world = bundled().getConfigurationSection("worlds.world");
        TestPlugins plugins = new TestPlugins();
        RaytraceConfig config = parse(plugins, "worlds:\n  world: {}\n");
        WorldSettings settings = config.worldSettings(TestWorlds.named("world"));
        assertEquals(world.getBoolean("enabled"), settings.enabled());
        assertEquals(world.getInt("surface-buffer"), settings.surfaceBuffer());
        assertEquals(world.getBoolean("hide-mobs"), settings.hideMobs());
        assertEquals(world.getBoolean("hide-players"), settings.hidePlayers());
        assertEquals(world.getBoolean("hide-items"), settings.hideItems());
        assertEquals(world.getBoolean("hide-projectiles"), settings.hideProjectiles());
        assertEquals(world.getBoolean("hide-misc"), settings.hideMisc());
    }

    @Test
    void badValuesAreReportedAndReplaced() throws Exception {
        TestPlugins plugins = new TestPlugins();
        String yaml = "worlds:\n  world:\n    hide-mobs: maybe\n    surface-buffer: 500\ngrids:\n  blocks:\n    size: abc\n    target-total-rays: 99999\n    max-scan-micros: '1500'\n  entities:\n    size: 2.5\n    max-scan-micros-per-tick: 0\nfar-sound-distance: 500\n";
        RaytraceConfig config = parse(plugins, yaml);
        assertEquals(8, config.blockGrid().size());
        assertEquals(10_000, config.blockGrid().targetTotalRays());
        assertEquals(1_500, config.blockGrid().maxScanMicros(), "a number in quotes is still a number");
        assertEquals(3, config.entityGrid().size());
        assertEquals(1_000, config.entityGrid().maxScanMicrosPerTick());
        assertEquals(128.0, config.farSoundDistance());
        WorldSettings world = config.worldSettings(TestWorlds.named("world"));
        assertTrue(world.hideMobs());
        assertEquals(64, world.surfaceBuffer());
        assertEquals(7, plugins.messages.size(), "one console line per bad value: " + plugins.messages);
    }

    @Test
    void aListedWorldWithoutEnabledIsProtected() throws Exception {
        RaytraceConfig config = parse(new TestPlugins(), "worlds:\n  survival:\n    hide-mobs: true\n");
        assertTrue(config.worldSettings(TestWorlds.named("survival")).enabled());
        assertFalse(config.worldSettings(TestWorlds.named("unlisted")).enabled(), "unlisted worlds stay off");
    }

    @Test
    void missingWorldsSectionIsReported() throws Exception {
        TestPlugins plugins = new TestPlugins();
        parse(plugins, "grids:\n  blocks:\n    size: 8\n");
        assertTrue(plugins.messages.stream().anyMatch(message -> message.contains("protects no world")), plugins.messages.toString());
    }

    @Test
    void outOfRangeEdgesAreAccepted() throws Exception {
        TestPlugins plugins = new TestPlugins();
        RaytraceConfig config = parse(plugins, "worlds:\n  world: {}\ngrids:\n  blocks:\n    size: 16\n    max-scan-micros-per-tick: 40000\n");
        assertEquals(16, config.blockGrid().size());
        assertEquals(40_000, config.blockGrid().maxScanMicrosPerTick());
        assertTrue(plugins.messages.isEmpty(), plugins.messages.toString());
    }
}
