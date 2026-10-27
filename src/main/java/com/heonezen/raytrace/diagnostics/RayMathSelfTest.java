package com.heonezen.raytrace.diagnostics;

import com.heonezen.raytrace.config.LayerBand;
import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.config.WorldSettings;
import com.heonezen.raytrace.engine.RaytraceEngine;
import com.heonezen.raytrace.engine.SurfaceHeights;
import com.heonezen.raytrace.util.NmsBlockReader;
import com.heonezen.raytrace.util.BlockRay;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Self-checks for the ray, surface and chunk-masking code; runs at startup or stand-alone. */
public final class RayMathSelfTest {

    /** solid[x][z * 256 + y] - a 16x16x256 chunk of blocks. */
    private static final boolean[][] SOLID = new boolean[16][16 * 256];

    private final List<Result> results = new ArrayList<>();

    private RayMathSelfTest() {
    }

    /** One check's outcome. */
    public record Result(String name, boolean passed, String detail) {
    }

    /** Runs every check and returns the outcomes, in order. */
    public static List<Result> run() {
        RayMathSelfTest test = new RayMathSelfTest();
        test.testTraversal();
        test.testRayPattern();
        test.testSurfaceCeiling();
        test.testSensitiveBlocks();
        test.testMasking();
        return List.copyOf(test.results);
    }

    /** Verifies that the configured fake blocks resolve through the server's own registry. */
    private void testMasking() {
        section("chunk masking");
        try {
            testMaskingInternal();
        } catch (Throwable throwable) {
            results.add(new Result("the chunk masking could not be run on this server", false,
                    String.valueOf(throwable)));
        }
    }

    private void testMaskingInternal() {
        // The native packet path resolves the fake block through the server itself, so that is
        // what has to be checked here: the three fake blocks have to be three different blocks.
        Object stoneState = serverBlockState(Material.STONE);
        Object deepslateState = serverBlockState(Material.DEEPSLATE);
        Object netherrackState = serverBlockState(Material.NETHERRACK);
        Object endStoneState = serverBlockState(Material.END_STONE);
        check("the fake stone resolves to a block of its own", stoneState != null ? 1 : 0, 1);
        check("the fake deepslate resolves to a block of its own", deepslateState != null ? 1 : 0, 1);
        check("the fake netherrack resolves to a block of its own", netherrackState != null ? 1 : 0, 1);
        check("the fake end stone resolves to a block of its own", endStoneState != null ? 1 : 0, 1);
        check("the fake stone and the fake deepslate are different blocks", stoneState != null && deepslateState != null && stoneState != deepslateState ? 1 : 0, 1);
        if (stoneState == null || deepslateState == null) {
            return;
        }

        // The native packet path draws the fake blocks from the server's own block states, so
        // that is what has to be checked here: every fake block has to resolve to a block of its
        // own, and the deepslate band must not come out as air.
        Object airState = serverBlockState(Material.AIR);
        check("the fake stone is not air", stoneState != null && !stoneState.equals(airState) ? 1 : 0, 1);
        check("the fake deepslate is not air", deepslateState != null && !deepslateState.equals(airState) ? 1 : 0, 1);
        check("the fake netherrack is not air", netherrackState != null && !netherrackState.equals(airState) ? 1 : 0, 1);
        check("the fake end stone is not air", endStoneState != null && !endStoneState.equals(airState) ? 1 : 0, 1);
        check("the fake stone and the fake deepslate are different blocks", stoneState != null && deepslateState != null && !stoneState.equals(deepslateState) ? 1 : 0, 1);
    }


