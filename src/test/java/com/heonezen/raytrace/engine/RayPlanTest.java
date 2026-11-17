package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.TestPlugins;
import com.heonezen.raytrace.config.RaytraceConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayPlanTest {

    private static RaytraceEngine engine(int targetRays) {
        TestPlugins plugins = new TestPlugins();
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("worlds.world.enabled", true);
        configuration.set("grids.blocks.target-total-rays", targetRays);
        return new RaytraceEngine(plugins.plugin, new RaytraceConfig(plugins.plugin, configuration));
    }

    @ParameterizedTest(name = "grid {0}, radius {1}, {2} rays")
    @CsvSource({"8, 176, 2600", "8, 176, 1000", "4, 272, 6000", "16, 64, 500", "1, 512, 10000"})
    void patternIsUnitLengthCoversAllDirectionsAndHitsItsBudget(int grid, int radius, int target) {
        RaytraceEngine.RayPlan plan = engine(target).rayPlan(grid, radius);
        int count = plan.count();
        // Fewer rays than the budget is fine when the view needs fewer; more than ~25% over is not.
        assertTrue(count >= 100 && count <= target * 1.25, "count " + count + " for target " + target);
        boolean ahead = false;
        boolean behind = false;
        boolean up = false;
        boolean down = false;
        for (int i = 0; i < count; i++) {
            double x = plan.xs()[i];
            double y = plan.ys()[i];
            double z = plan.zs()[i];
            assertEquals(1.0, Math.sqrt(x * x + y * y + z * z), 1.0e-5, "ray " + i + " is not a unit vector");
            ahead |= z > 0.9999 && Math.abs(x) < 1.0e-4;
            behind |= z < -0.9999 && Math.abs(x) < 1.0e-4;
            up |= y > 0.99;
            down |= y < -0.99;
        }
        assertTrue(ahead, "a ray points exactly ahead");
        assertTrue(behind, "a ray points exactly behind");
        assertTrue(up && down, "the pattern reaches up and down");
        assertTrue(plan.yawStep() > 0 && plan.pitchStep() > 0);
    }

    @ParameterizedTest(name = "radius {0}")
    @CsvSource({"64", "176", "512"})
    void sameSettingsReuseTheCachedPlan(int radius) {
        RaytraceEngine engine = engine(2600);
        assertTrue(engine.rayPlan(8, radius) == engine.rayPlan(8, radius));
    }
}
