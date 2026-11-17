package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.TestPlugins;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs the real scan code on a deep shaft seen from its rim: far walls must turn real, nothing behind them may.
class FrontierCoverageTest {

    @ParameterizedTest(name = "grid size {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 8, 12, 16})
    void deepShaftIsRevealedWithoutLeaks(int size) {
        YamlConfiguration grids = new YamlConfiguration();
        grids.set("blocks.size", size);
        List<FrontierSelfCheck.Outcome> outcomes = FrontierSelfCheck.run(new TestPlugins().plugin, grids);
        assertEquals(2, outcomes.size());
        for (FrontierSelfCheck.Outcome outcome : outcomes) {
            assertEquals(0, outcome.leaks(), outcome + ": a cell behind a wall was revealed");
            assertTrue(outcome.passed(), outcome.toString());
            assertTrue(outcome.refined() >= outcome.patternOnly(), outcome.toString());
        }
    }

    @ParameterizedTest(name = "grid size {0}")
    @ValueSource(ints = {8, 16})
    void thePlainPatternAloneWouldMissTheBottom(int size) {
        // Proves the scenario is hard: without refinement most visible cells stay fake.
        YamlConfiguration grids = new YamlConfiguration();
        grids.set("blocks.size", size);
        for (FrontierSelfCheck.Outcome outcome : FrontierSelfCheck.run(new TestPlugins().plugin, grids)) {
            assertTrue(outcome.patternOnly() < outcome.visible() / 2, outcome.toString());
        }
    }
}
