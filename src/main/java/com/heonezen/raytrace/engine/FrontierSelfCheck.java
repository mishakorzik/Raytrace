package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.config.GridSettings;
import com.heonezen.raytrace.config.LayerBand;
import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.config.WorldSettings;
import com.heonezen.raytrace.util.CellPos;
import com.heonezen.raytrace.util.LongIntMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Checks the scan where fixed ray patterns fail: the far walls of a deep shaft seen from its rim must turn real, and nothing behind them.
public final class FrontierSelfCheck {

    // Solid terrain up to this Y outside the shaft; the shaft is open from Y 1.
    private static final int SURFACE = 136;
    // Only walls below this Y count: the bottom of the shaft.
    private static final int BAND_TOP = 40;
    private static final int SHAFT_SIZE = 16;
    private static final int RADIUS_CHUNKS = 11;
    private static final String WORLD_NAME = "raytrace-frontier-selfcheck";

    private FrontierSelfCheck() {}

    public record Outcome(String shaft, int visible, int patternOnly, int refined, int leaks, int frontierRays) {
        public boolean passed() {
            return leaks == 0 && visible > 0 && refined >= Math.ceil(visible * 0.95) && refined >= patternOnly;
        }
    }

    // Runs both shafts with these grid settings (null: the defaults).
    public static List<Outcome> run(Plugin plugin, ConfigurationSection grids) {
        return List.of(run(plugin, grids, "a whole chunk", 0, 0), run(plugin, grids, "an offset shaft", 3, 5));
    }

    private static Outcome run(Plugin plugin, ConfigurationSection grids, String name, int shaftX, int shaftZ) {
        YamlConfiguration configuration = new YamlConfiguration();
        // Only the grids come from the configuration; the layer is built directly, so no block registry is needed.
        configuration.set("worlds." + WORLD_NAME + ".enabled", true);
        if (grids != null) {
            configuration.set("grids", grids);
        }
        RaytraceConfig config = new RaytraceConfig(plugin, configuration);
        RaytraceEngine engine = new RaytraceEngine(plugin, config);
        World world = world();
        WorldSettings settings = new WorldSettings(true, List.of(new LayerBand(256, Material.STONE)), true, true, 8);
        GridSettings.BlockGrid grid = config.blockGrid();
        int gridSize = grid.size();
        int depthLimit = config.entityGrid().occlusionBlocks();
        int maxDistance = RADIUS_CHUNKS * 16;
        Shaft shaft = new Shaft(shaftX, shaftZ);

        // Ceilings: nothing hidden in a chunk the shaft fills, terrain up to the surface elsewhere.
        long forever = System.nanoTime() + 3_600_000_000_000L;
        for (int chunkX = -RADIUS_CHUNKS - 2; chunkX <= RADIUS_CHUNKS + 2; chunkX++) {
            for (int chunkZ = -RADIUS_CHUNKS - 2; chunkZ <= RADIUS_CHUNKS + 2; chunkZ++) {
                int maxHard = shaft.fillsChunk(chunkX, chunkZ) ? 0 : SURFACE - 1;
                engine.storePointEntry(world.getUID(), chunkX, chunkZ, new RaytraceEngine.CeilingEntry(new int[256], new int[256], maxHard, forever, forever));
            }
        }

        // A sneaking player leaning over the west edge, looking straight down.
        double ex = shaftX + 0.25;
        double ey = SURFACE + 1.62;
        double ez = shaftZ + 8.0;
        float yaw = -90.0F;
        float pitch = 90.0F;

        Set<Long> visible = shaft.referenceCells(gridSize, ex, ey, ez, maxDistance);
        RaytraceEngine.RayPlan plan = engine.rayPlan(gridSize, maxDistance);

        // The normal pass.
        Set<Long> patternCells = new HashSet<>();
        Set<Long> allCells = new HashSet<>();
        RaytraceEngine.RayContext ctx = context(engine, world, settings, grid, shaft);
        LongIntMap touched = new LongIntMap(2048);
        engine.castPattern(ctx, plan, ex, ey, ez, yaw, pitch, RaytraceEngine.REFINE_OFFSETS[0], 0, plan.count(), maxDistance, touched);
        collect(touched, depthLimit, gridSize, patternCells);
        allCells.addAll(patternCells);

        // The idle passes, the way refreshVisibleCells runs them for a player who stands still.
        PlayerState state = new PlayerState();
        double frontierDistance = RaytraceEngine.frontierDistance(gridSize, plan);
        for (int pass = 1; pass < RaytraceEngine.REFINE_OFFSETS.length; pass++) {
            ctx = context(engine, world, settings, grid, shaft);
            touched = new LongIntMap(2048);
            ctx.setView(ex, ey, ez, yaw, pitch);
            ctx.seedState = state;
            ctx.seedDistance = frontierDistance;
            engine.castPattern(ctx, plan, ex, ey, ez, yaw, pitch, RaytraceEngine.REFINE_OFFSETS[pass], 0, plan.count(), maxDistance, touched);
            ctx.seedState = null;
            while (engine.growFrontier(state, ctx, touched, ex, ey, ez, maxDistance, frontierDistance)) {
                ctx.steps = Integer.MAX_VALUE;
            }
            collect(touched, depthLimit, gridSize, allCells);
        }

        int patternOnly = 0;
        int refined = 0;
        for (long key : visible) {
            if (patternCells.contains(key)) {
                patternOnly++;
            }
            if (allCells.contains(key)) {
                refined++;
            }
        }
        int leaks = 0;
        for (long key : allCells) {
            if (!shaft.touchesCell(CellPos.unpackX(key), CellPos.unpackZ(key), gridSize)) {
                leaks++;
            }
        }
        return new Outcome(name, visible.size(), patternOnly, refined, leaks, state.frontierRaysUsed());
    }

