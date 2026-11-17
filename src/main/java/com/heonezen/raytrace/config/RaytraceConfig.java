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
    // Defaults must match the bundled config.yml: a missing key falls back to these, not to the jar's file.
    private static final int DEFAULT_SURFACE_BUFFER = 4;

    private static final List<String> DEFAULT_IMPORTANT_SOUNDS = List.of("explode", "explosion", "ender_dragon", "end_crystal", "wither", "end_portal", "beacon", "raid", "bell");

    private static final WorldSettings DISABLED = new WorldSettings(false, List.of(), false, false, false, false, false, DEFAULT_SURFACE_BUFFER);

    private final Plugin plugin;

    // Whether a configuration has been applied at least once.
    private volatile boolean loaded;

    private volatile Map<String, WorldSettings> worlds = Map.of();
    private volatile boolean hideEffects = true;
    private volatile double farSoundDistance = 48.0;
    private volatile String[] importantSounds = DEFAULT_IMPORTANT_SOUNDS.toArray(new String[0]);
    // Scan limits; config.yml explains each of them.
    private static final int DEFAULT_MAX_STEPS = 80_000;
    private static final int DEFAULT_TARGET_RAYS = 2_600;
    private static final int DEFAULT_MAX_REVEALED = 32_768;
    private static final long DEFAULT_SCAN_MICROS = 1_000L;
    private static final long DEFAULT_SCAN_MICROS_PER_TICK = 6_000L;
    private static final long DEFAULT_ENTITY_SCAN_MICROS = 500L;
    private static final long DEFAULT_ENTITY_SCAN_MICROS_PER_TICK = 4_000L;

    private volatile boolean hideLocatorPositions = true;
    private volatile GridSettings grids = new GridSettings(new GridSettings.BlockGrid(8, 1, 2L, 1.2, 6.0, 4.0, DEFAULT_MAX_STEPS, DEFAULT_TARGET_RAYS, DEFAULT_MAX_REVEALED, DEFAULT_SCAN_MICROS, DEFAULT_SCAN_MICROS_PER_TICK), new GridSettings.EntityGrid(4, 2L, 128.0, 1, DEFAULT_ENTITY_SCAN_MICROS, DEFAULT_ENTITY_SCAN_MICROS_PER_TICK));

    public RaytraceConfig(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // Builds the settings from a loaded configuration, without the plugin's data folder (self-checks).
    public RaytraceConfig(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        apply(config);
    }

    public boolean reload() {
        plugin.saveDefaultConfig();
        FileConfiguration config;
        try {
            // Parse the file on its own first: Bukkit hides a YAML error behind an empty config.
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

    // Parses everything first, so a malformed reload never leaves the plugin with a half-updated mix of old and new settings.
    private boolean apply(FileConfiguration config) {
        // contains(path, true) ignores the jar defaults, so only a section the file really has counts.
        if (!worlds.isEmpty() && !config.contains("worlds", true)) {
            plugin.getLogger().warning("config.yml has no 'worlds' section; the previous settings stay active.");
            return false;
        }

        Map<String, WorldSettings> parsedWorlds = parseWorlds(config);
        boolean parsedHideEffects = booleanSetting(config, "", "hide-effects", true);
        boolean parsedHideLocator = booleanSetting(config, "", "hide-locator-positions", true);
        double parsedFarSoundDistance = doubleSetting(config, "", "far-sound-distance", 48.0, 1.0, 128.0);
        String[] parsedImportantSounds = parseImportantSounds(config);
        GridSettings parsedGrids = parseGrids(config);

        this.worlds = parsedWorlds;
        this.hideEffects = parsedHideEffects;
        this.hideLocatorPositions = parsedHideLocator;
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
        // Checked on the file itself: Bukkit returns an empty default section when the file has none.
        ConfigurationSection worldsSection = config.contains("worlds", true) ? config.getConfigurationSection("worlds") : null;
        if (worldsSection == null || worldsSection.getKeys(false).isEmpty()) {
            plugin.getLogger().warning("config.yml has no worlds listed under 'worlds'; Raytrace protects no world.");
            return result;
        }

        for (String worldName : worldsSection.getKeys(false)) {
            ConfigurationSection section = worldsSection.getConfigurationSection(worldName);
            if (section == null) {
                plugin.getLogger().warning("worlds." + worldName + " is not a section of options; that world is not protected.");
                continue;
            }
            String path = "worlds." + worldName + ".";
            // A world that is listed is meant to be protected: a missing 'enabled' means on.
            boolean enabled = booleanSetting(section, path, "enabled", true);
            List<LayerBand> bands = parseLayers(section.getMapList("layers"), worldName);
            boolean hideMobs = booleanSetting(section, path, "hide-mobs", true);
            boolean hidePlayers = booleanSetting(section, path, "hide-players", true);
            boolean hideVehicles = booleanSetting(section, path, "hide-vehicles", true);
            boolean hideDecorations = booleanSetting(section, path, "hide-decorations", true);
            boolean hideItems = booleanSetting(section, path, "hide-items", true);
            boolean hideProjectiles = booleanSetting(section, path, "hide-projectiles", true);
            boolean hideMisc = booleanSetting(section, path, "hide-misc", true);
            int surfaceBuffer = intSetting(section, path, "surface-buffer", DEFAULT_SURFACE_BUFFER, -1, 64);
            if (enabled && bands.isEmpty() && !hideMobs && !hidePlayers && !hideVehicles && !hideDecorations && !hideItems && !hideProjectiles && !hideMisc) {
                plugin.getLogger().warning("World '" + worldName + "' is enabled but protects nothing.");
            }
            result.put(worldName, new WorldSettings(enabled, bands, hideMobs, hidePlayers, hideVehicles, hideDecorations, hideItems, hideProjectiles, hideMisc, surfaceBuffer));
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

        String b = "grids.blocks.";
        String e = "grids.entities.";
        int blockSize = intSetting(blocks, b, "size", DEFAULT_BLOCK_GRID_SIZE, MIN_GRID_SIZE, MAX_BLOCK_GRID_SIZE);
        // Walking further than entities.occlusion-blocks reveals nothing more.
        int grace = intSetting(blocks, b, "occlusion-grace-blocks", 1, 1, 8);
        int blockScanTicks = intSetting(blocks, b, "scan-ticks", 2, 1, 20);
        double moveThreshold = doubleSetting(blocks, b, "move-threshold-blocks", 1.2, 0.1, 16.0);
        double yawThreshold = doubleSetting(blocks, b, "yaw-threshold-degrees", 6.0, 0.1, 120.0);
        double pitchThreshold = doubleSetting(blocks, b, "pitch-threshold-degrees", 4.0, 0.1, 120.0);
        int maxSteps = intSetting(blocks, b, "max-steps-per-scan", DEFAULT_MAX_STEPS, 10_000, 1_000_000);
        int targetRays = intSetting(blocks, b, "target-total-rays", DEFAULT_TARGET_RAYS, 500, 10_000);
        int maxRevealed = intSetting(blocks, b, "max-revealed-blocks-per-scan", DEFAULT_MAX_REVEALED, 4_096, 262_144);
        int scanMicros = intSetting(blocks, b, "max-scan-micros", (int) DEFAULT_SCAN_MICROS, 200, 10_000);
        int scanMicrosPerTick = intSetting(blocks, b, "max-scan-micros-per-tick", (int) DEFAULT_SCAN_MICROS_PER_TICK, 1_000, 40_000);

        int entitySize = intSetting(entities, e, "size", DEFAULT_ENTITY_GRID_SIZE, MIN_GRID_SIZE, MAX_ENTITY_GRID_SIZE);
        int entityScanTicks = intSetting(entities, e, "scan-ticks", 2, 1, 20);
        double scanRadius = doubleSetting(entities, e, "scan-radius-blocks", 128.0, 8.0, 200.0);
        int occlusion = intSetting(entities, e, "occlusion-blocks", 1, 1, 8);
        int entityMicros = intSetting(entities, e, "max-scan-micros", (int) DEFAULT_ENTITY_SCAN_MICROS, 100, 10_000);
        int entityMicrosPerTick = intSetting(entities, e, "max-scan-micros-per-tick", (int) DEFAULT_ENTITY_SCAN_MICROS_PER_TICK, 1_000, 40_000);

        GridSettings.BlockGrid blockGrid = new GridSettings.BlockGrid(blockSize, grace, blockScanTicks, moveThreshold, yawThreshold, pitchThreshold, maxSteps, targetRays, maxRevealed, scanMicros, scanMicrosPerTick);
        GridSettings.EntityGrid entityGrid = new GridSettings.EntityGrid(entitySize, entityScanTicks, scanRadius, occlusion, entityMicros, entityMicrosPerTick);
        return new GridSettings(blockGrid, entityGrid);
    }

    // The file's own value of a key (never the jar's default), or null when it is missing.
    private static Object rawValue(ConfigurationSection section, String key) {
        return section == null ? null : section.get(key, null);
    }

    // A whole-number setting. A wrong or out-of-range value is logged, never replaced silently.
    private int intSetting(ConfigurationSection section, String path, String key, int defaultValue, int min, int max) {
        Object raw = rawValue(section, key);
        if (raw == null) {
            return defaultValue;
        }
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning(path + key + ": '" + raw + "' is not a number; using " + defaultValue + ".");
                return defaultValue;
            }
        }
        if (value != Math.rint(value)) {
            plugin.getLogger().warning(path + key + ": " + raw + " must be a whole number; using " + Math.round(value) + ".");
        }
        long rounded = Math.round(value);
        if (rounded < min || rounded > max) {
            long clamped = Math.max(min, Math.min(max, rounded));
            plugin.getLogger().warning(path + key + ": " + raw + " is outside " + min + " to " + max + "; using " + clamped + ".");
            return (int) clamped;
        }
        return (int) rounded;
    }

    // A decimal setting, reported like intSetting.
    private double doubleSetting(ConfigurationSection section, String path, String key, double defaultValue, double min, double max) {
        Object raw = rawValue(section, key);
        if (raw == null) {
            return defaultValue;
        }
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning(path + key + ": '" + raw + "' is not a number; using " + defaultValue + ".");
                return defaultValue;
            }
        }
        if (Double.isNaN(value) || value < min || value > max) {
            double clamped = Double.isNaN(value) ? defaultValue : Math.max(min, Math.min(max, value));
            plugin.getLogger().warning(path + key + ": " + raw + " is outside " + min + " to " + max + "; using " + clamped + ".");
            return clamped;
        }
        return value;
    }

    // A true/false setting, reported like intSetting.
    private boolean booleanSetting(ConfigurationSection section, String path, String key, boolean defaultValue) {
        Object raw = rawValue(section, key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = raw.toString().trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(text);
        }
        plugin.getLogger().warning(path + key + ": '" + raw + "' is not true or false; using " + defaultValue + ".");
        return defaultValue;
    }

    public WorldSettings worldSettings(World world) {
        return worlds.getOrDefault(world.getName(), DISABLED);
    }

    public boolean hideEffects() {
        return hideEffects;
    }

    // Whether the locator bar may only show a direction for entities the viewer cannot see.
    public boolean hideLocatorPositions() {
        return hideLocatorPositions;
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