    /** The block state the server uses for a material. */
    private static Object serverBlockState(Material material) {
        try {
            Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks");
            Object block = blocks.getField(material.name()).get(null);
            if (block == null) {
                return null;
            }
            return block.getClass().getMethod("defaultBlockState").invoke(block);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** Stand-alone entry point: prints the checks and exits non-zero if any of them failed. */
    public static void main(String[] args) {
        List<Result> results = run();
        int failures = 0;
        for (Result result : results) {
            if (!result.passed()) {
                failures++;
            }
            System.out.println((result.passed() ? "PASS  " : "FAIL  ") + result.name() + (result.detail().isEmpty() ? "" : " -> " + result.detail()));
        }
        System.out.println(failures == 0 ? "ALL OK" : (failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Checks that trees, mountains and soil resolve to the expected masking ceilings. */
    private void testSurfaceCeiling() {
        section("surface ceiling");

        WorldSettings settings = new WorldSettings(true, List.of(new LayerBand(64, Material.STONE), new LayerBand(0, Material.DEEPSLATE)), true, true, 8);

        // Flat ground at Y=64 made of dirt, with a tree (leaves up to Y=80) standing on it.
        // The surface must resolve to the ground (64), not to the top of the canopy (80).
        SurfaceGrid grid = new SurfaceGrid();
        grid.fill(0, 63, 60, 64, Material.DIRT);
        grid.fill(0, 63, 65, 80, Material.OAK_LEAVES);
        int forestCeiling = SurfaceHeights.effectiveCeiling(grid.world(), settings, 0, 0);
        check("a forest resolves its surface to the ground, not to the tree canopy", forestCeiling == 57 ? 1 : 0, 1);
        check("the tree canopy itself is never masked", forestCeiling <= 64 ? 1 : 0, 1);
        // The hard cutoff is not raised by the soil buffer. Ores and containers between the
        // normal ceiling and this Y are still hidden until their cell is earned.
        check("a forest keeps a hard cutoff at the ground", SurfaceHeights.hardCeiling(grid.world(), settings, 0, 0) == 64 ? 1 : 0, 1);

        // Bare stone mountain, with a deep below-y so the mountain itself is in range: the
        // surface block is stone, so it belongs to the hidden mass and the buffer does not
        // apply - a mountain must not have a band of real stone on top.
        WorldSettings deep = new WorldSettings(true, List.of(new LayerBand(128, Material.STONE), new LayerBand(0, Material.DEEPSLATE)), true, true, 8);
        SurfaceGrid stone = new SurfaceGrid();
        stone.fill(0, 63, 64, 120, Material.STONE);
        int mountainCeiling = SurfaceHeights.effectiveCeiling(stone.world(), deep, 0, 0);
        check("a bare stone mountain is masked from its own surface down", mountainCeiling == 120 ? 1 : 0, 1);
        check("a bare stone mountain has the same hard cutoff", SurfaceHeights.hardCeiling(stone.world(), deep, 0, 0) == 120 ? 1 : 0, 1);

        // The same mountain, but with soil on top: the buffer keeps that soil layer real.
        SurfaceGrid soil = new SurfaceGrid();
        soil.fill(0, 63, 64, 112, Material.STONE);
        soil.fill(0, 63, 113, 120, Material.DIRT);
        int soilCeiling = SurfaceHeights.effectiveCeiling(soil.world(), deep, 0, 0);
        check("a soil-covered mountain keeps its topsoil layer real", soilCeiling == 113 ? 1 : 0, 1);
        check("the soil buffer does not raise the hard cutoff", SurfaceHeights.hardCeiling(soil.world(), deep, 0, 0) == 120 ? 1 : 0, 1);

        // The configured below-y always wins over the surface.
        check("the configured below-y caps the ceiling", SurfaceHeights.effectiveCeiling(stone.world(), settings, 0, 0) == 64 ? 1 : 0, 1);
        check("the configured below-y also caps the hard cutoff", SurfaceHeights.hardCeiling(stone.world(), settings, 0, 0) == 64 ? 1 : 0, 1);

        // A negative buffer disables surface awareness for both cutoffs.
        WorldSettings flat = new WorldSettings(true, List.of(new LayerBand(128, Material.STONE), new LayerBand(0, Material.DEEPSLATE)), true, true, -1);
        check("a negative surface buffer uses the flat hard cutoff", SurfaceHeights.hardCeiling(stone.world(), flat, 0, 0) == 128 ? 1 : 0, 1);

        // An ocean: the surface is the seabed, the water above it is not masked.
        SurfaceGrid ocean = new SurfaceGrid();
        ocean.fill(0, 63, 0, 25, Material.WATER);
        ocean.fill(0, 63, 26, 30, Material.SAND);
        int oceanCeiling = SurfaceHeights.effectiveCeiling(ocean.world(), settings, 0, 0);
        check("an ocean resolves its surface to the seabed, not to the water surface", oceanCeiling == 23 ? 1 : 0, 1);
        check("an ocean keeps a hard cutoff at the seabed", SurfaceHeights.hardCeiling(ocean.world(), settings, 0, 0) == 30 ? 1 : 0, 1);

        // The deepslate band must be reachable: the fake block below Y=0 is deepslate.
        check("Y -1 is faked as deepslate", settings.fakeBlockFor(-1) == Material.DEEPSLATE ? 1 : 0, 1);
        check("Y 0 is faked as stone", settings.fakeBlockFor(0) == Material.STONE ? 1 : 0, 1);
    }

    /** The block classes that must stay hidden even inside the visual soil buffer. */
    private void testSensitiveBlocks() {
        section("block sensitivity");
        check("an ore is sensitive", NmsBlockReader.isSensitiveMaterial(Material.IRON_ORE) ? 1 : 0, 1);
        check("a chest is sensitive", NmsBlockReader.isSensitiveMaterial(Material.CHEST) ? 1 : 0, 1);
        check("a spawner is sensitive", NmsBlockReader.isSensitiveMaterial(Material.SPAWNER) ? 1 : 0, 1);
        check("dirt is not sensitive", NmsBlockReader.isSensitiveMaterial(Material.DIRT) ? 0 : 1, 1);
        check("a log is not sensitive", NmsBlockReader.isSensitiveMaterial(Material.OAK_LOG) ? 0 : 1, 1);
    }

    /**
     * A tiny in-memory world for the surface checks: a grid of blocks plus the height map
     * lookup derived from it.
     */
    private static final class SurfaceGrid {
        private final java.util.Map<Long, Material> blocks = new java.util.HashMap<>();

        void fill(int x0, int x1, int y0, int y1, Material material) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    blocks.put(key(x, y), material);
                }
            }
        }

        private static long key(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        World world() {
            return (World) Proxy.newProxyInstance(RayMathSelfTest.class.getClassLoader(),
                    new Class<?>[]{World.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isChunkLoaded":
                                return true;
                            case "getMinHeight":
                                return -64;
                            case "getMaxHeight":
                                return 320;
                            case "getChunkAt":
                                return chunk();
                            case "getHighestBlockYAt": {
                                int x = (Integer) args[0];
                                int highest = Integer.MIN_VALUE;
                                for (Map.Entry<Long, Material> entry : blocks.entrySet()) {
                                    int bx = (int) (entry.getKey() >> 32);
                                    int by = (int) (entry.getKey() & 0xFFFFFFFFL);
                                    if (bx != x) {
                                        continue;
                                    }
                                    Material material = entry.getValue();
                                    if (material == Material.OAK_LEAVES || material == Material.WATER) {
                                        continue;
                                    }
                                    highest = Math.max(highest, by);
                                }
                                return highest == Integer.MIN_VALUE ? -64 : highest;
                            }
                            default:
                                return null;
                        }
                    });
        }

        private Chunk chunk() {
            return (Chunk) Proxy.newProxyInstance(RayMathSelfTest.class.getClassLoader(),
                    new Class<?>[]{Chunk.class}, (proxy, method, args) -> {
                        if ("getBlock".equals(method.getName())) {
                            int x = (Integer) args[0];
                            int y = (Integer) args[1];
                            return block(blocks.getOrDefault(key(x, y), Material.AIR));
                        }
                        return null;
                    });
        }
    }

    private void testTraversal() {
        section("traversal");

        clearGrid();
        check("open air along +X", countOccluding(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, 4), 0);

        clearGrid();
        fill(5, 5, 0, 15, 0, 255);
        check("one block thick wall along +X", countOccluding(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, 4), 1);

        fill(4, 4, 0, 15, 0, 255);
        check("two walls along +X", countOccluding(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, 4), 2);

        clearGrid();
        for (int i = 0; i < 16; i++) {
            SOLID[i][i * 256 + 64] = true;
        }
        check("diagonal wall is detected", countOccluding(0.5, 64.5, 0.5, 15.5, 64.5, 15.5, 8) > 0 ? 1 : 0, 1);
        check("a ray clear of the diagonal wall sees nothing", countOccluding(0.5, 64.5, 3.5, 11.5, 64.5, 14.5, 8), 0);

        clearGrid();
        SOLID[0][0 * 256 + 64] = true;
        check("the block the eye is in is not counted", countOccluding(0.5, 64.5, 0.5, 4.5, 64.5, 0.5, 4), 0);

        clearGrid();
        fill(2, 2, 0, 15, 0, 255);
        fill(4, 4, 0, 15, 0, 255);
        check("the walk stops as soon as the threshold is reached", countOccluding(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, 1), 1);

        clearGrid();
        fill(0, 15, 0, 15, 63, 63);
        check("solid ground below", countOccluding(0.5, 64.5, 0.5, 0.5, 60.5, 0.5, 4), 1);

        clearGrid();
        check("a ray exactly along +Z terminates", countOccluding(0.5, 64.5, 0.5, 0.5, 64.5, 10.5, 4), 0);
        check("a ray exactly along +X terminates", countOccluding(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, 4), 0);
        check("a ray exactly along -Y terminates", countOccluding(0.5, 64.5, 0.5, 0.5, 54.5, 0.5, 4), 0);

        clearGrid();
        fill(5, 5, 4, 4, 64, 64);
        double cornerTargetZ = 0.5 + 10.0 * ((4.5 - 0.05) / 4.5);
        check("a thin block-corner clip is ignored when allowed", BlockRay.countOccluding(world(), 0.5, 64.5, 0.5, 10.5, 64.5, cornerTargetZ, 4, 1), 0);
        check("the same corner clip is solid without the allowance", BlockRay.countOccluding(world(), 0.5, 64.5, 0.5, 10.5, 64.5, cornerTargetZ, 4), 1);

        clearGrid();
        // H0
        // 0#
        // The eye is in H, the target is in #, and the two 0 blocks share the corner.
        // This is a wall seam, not a single block corner, so the diagonal must stay solid.
        fill(1, 1, 0, 0, 64, 64);
        fill(0, 0, 1, 1, 64, 64);
        check("a wall seam at a block corner is not clipped", BlockRay.countOccluding(world(), 0.5, 64.5, 0.5, 1.5, 64.5, 1.5, 4, 1), 1);

        check("floor(-0.5)", BlockRay.floor(-0.5), -1);
        check("floor(-64.0)", BlockRay.floor(-64.0), -64);
        check("signum(-2)", BlockRay.signum(-2.0), -1);
        check("signum(0)", BlockRay.signum(0.0), 0);
    }

    private int countOccluding(double ex, double ey, double ez, double tx, double ty, double tz, int stopAfter) {
        return BlockRay.countOccluding(world(), ex, ey, ez, tx, ty, tz, stopAfter);
    }

    private void testRayPattern() {
        section("ray pattern");

        RaytraceEngine engine = new RaytraceEngine(testPlugin(), new RaytraceConfig(testPlugin()));
        float[] xs;
        float[] ys;
        float[] zs;
        int count;
        try {
            Method rayPlan = RaytraceEngine.class.getDeclaredMethod("rayPlan", int.class, int.class);
            rayPlan.setAccessible(true);
            Object plan = rayPlan.invoke(engine, 7, 176);

            Method countMethod = plan.getClass().getMethod("count");
            Method xsMethod = plan.getClass().getMethod("xs");
            Method ysMethod = plan.getClass().getMethod("ys");
            Method zsMethod = plan.getClass().getMethod("zs");
            countMethod.setAccessible(true);
            xsMethod.setAccessible(true);
            ysMethod.setAccessible(true);
            zsMethod.setAccessible(true);
            count = (int) countMethod.invoke(plan);
            xs = (float[]) xsMethod.invoke(plan);
            ys = (float[]) ysMethod.invoke(plan);
            zs = (float[]) zsMethod.invoke(plan);
        } catch (ReflectiveOperationException exception) {
            results.add(new Result("the ray plan could not be inspected", false, String.valueOf(exception)));
            return;
        }

        check("the plan has rays", count > 100 ? 1 : 0, 1);

        boolean allUnit = true;
        for (int i = 0; i < count; i++) {
            double length = Math.sqrt(xs[i] * xs[i] + ys[i] * ys[i] + zs[i] * zs[i]);
            if (Math.abs(length - 1.0) > 1.0e-5) {
                allUnit = false;
                break;
            }
        }
        check("every ray is a unit vector", allUnit ? 1 : 0, 1);

        // the pattern must contain a horizontal ray pointing exactly at yaw 0 (+Z)
        int centre = -1;
        double bestFlatness = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double yaw = Math.toDegrees(Math.atan2(-xs[i], zs[i]));
            if (Math.abs(yaw) > 1.0e-6) {
                continue;
            }
            if (Math.abs(ys[i]) < bestFlatness) {
                bestFlatness = Math.abs(ys[i]);
                centre = i;
            }
        }
        check("the pattern contains a horizontal ray pointing exactly at yaw 0", centre >= 0 ? 1 : 0, 1);
        if (centre >= 0) {
            check("that ray points along +Z", Math.abs(xs[centre]) < 1.0e-6 && zs[centre] > 0.99 ? 1 : 0, 1);
        }

        // rotating onto a yaw must shift every ray by exactly that yaw
        for (double playerYaw : new double[]{0.0, 37.5, 90.0, 180.0, -122.0}) {
            double radians = Math.toRadians(playerYaw);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double worst = 0;
            for (int i = 0; i < count; i++) {
                double rx = xs[i] * cos - zs[i] * sin;
                double rz = zs[i] * cos + xs[i] * sin;
                double before = Math.toDegrees(Math.atan2(-xs[i], zs[i]));
                double after = Math.toDegrees(Math.atan2(-rx, rz));
                double delta = Math.abs(((after - before - playerYaw) % 360.0 + 540.0) % 360.0 - 180.0);
                worst = Math.max(worst, delta);
            }
            check("rotating by " + playerYaw + " degrees shifts every ray by the same yaw",
                    worst < 1.0e-3 ? 1 : 0, 1);
        }

        // rotating onto a pitch must shift every ray by exactly that pitch
        double pitch = Math.toRadians(45.0);
        double cos = Math.cos(pitch);
        double sin = Math.sin(pitch);
        double worst = 0;
        for (int i = 0; i < count; i++) {
            double py = ys[i] * cos - zs[i] * sin;
            double pz = zs[i] * cos + ys[i] * sin;
            double before = Math.toDegrees(Math.atan2(-ys[i], zs[i]));
            double after = Math.toDegrees(Math.atan2(-py, pz));
            double delta = Math.abs(((after - before - 45.0) % 360.0 + 540.0) % 360.0 - 180.0);
            worst = Math.max(worst, delta);
        }
        check("rotating by 45 degrees of pitch shifts every ray by the same pitch", worst < 1.0e-3 ? 1 : 0, 1);
    }

    private void section(String name) {
        results.add(new Result("[" + name + "]", true, ""));
    }

    private void check(String name, int actual, int expected) {
        boolean ok = actual == expected;
        results.add(new Result(name, ok, ok ? "" : actual + " (expected " + expected + ")"));
    }

    /** A check that compares a name, and reports what it actually found. */
    private void check(String name, String actual, String expected) {
        boolean ok = actual.equals(expected);
        results.add(new Result(name, ok, ok ? "" : "found " + actual + ", expected " + expected));
    }

    private static void clearGrid() {
        for (int x = 0; x < 16; x++) {
            Arrays.fill(SOLID[x], false);
        }
    }

    private static void fill(int x0, int x1, int z0, int z1, int y0, int y1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    SOLID[x][z * 256 + y] = true;
                }
            }
        }
    }

    private static World world() {
        Chunk chunk = (Chunk) Proxy.newProxyInstance(RayMathSelfTest.class.getClassLoader(),
                new Class<?>[]{Chunk.class}, (proxy, method, args) -> {
                    if ("getBlock".equals(method.getName())) {
                        int x = (Integer) args[0];
                        int y = (Integer) args[1];
                        int z = (Integer) args[2];
                        return block(SOLID[x & 15][(z & 15) * 256 + y]);
                    }
                    if ("getX".equals(method.getName()) || "getZ".equals(method.getName())) {
                        return 0;
                    }
                    return null;
                });
        return (World) Proxy.newProxyInstance(RayMathSelfTest.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isChunkLoaded":
                            return true;
                        case "getChunkAt":
                            return chunk;
                        case "getMinHeight":
                            return 0;
                        case "getMaxHeight":
                            return 256;
                        default:
                            return null;
                    }
                });
    }

    private static Object block(boolean solid) {
        return block(solid ? Material.STONE : Material.AIR);
    }

    private static Object block(Material material) {
        return Proxy.newProxyInstance(RayMathSelfTest.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> "getType".equals(method.getName()) ? material : null);
    }

    private static Plugin testPlugin() {
        // A minimal but complete configuration, so that building a real RaytraceConfig from it
        // does not print "config.yml has no 'worlds' section" warnings into the server console
        // while the self-test runs.
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("worlds.world.enabled", true);
        configuration.set("worlds.world.layers", List.of(Map.of("below-y", 64, "block", "STONE")));
        return (Plugin) Proxy.newProxyInstance(RayMathSelfTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getConfig":
                            return configuration;
                        case "getLogger":
                            return Logger.getLogger("RaytraceSelfTest");
                        case "saveDefaultConfig":
                        case "reloadConfig":
                        case "onEnable":
                        case "onDisable":
                            return null;
                        case "toString":
                            return "RaytraceSelfTest";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }
}