    private static RaytraceEngine.RayContext context(RaytraceEngine engine, World world, WorldSettings settings, GridSettings.BlockGrid grid, Shaft shaft) {
        RaytraceEngine.RayContext ctx = engine.new RayContext(world, settings, grid, Integer.MAX_VALUE, Long.MAX_VALUE);
        ctx.testTerrain = shaft::solid;
        return ctx;
    }

    // Bottom cells (below BAND_TOP) reached within the depth limit.
    private static void collect(LongIntMap touched, int depthLimit, int gridSize, Set<Long> into) {
        touched.forEach((key, depth) -> {
            if (depth <= depthLimit && (long) CellPos.unpackY(key) * gridSize < BAND_TOP) {
                into.add(key);
            }
        });
    }

    // A 16x16 shaft with its minimum corner at (minX, minZ), open from Y 1 to the surface.
    private record Shaft(int minX, int minZ) {

        boolean inside(int x, int z) {
            return x >= minX && x < minX + SHAFT_SIZE && z >= minZ && z < minZ + SHAFT_SIZE;
        }

        boolean solid(int x, int y, int z) {
            return inside(x, z) ? y <= 0 : y < SURFACE;
        }

        boolean fillsChunk(int chunkX, int chunkZ) {
            return minX == chunkX * 16 && minZ == chunkZ * 16;
        }

        // Whether the cell's column holds shaft air or a wall block next to it: anything else lies behind a wall and must never be reached.
        boolean touchesCell(int cellX, int cellZ, int gridSize) {
            for (int x = cellX * gridSize; x < cellX * gridSize + gridSize; x++) {
                for (int z = cellZ * gridSize; z < cellZ * gridSize + gridSize; z++) {
                    if (inside(x, z) || inside(x - 1, z) || inside(x + 1, z) || inside(x, z - 1) || inside(x, z + 1)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // Reference: bottom wall cells holding the first solid block of some line from the eye (0.02 degree fan, exact geometry).
        Set<Long> referenceCells(int gridSize, double ex, double ey, double ez, int maxDistance) {
            Set<Long> cells = new HashSet<>();
            double reach = Math.toRadians(16.0);
            double step = Math.toRadians(0.02);
            for (double a = -reach; a <= reach; a += step) {
                for (double b = -reach; b <= reach; b += step) {
                    double dx = Math.tan(a);
                    double dz = Math.tan(b);
                    double dy = -1.0;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= length;
                    dy /= length;
                    dz /= length;
                    double exit = Double.MAX_VALUE;
                    if (dx > 0) {
                        exit = Math.min(exit, (minX + SHAFT_SIZE - ex) / dx);
                    } else if (dx < 0) {
                        exit = Math.min(exit, (minX - ex) / dx);
                    }
                    if (dz > 0) {
                        exit = Math.min(exit, (minZ + SHAFT_SIZE - ez) / dz);
                    } else if (dz < 0) {
                        exit = Math.min(exit, (minZ - ez) / dz);
                    }
                    double floor = (1 - ey) / dy;
                    if (exit >= floor || exit > maxDistance) {
                        continue;
                    }
                    double hitY = ey + dy * exit;
                    if (hitY >= BAND_TOP || hitY >= SURFACE) {
                        continue;
                    }
                    int blockX = (int) Math.floor(ex + dx * (exit + 1.0e-7));
                    int blockY = (int) Math.floor(hitY);
                    int blockZ = (int) Math.floor(ez + dz * (exit + 1.0e-7));
                    cells.add(CellPos.pack(Math.floorDiv(blockX, gridSize), Math.floorDiv(blockY, gridSize), Math.floorDiv(blockZ, gridSize)));
                }
            }
            return cells;
        }
    }

    private static World world() {
        UUID id = UUID.nameUUIDFromBytes(WORLD_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (World) Proxy.newProxyInstance(FrontierSelfCheck.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMinHeight" -> 0;
                    case "getMaxHeight" -> 256;
                    case "getUID" -> id;
                    case "getName" -> WORLD_NAME;
                    case "isChunkLoaded" -> true;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> WORLD_NAME;
                    default -> null;
                });
    }
}
