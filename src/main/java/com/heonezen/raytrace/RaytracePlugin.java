package com.heonezen.raytrace;

import com.heonezen.raytrace.command.RaytraceCommand;
import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.diagnostics.ChunkMaskSelfTest;
import com.heonezen.raytrace.diagnostics.NativePacketSelfTest;
import com.heonezen.raytrace.diagnostics.RayMathSelfTest;
import com.heonezen.raytrace.engine.RaytraceEngine;
import com.heonezen.raytrace.listener.PlayerActivityListener;
import com.heonezen.raytrace.protocol.NativeProtocol;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Packet-level anti-ESP and anti-freecam protection for Paper and Folia. */
public final class RaytracePlugin extends JavaPlugin {

    private RaytraceConfig config;
    private RaytraceEngine engine;
    private String buildStamp = "unknown";

    @Override
    public void onEnable() {
        this.buildStamp = readBuildStamp();
        this.config = new RaytraceConfig(this);
        this.engine = new RaytraceEngine(this, config);

        // No other plugin is needed: each player's connection gets its own packet handler
        // (see RaytraceEngine#installNativeHandler).
        Bukkit.getPluginManager().registerEvents(new PlayerActivityListener(config, engine), this);

        RaytraceCommand command = new RaytraceCommand(this, config, engine);
        PluginCommand pluginCommand = getCommand("raytrace");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        engine.start();
        config.logSummary();
        getLogger().info("Raytrace enabled - packet-level ESP/freecam protection is active.");
        // Printed on every start so logs always show which build is running.
        getLogger().info("Raytrace version " + getDescription().getVersion() + " (built " + buildStamp + ")");

        String chunkProblem = engine.chunkMaskingProblem();
        if (chunkProblem != null) {
            getLogger().warning("Raytrace cannot mask the terrain in the chunk data: " + chunkProblem + ". Mobs, players and effects are still hidden, but the terrain itself is sent as it is.");
        }
        String blockProblem = engine.blockUpdateSupportProblem();
        if (blockProblem != null) {
            getLogger().warning("Raytrace cannot build per-player block-change packets: " + blockProblem + ". Hidden block changes are dropped rather than exposed; visible changes bundled " + "into the same packet may be missed until the next chunk update.");
        }
        String effectProblem = engine.effectPositionSupportProblem();
        if (effectProblem != null) {
            getLogger().warning("Raytrace cannot locate positional effects: " + effectProblem + ". Sounds, particles, explosions and block events may reveal hidden activity.");
        } else {
            getLogger().info("Raytrace positional-effect interception check passed.");
        }
        String spawnProblem = NativeProtocol.entitySpawnSupportProblem();
        if (spawnProblem != null) {
            getLogger().warning("Raytrace cannot filter entity spawns before they reach the client: " + spawnProblem + ". The periodic scan still hides them, but a spawn may be visible for a moment.");
        } else {
            getLogger().info("Raytrace entity-spawn interception check passed.");
        }

        String bundleProblem = engine.bundleSupportProblem();
        if (bundleProblem != null) {
            getLogger().warning("Raytrace cannot look inside packet bundles: " + bundleProblem + ". Entity spawns are sent inside bundles, so hidden entities may be visible for a moment before the periodic scan removes them.");
        } else {
            getLogger().info("Raytrace entity-bundle interception check passed.");
        }

        runSelfTestLater();
    }

    /** Runs the built-in self-check shortly after startup, once block mappings are loaded. */
    private void runSelfTestLater() {
        // GlobalRegionScheduler works on Paper and Folia/Canvas; the legacy Bukkit scheduler
        // does not.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, scheduledTask -> {
            try {
                List<RayMathSelfTest.Result> results = RayMathSelfTest.run();
                long failed = results.stream().filter(result -> !result.passed()).count();
                if (failed == 0) {
                    getLogger().info("Raytrace self-check passed (" + results.size() + " checks).");
                } else {
                    getLogger().warning("Raytrace self-check: " + failed + " of " + results.size()
                            + " checks FAILED - the protection may not be working correctly:");
                    for (RayMathSelfTest.Result result : results) {
                        if (!result.passed()) {
                            getLogger().warning("  - " + result.name() + (result.detail().isEmpty() ? "" : " -> " + result.detail()));
                        }
                    }
                }
            } catch (Throwable throwable) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Raytrace self-check could not be run", throwable);
            }
            try {
                List<NativePacketSelfTest.Result> results = NativePacketSelfTest.run();
                long failed = results.stream().filter(result -> !result.passed()).count();
                if (failed == 0) {
                    getLogger().info("Raytrace block-update copy self-check passed ("
                            + results.size() + " checks).");
                } else {
                    getLogger().warning("Raytrace block-update copy self-check: " + failed + " of "
                            + results.size() + " checks FAILED - hidden block changes could be "
                            + "exposed or visible changes could be dropped:");
                    for (NativePacketSelfTest.Result result : results) {
                        if (!result.passed()) {
                            getLogger().warning("  - " + result.name()
                                    + (result.detail().isEmpty() ? "" : " -> " + result.detail()));
                        }
                    }
                }
            } catch (Throwable throwable) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Raytrace block-update copy self-check could not be run", throwable);
            }
            runChunkMaskSelfTest();
        }, 100L);
    }

    /** Encodes, masks and decodes a real chunk on the region that owns it. */
    private void runChunkMaskSelfTest() {
        try {
            World testWorld = null;
            for (World candidate : Bukkit.getWorlds()) {
                if (candidate.getEnvironment() == World.Environment.NORMAL) {
                    testWorld = candidate;
                    break;
                }
            }
            if (testWorld == null) {
                getLogger().warning("Raytrace chunk-masking self-check skipped: no normal world is loaded.");
                return;
            }
            World world = testWorld;
            Location spawn = world.getSpawnLocation();
            Bukkit.getRegionScheduler().run(this, spawn, scheduledTask -> {
                ChunkMaskSelfTest.Result result = ChunkMaskSelfTest.run(engine.palettedContainerFactory(), world);
                if (result.passed()) {
                    getLogger().info("Raytrace chunk-masking self-check passed (" + result.detail() + ").");
                } else {
                    getLogger().warning("Raytrace chunk-masking self-check FAILED: " + result.detail()
                            + " - the terrain is NOT being masked in the chunk data.");
                }
            });
        } catch (RuntimeException exception) {
            getLogger().warning("Raytrace chunk-masking self-check could not be scheduled on the "
                    + "world region: " + exception);
        }
    }

    private String readBuildStamp() {
        try (java.io.InputStream stream = getResource("build.properties")) {
            if (stream == null) {
                return "unknown";
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(stream);
            String built = properties.getProperty("built");
            return built == null || built.isBlank() ? "unknown" : built.trim();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stop();
        }
    }

}

