package com.heonezen.raytrace.config;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Loads config.yml into immutable settings and reloads it on demand. */
public final class RaytraceConfig {

    private static final int MIN_GRID_SIZE = 1;
    private static final int MAX_BLOCK_GRID_SIZE = 16;
    private static final int MAX_ENTITY_GRID_SIZE = 9;
    private static final int DEFAULT_BLOCK_GRID_SIZE = 10;
    private static final int DEFAULT_ENTITY_GRID_SIZE = 5;
    private static final int DEFAULT_SURFACE_BUFFER = 8;

    private static final WorldSettings DISABLED = new WorldSettings(false, List.of(), false, false, false, false, false, DEFAULT_SURFACE_BUFFER);

    private final Plugin plugin;

    private volatile Map<String, WorldSettings> worlds = Map.of();
    private volatile boolean hideEffects = true;
    private volatile double farSoundDistance = 24.0;
    private volatile GridSettings grids = new GridSettings(new GridSettings.BlockGrid(10, 2, 2L, 1.2, 6.0, 4.0, 80_000, 1_600, 20_000, 1_000L), new GridSettings.EntityGrid(5, 4L, 128.0, 1));

    public RaytraceConfig(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        this.worlds = parseWorlds(config);
        this.hideEffects = config.getBoolean("hide-effects", true);
        this.farSoundDistance = Math.max(4.0, config.getDouble("far-sound-distance", 24.0));
        this.grids = parseGrids(config);
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
        ConfigurationSection legacyPerformance = config.getConfigurationSection("performance");

        int blockSize = clamp(intValue(blocks, "size", config, "grid-size", DEFAULT_BLOCK_GRID_SIZE), MIN_GRID_SIZE, MAX_BLOCK_GRID_SIZE);
        int entitySize = clamp(intValue(entities, "size", null, null, DEFAULT_ENTITY_GRID_SIZE), MIN_GRID_SIZE, MAX_ENTITY_GRID_SIZE);

        // New configs use ticks. Old configs used milliseconds, so convert the legacy key
        // once instead of silently treating 50 as 50 ticks.
        long blockScanTicks = longValue(blocks, "scan-ticks", null, null, -1L);
        if (blockScanTicks < 1L) {
            if (blocks != null && blocks.contains("refresh-interval-millis")) {
                long legacyMillis = longValue(blocks, "refresh-interval-millis", null, null, 100L);
                blockScanTicks = Math.max(1L, (legacyMillis + 49L) / 50L);
            } else {
                blockScanTicks = 2L;
            }
        }
        blockScanTicks = clamp(blockScanTicks, 1L, 20L);

        return new GridSettings(
                new GridSettings.BlockGrid(
                        blockSize,
                        Math.max(0, intValue(blocks, "occlusion-grace-blocks", config, "occlusion-grace-blocks", 2)),
                        blockScanTicks,
                        Math.max(0.0, doubleValue(blocks, "move-threshold-blocks", config, "move-threshold-blocks", 1.2)),
                        Math.max(0.0, doubleValue(blocks, "yaw-threshold-degrees", config, "yaw-threshold-degrees", 6.0)),
                        Math.max(0.0, doubleValue(blocks, "pitch-threshold-degrees", config, "pitch-threshold-degrees", 4.0)),
                        clamp(intValue(blocks, "max-steps-per-scan", legacyPerformance, "max-steps-per-scan", 80_000), 1_000, 1_000_000),
                        clamp(intValue(blocks, "target-total-rays", legacyPerformance, "target-total-rays", 1_600), 64, 50_000),
                        clamp(intValue(blocks, "max-revealed-blocks-per-scan", legacyPerformance, "max-revealed-blocks-per-scan", 20_000), 256, 1_000_000),
                        clamp(longValue(blocks, "max-scan-micros", null, null, 1_000L), 100L, 20_000L)
                ),
                new GridSettings.EntityGrid(
                        entitySize,
                        clamp(longValue(entities, "scan-ticks", legacyPerformance, "entity-scan-interval-ticks", 4L), 1L, 40L),
                        clamp(doubleValue(entities, "scan-radius-blocks", legacyPerformance, "entity-scan-radius-blocks", 128.0), 8.0, 200.0),
                        // Entity rays get one grace block by default; terrain reveal has its own
                        // occlusion-grace-blocks setting.
                        clamp(intValue(entities, "occlusion-blocks", config, "entity-occlusion-blocks", 1), 1, 8)
                )
        );
    }

    private static int intValue(ConfigurationSection primary, String key, ConfigurationSection fallback, String fallbackKey, int defaultValue) {
        if (primary != null && primary.contains(key)) {
            return primary.getInt(key, defaultValue);
        }
        if (fallback != null && fallbackKey != null && fallback.contains(fallbackKey)) {
            return fallback.getInt(fallbackKey, defaultValue);
        }
        return defaultValue;
    }

    private static long longValue(ConfigurationSection primary, String key, ConfigurationSection fallback, String fallbackKey, long defaultValue) {
        if (primary != null && primary.contains(key)) {
            return primary.getLong(key, defaultValue);
        }
        if (fallback != null && fallbackKey != null && fallback.contains(fallbackKey)) {
            return fallback.getLong(fallbackKey, defaultValue);
        }
        return defaultValue;
    }

    private static double doubleValue(ConfigurationSection primary, String key, ConfigurationSection fallback, String fallbackKey, double defaultValue) {
        if (primary != null && primary.contains(key)) {
            return primary.getDouble(key, defaultValue);
        }
        if (fallback != null && fallbackKey != null && fallback.contains(fallbackKey)) {
            return fallback.getDouble(fallbackKey, defaultValue);
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
