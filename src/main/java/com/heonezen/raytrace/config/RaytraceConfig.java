package com.heonezen.raytrace.config;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

// Loads config.yml into immutable settings and reloads it on demand.
public final class RaytraceConfig {

    private static final int MIN_GRID_SIZE = 1;
    private static final int MAX_BLOCK_GRID_SIZE = 16;
    private static final int MAX_ENTITY_GRID_SIZE = 9;
    private static final int DEFAULT_BLOCK_GRID_SIZE = 8;
    private static final int DEFAULT_ENTITY_GRID_SIZE = 4;
    private static final int DEFAULT_SURFACE_BUFFER = 8;

    private static final List<String> DEFAULT_IMPORTANT_SOUNDS = List.of("explode", "explosion", "ender_dragon", "end_crystal", "wither", "end_portal", "beacon", "raid", "bell");

    private static final WorldSettings DISABLED = new WorldSettings(false, List.of(), false, false, false, false, false, DEFAULT_SURFACE_BUFFER);

    private final Plugin plugin;

    // Whether a configuration has been applied at least once.
    private volatile boolean loaded;

    private volatile Map<String, WorldSettings> worlds = Map.of();
    private volatile boolean hideEffects = true;
    private volatile double farSoundDistance = 48.0;
    private volatile String[] importantSounds = DEFAULT_IMPORTANT_SOUNDS.toArray(new String[0]);
    private volatile GridSettings grids = new GridSettings(new GridSettings.BlockGrid(8, 2, 2L, 1.2, 6.0, 4.0, 80_000, 2_600, 40_000, 1_000L), new GridSettings.EntityGrid(4, 2L, 128.0, 1));

    public RaytraceConfig(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Builds the settings from an already loaded configuration, without touching the plugin's
     * data folder. The diagnostics use this so they can run before the plugin has one.
     */
    public RaytraceConfig(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        apply(config);
    }

    public boolean reload() {
        plugin.saveDefaultConfig();
        FileConfiguration config;
        try {
            // The file is parsed on its own first. Bukkit swallows a YAML error and hands back an
            // empty config merged with the jar defaults, which would silently switch the
            // protection off in every world.
            new YamlConfiguration().load(new File(plugin.getDataFolder(), "config.yml"));
            plugin.reloadConfig();
            config = plugin.getConfig();
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().log(Level.SEVERE, "config.yml cannot be read: " + exception.getMessage(), exception);
            if (loaded) {
                plugin.getLogger().severe("The previous settings stay active.");
                return false;
            }
            // First load: run on the bundled defaults rather than leaving every world unprotected.
            config = bundledDefaults();
            if (config == null) {
                return false;
            }
            plugin.getLogger().severe("Raytrace runs on its built-in default settings until config.yml is fixed.");
        }

        return apply(config);
    }

    // Parses everything first, so a malformed reload never leaves the plugin with a half-updated
    // mix of old and new settings.
    private boolean apply(FileConfiguration config) {
        // contains(path, true) ignores the jar defaults, so only a section the file really has counts.
        if (!worlds.isEmpty() && !config.contains("worlds", true)) {
            plugin.getLogger().warning("config.yml has no 'worlds' section; the previous settings stay active.");
            return false;
        }

        Map<String, WorldSettings> parsedWorlds = parseWorlds(config);
        boolean parsedHideEffects = config.getBoolean("hide-effects", true);
        double parsedFarSoundDistance = clamp(config.getDouble("far-sound-distance", 48.0), 1.0, 128.0);
        String[] parsedImportantSounds = parseImportantSounds(config);
        GridSettings parsedGrids = parseGrids(config);

        this.worlds = parsedWorlds;
        this.hideEffects = parsedHideEffects;
        this.farSoundDistance = parsedFarSoundDistance;
        this.importantSounds = parsedImportantSounds;
        this.grids = parsedGrids;
        this.loaded = true;
        return true;
    }

    // The config.yml that ships inside the jar, or null if it cannot be read.
    private FileConfiguration bundledDefaults() {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String[] parseImportantSounds(FileConfiguration config) {
        if (!config.contains("important-sounds")) {
            return DEFAULT_IMPORTANT_SOUNDS.toArray(new String[0]);
        }
        List<String> result = new ArrayList<>();
        for (String entry : config.getStringList("important-sounds")) {
            if (entry == null) {
                continue;
            }
            String value = entry.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result.toArray(new String[0]);
    }

    private Map<String, WorldSettings> parseWorlds(FileConfiguration config) {
        Map<String, WorldSettings> result = new HashMap<>();
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection == null) {
            plugin.getLogger().warning("config.yml has no 'worlds' section; Raytrace is disabled everywhere.");
            return result;
        }

        for (String worldName : worldsSection.getKeys(false)) {
            ConfigurationSection section = worldsSection.getConfigurationSection(worldName);
            if (section == null) {
                continue;
            }
            boolean enabled = section.getBoolean("enabled", false);
            List<LayerBand> bands = parseLayers(section.getMapList("layers"), worldName);
            boolean hideMobs = section.getBoolean("hide-mobs", true);
            boolean hidePlayers = section.getBoolean("hide-players", true);
            boolean hideVehicles = section.getBoolean("hide-vehicles", true);
            boolean hideDecorations = section.getBoolean("hide-decorations", true);
            boolean hideItems = section.getBoolean("hide-items", true);
            int surfaceBuffer = section.getInt("surface-buffer", DEFAULT_SURFACE_BUFFER);
            if (enabled && bands.isEmpty() && !hideMobs && !hidePlayers && !hideVehicles && !hideDecorations && !hideItems) {
                plugin.getLogger().warning("World '" + worldName + "' is enabled but protects nothing.");
            }
            result.put(worldName, new WorldSettings(enabled, bands, hideMobs, hidePlayers, hideVehicles, hideDecorations, hideItems, surfaceBuffer));
        }
        return result;
    }

    private List<LayerBand> parseLayers(List<Map<?, ?>> rawLayers, String worldName) {
        List<LayerBand> bands = new ArrayList<>();
        for (Map<?, ?> rawLayer : rawLayers) {
            Object belowY = rawLayer.get("below-y");
            Object block = rawLayer.get("block");
            if (!(belowY instanceof Number number) || block == null) {
                plugin.getLogger().warning("Skipping malformed layer for world '" + worldName + "': " + rawLayer);
                continue;
            }
            Material material = Material.matchMaterial(String.valueOf(block));
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("Unknown block '" + block + "' in world '" + worldName + "'; skipped.");
                continue;
            }
            bands.add(new LayerBand(number.intValue(), material));
        }
        return bands;
    }

    private GridSettings parseGrids(FileConfiguration config) {
        ConfigurationSection gridsSection = config.getConfigurationSection("grids");
        ConfigurationSection blocks = gridsSection == null ? null : gridsSection.getConfigurationSection("blocks");
        ConfigurationSection entities = gridsSection == null ? null : gridsSection.getConfigurationSection("entities");

        int blockSize = clamp(intValue(blocks, "size", DEFAULT_BLOCK_GRID_SIZE), MIN_GRID_SIZE, MAX_BLOCK_GRID_SIZE);
        int entitySize = clamp(intValue(entities, "size", DEFAULT_ENTITY_GRID_SIZE), MIN_GRID_SIZE, MAX_ENTITY_GRID_SIZE);
        long blockScanTicks = clamp(longValue(blocks, "scan-ticks", 2L), 1L, 20L);
        return new GridSettings(
                new GridSettings.BlockGrid(
                        blockSize,
                        clamp(intValue(blocks, "occlusion-grace-blocks", 2), 1, 8),
                        blockScanTicks,
                        clamp(doubleValue(blocks, "move-threshold-blocks", 1.2), 0.1, 16.0),
                        clamp(doubleValue(blocks, "yaw-threshold-degrees", 6.0), 0.1, 120.0),
                        clamp(doubleValue(blocks, "pitch-threshold-degrees", 4.0), 0.1, 120.0),
                        clamp(intValue(blocks, "max-steps-per-scan", 80000), 1000, 1000000),
                        clamp(intValue(blocks, "target-total-rays", 2600), 64, 10000),
                        clamp(intValue(blocks, "max-revealed-blocks-per-scan", 40000), 256, 1000000),
                        clamp(longValue(blocks, "max-scan-micros", 1000L), 100L, 20000L)
                ),
                new GridSettings.EntityGrid(
                        entitySize,
                        clamp(longValue(entities, "scan-ticks", 2L), 1L, 20L),
                        clamp(doubleValue(entities, "scan-radius-blocks", 128.0), 8.0, 200.0),
                        // Entity rays get one grace block by default; terrain reveal has its own
                        // occlusion-grace-blocks setting.
                        clamp(intValue(entities, "occlusion-blocks", 1), 1, 8)
                )
        );
    }

    private static int intValue(ConfigurationSection section, String key, int defaultValue) {
        if (section != null && section.contains(key)) {
            return section.getInt(key, defaultValue);
        }
        return defaultValue;
    }

    private static long longValue(ConfigurationSection section, String key, long defaultValue) {
        if (section != null && section.contains(key)) {
            return section.getLong(key, defaultValue);
        }
        return defaultValue;
    }

    private static double doubleValue(ConfigurationSection section, String key, double defaultValue) {
        if (section != null && section.contains(key)) {
            return section.getDouble(key, defaultValue);
        }
        return defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public WorldSettings worldSettings(World world) {
        return worlds.getOrDefault(world.getName(), DISABLED);
    }

    public boolean hideEffects() {
        return hideEffects;
    }

    public double farSoundDistance() {
        return farSoundDistance;
    }

    public String[] importantSounds() {
        return importantSounds;
    }

    public GridSettings.BlockGrid blockGrid() {
        return grids.blocks();
    }

    public GridSettings.EntityGrid entityGrid() {
        return grids.entities();
    }

    public void logSummary() {
        int enabledWorlds = (int) worlds.values().stream().filter(WorldSettings::enabled).count();
        plugin.getLogger().log(Level.INFO, "Raytrace protection active in {0} world(s).", enabledWorlds);
        GridSettings settings = grids;
        plugin.getLogger().log(Level.INFO, "  grids: blocks {0} (every {1} tick(s)), entities {2}x{2} " + "(every {3} tick(s), occlusion {4})", new Object[]{settings.blocks().size(), settings.blocks().scanTicks(), settings.entities().size(), settings.entities().scanIntervalTicks(), settings.entities().occlusionBlocks()});
        for (Map.Entry<String, WorldSettings> entry : worlds.entrySet()) {
            WorldSettings value = entry.getValue();
            if (value.enabled()) {
                plugin.getLogger().log(Level.INFO, "  {0}: {1}", new Object[]{entry.getKey(), value.describeLayers()});
            }
        }
    }
}
