package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.config.GridSettings;
import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.config.WorldSettings;
import com.heonezen.raytrace.protocol.NativeProtocol;
import com.heonezen.raytrace.util.BlockRay;
import com.heonezen.raytrace.util.CellPos;
import com.heonezen.raytrace.util.EntityKind;
import com.heonezen.raytrace.util.EntityKinds;
import com.heonezen.raytrace.util.EntitySnapshot;
import com.heonezen.raytrace.util.LongIntMap;
import com.heonezen.raytrace.util.NmsBlockReader;
import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

// Terrain and entity visibility engine.
public final class RaytraceEngine {

    private static final long TICK_PERIOD = 1L;

    // Hard cap on the scan radius, regardless of view distance.
    private static final int ABSOLUTE_MAX_RADIUS_CHUNKS = 32;
    // Extra chunks past the player's view distance, so edge chunks are earned before they appear.
    private static final int VIEW_DISTANCE_MARGIN_CHUNKS = 1;

    // Horizontal cones in yaw degrees; front is widest and back narrowest.
    private static final double FRONT_MIN_YAW = -85.0;
    private static final double FRONT_MAX_YAW = 85.0;
    private static final double SIDE_MIN_YAW = 85.0;
    private static final double SIDE_MAX_YAW = 155.0;
    private static final double BACK_MIN_YAW = 155.0;
    private static final double BACK_MAX_YAW = 205.0;
    private static final double VERTICAL_MIN_PITCH = -89.0;
    private static final double VERTICAL_MAX_PITCH = 89.0;

    private static final double FRONT_SPAN_DEGREES = FRONT_MAX_YAW - FRONT_MIN_YAW;
    private static final double SIDE_SPAN_DEGREES = SIDE_MAX_YAW - SIDE_MIN_YAW;
    private static final double BACK_SPAN_DEGREES = BACK_MAX_YAW - BACK_MIN_YAW;
    private static final double VERTICAL_SPAN_DEGREES = VERTICAL_MAX_PITCH - VERTICAL_MIN_PITCH;

    // One thin corner clip per entity ray, so grazing a corner is not counted as a wall.
    private static final int ENTITY_CORNER_SKIPS = 1;

    // Small body-sized safety bubble.
    private static final int BUBBLE_HORIZONTAL_BLOCKS = 2;
    private static final int BUBBLE_DOWN_BLOCKS = 3;
    private static final int BUBBLE_UP_BLOCKS = 1;

    // Horizontal and vertical spread of the focus rays, in degrees.
    private static final double FOCUS_HORIZONTAL_OFFSET_DEGREES = 20.0;
    private static final double FOCUS_VERTICAL_OFFSET_DEGREES = 14.0;
    // Crosshair plus four rays around it, as yaw/pitch offsets in degrees.
    private static final int[][] FOCUS_RAY_OFFSETS = {{0, 0}, {(int) FOCUS_HORIZONTAL_OFFSET_DEGREES, 0}, {(int) -FOCUS_HORIZONTAL_OFFSET_DEGREES, 0}, {0, (int) FOCUS_VERTICAL_OFFSET_DEGREES}, {0, (int) -FOCUS_VERTICAL_OFFSET_DEGREES}};
    // Cap on blocks the focus pass may examine per tick.
    private static final int FOCUS_STEP_BUDGET = 4_000;

    // Idle refinement: while the player stands still the pattern is cast again, shifted by these fractions of the ray spacing (yaw, pitch).
    static final double[][] REFINE_OFFSETS = {{0.0, 0.0}, {0.5, 0.0}, {0.0, 0.5}, {0.5, 0.5}};
    // Frontier growth works within 80 degrees of the crosshair: that covers the screen even at the maximum field of view on a 32:9 monitor.
    private static final double FRONTIER_VIEW_CONE_COS = Math.cos(Math.toRadians(80.0));

    // How often the (comparatively expensive) permission lookup is repeated.
    private static final long PERMISSION_INTERVAL_TICKS = 40L;
    // An unchanged entity verdict is reused for at most this many ticks.
    private static final long ENTITY_VERDICT_MAX_AGE_TICKS = 40L;
    // Movement below this (blocks, squared) of both viewer and entity keeps the old verdict.
    private static final double ENTITY_VERDICT_MOVE_SQ = 0.0625;
    // How long a known entity stays visible after a wall hides it (0.5 s), so corners do not flicker. Not for underground or new spawns.
    private static final long ENTITY_HIDE_DELAY_TICKS = 10L;
    // How long the per-column ceilings of a chunk are reused for masking decisions (3 seconds).
    private static final long CEILING_CACHE_NANOS = 3_000_000_000L;
    // A stale chunk ceiling is still a fine upper bound for skipping air cells (2 minutes).
    private static final long CEILING_BOUND_NANOS = 120_000_000_000L;
    private static final int MAX_CEILING_ENTRIES_PER_WORLD = 8192;
    private static final int MAX_DEFERRED_CELLS = 4096;

    private final Plugin plugin;
    private final RaytraceConfig config;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Map<Material, BlockData> fakeBlockDataCache = new ConcurrentHashMap<>();
    private final Map<RayCounts, RayPlan> rayPlanCache = new ConcurrentHashMap<>();

    private final AtomicBoolean loggedFirstOcclusionFailure = new AtomicBoolean();
    private final AtomicBoolean loggedFirstNearbyFailure = new AtomicBoolean();
    private final AtomicBoolean loggedFirstTrackerFailure = new AtomicBoolean();
    private final AtomicBoolean loggedFirstBlockCopyFailure = new AtomicBoolean();
    private final AtomicBoolean loggedFirstChunkMaskFailure = new AtomicBoolean();
    private final AtomicBoolean loggedFirstBundleFailure = new AtomicBoolean();
    private final AtomicBoolean loggedDirectBlockFailure = new AtomicBoolean();
    private final AtomicBoolean nativeUnavailableLogged = new AtomicBoolean();

    // Set once the direct (server-state) block-change path fails; the Bukkit path takes over.
    private volatile boolean directBlockChangesBroken;

    // The masking ceiling per column of a chunk, as seen by the chunk packets (short-lived).
    private final Map<CeilingKey, CeilingEntry> ceilingCache = new ConcurrentHashMap<>();
    // The same data for point lookups (block updates, effects, entity checks), per world.
    private final Map<UUID, ConcurrentHashMap<Long, CeilingEntry>> pointCeilings = new ConcurrentHashMap<>();
    // The server's own block state for each fake block material.
    private final Map<Material, Object> fakeStateCache = new ConcurrentHashMap<>();
    // The server's own factory for the block-state and biome containers of a section.
    private volatile Object palettedContainerFactory;

    private record CeilingKey(UUID world, int chunkX, int chunkZ) {}

    // Per-column ceilings of one chunk. maxHard bounds every masked Y in the chunk.
    record CeilingEntry(int[] ceilings, int[] oreCeilings, int maxHard, long expiresAt, long boundUntil) {}

    public RaytraceEngine(Plugin plugin, RaytraceConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Folia: run on the player's own scheduler; the enable thread may not touch their chunks.
            player.getScheduler().run(plugin, ignored -> onJoin(player), null);
        }
    }

    public void stop() {
        for (ScheduledTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (PlayerState state : states.values()) {
            try {
                // Hand every withheld entity back, then take the handler out of the pipeline.
                for (PlayerState.HiddenEntity hidden : List.copyOf(state.hiddenEntities())) {
                    state.unhideEntity(hidden.id);
                    replaySpawn(state, hidden);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.FINE, "Could not restore hidden entities on shutdown", exception);
            }
            NativeProtocol.uninstall(state.channel());
        }
        states.clear();
        ceilingCache.clear();
        pointCeilings.clear();
    }

    public void onJoin(Player player) {
        PlayerState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerState());
        Location eye = player.getEyeLocation();
        state.setWorld(eye.getWorld());
        state.setSpectator(player.getGameMode() == GameMode.SPECTATOR);
        state.setPermissionBypass(player.hasPermission("raytrace.bypass"));
        state.updateEye(eye.getX(), eye.getY(), eye.getZ());
        state.setViewerId(player.getEntityId());
        if (!installNativeHandler(player, state)) {
            // Without the packet handler nothing is masked, so the scans would be wasted work.
            states.remove(player.getUniqueId(), state);
            return;
        }
        if (eye.getWorld() != null) {
            immediateBubbleReveal(player, state, config.worldSettings(eye.getWorld()), eye.getWorld(), eye.getX(), eye.getY(), eye.getZ());
        }
        startTask(player, state);
    }

    public void onQuit(Player player) {
        ScheduledTask task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        PlayerState state = states.remove(player.getUniqueId());
        if (state != null) {
            // The client is gone: there is nobody to hand hidden entities back to.
            state.clearEntities();
        }
    }

    // Full reset after a world change or respawn; same-world teleports keep what was earned.
    public void resetPlayer(Player player) {
        resetPlayer(player, null);
    }

    // Reset after a teleport, respawn or world change.
    public void resetPlayer(Player player, Location destination) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        World newWorld = destination != null && destination.getWorld() != null ? destination.getWorld() : player.getWorld();
        World oldWorld = state.world();
        boolean worldChanged = oldWorld == null || !oldWorld.getUID().equals(newWorld.getUID());
        if (worldChanged) {
            // A different world: nothing earned or hidden carries over, and the client has already dropped every entity it knew.
            state.clear();
        } else {
            state.invalidateFocus();
        }
        state.setWorld(newWorld);
        double ex;
        double ey;
        double ez;
        if (destination != null) {
            ex = destination.getX();
            ey = destination.getY() + player.getEyeHeight();
            ez = destination.getZ();
        } else {
            Location eye = player.getEyeLocation();
            ex = eye.getX();
            ey = eye.getY();
            ez = eye.getZ();
        }
        state.updateEye(ex, ey, ez);
        // Apply the new surroundings immediately, before the client can mispredict collision.
        immediateBubbleReveal(player, state, config.worldSettings(newWorld), newWorld, ex, ey, ez);
    }

    // Full reload after a config change: every player's terrain is re-masked and earned again, on their own scheduler.
    public void reloadAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin, ignored -> reloadPlayer(player), null);
        }
    }

    private void reloadPlayer(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        World world = player.getWorld();
        WorldSettings worldSettings = config.worldSettings(world);
        Location eye = player.getEyeLocation();
        state.setWorld(world);
        state.updateEye(eye.getX(), eye.getY(), eye.getZ());
        // Re-mask everything the viewer had earned, then earn again from the new settings.
        revealHiddenEntities(player, state);
        Set<CellPos> earned = state.forgetInside();
        if (!earned.isEmpty()) {
            sendCellChanges(player, state, world, worldSettings, new ArrayList<>(earned), false);
        }
        if (state.hasHiddenEntities()) {
            // Should be empty after revealHiddenEntities, kept for safety.
            revealHiddenEntities(player, state);
        }
        state.invalidateFocus();
        if (worldSettings.terrainMaskingEnabled()) {
            immediateBubbleReveal(player, state, worldSettings, world, eye.getX(), eye.getY(), eye.getZ());
        }
        if (worldSettings.enabled() && !state.bypassed()) {
            // Earliest possible scan on the next scheduled tick (eye already updated).
            state.invalidateScan();
        }
    }


    // Drops world-derived caches that depend on configuration.
    public void clearConfigCaches() {
        ceilingCache.clear();
        pointCeilings.clear();
    }

    public void onChunkUnload(World world, int chunkX, int chunkZ) {
        UUID worldId = world.getUID();
        for (PlayerState state : states.values()) {
            World stateWorld = state.world();
            // Cells belong to the world the player is in; a chunk of another dimension at the same coordinates must not erase them.
            if (stateWorld != null && stateWorld.getUID().equals(worldId)) {
                state.forgetColumn(chunkX, chunkZ);
            }
        }
        pointCeilings.getOrDefault(worldId, EMPTY_CEILINGS).remove(chunkKey(chunkX, chunkZ));
        ceilingCache.remove(new CeilingKey(worldId, chunkX, chunkZ));
    }

    private static final ConcurrentHashMap<Long, CeilingEntry> EMPTY_CEILINGS = new ConcurrentHashMap<>();

    private void startTask(Player player, PlayerState state) {
        ScheduledTask existing = tasks.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduledTask -> tick(player, state), () -> onQuit(player), 1L, TICK_PERIOD);
        if (task != null) {
            tasks.put(player.getUniqueId(), task);
        }
    }

    private void tick(Player player, PlayerState state) {
        if (!player.isOnline()) {
            return;
        }
        state.incrementTick();
        try {
            tickInternal(player, state);
        } catch (Throwable throwable) {
            // Keep the task alive: one bad call must not end protection. Repeated failures are logged at most every 30 s.
            long now = System.nanoTime();
            long next = nextTickFailureLog.get();
            if (now - next >= 0 && nextTickFailureLog.compareAndSet(next, now + TICK_FAILURE_LOG_INTERVAL_NANOS)) {
                long suppressed = suppressedTickFailures.getAndSet(0);
                plugin.getLogger().log(Level.WARNING, "Raytrace tick failed for " + player.getName() + (suppressed > 0 ? " (" + suppressed + " similar failures were not logged)" : ""), throwable);
            } else {
                suppressedTickFailures.incrementAndGet();
            }
        }
    }

    // Per-thread time budgets for the terrain and entity scans of all players together.
    private final ScanBudget terrainBudget = new ScanBudget();
    private final ScanBudget entityBudget = new ScanBudget();
    // A scan is not started with less than this left in the shared budget.
    private static final long MIN_SCAN_NANOS = 150_000L;

    private static final long TICK_FAILURE_LOG_INTERVAL_NANOS = 30_000_000_000L;
    private final java.util.concurrent.atomic.AtomicLong nextTickFailureLog = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
    private final java.util.concurrent.atomic.AtomicLong suppressedTickFailures = new java.util.concurrent.atomic.AtomicLong();

    private void tickInternal(Player player, PlayerState state) {
        // One location read per tick; everything below works from it.
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return;
        }
        state.setWorld(world);
        state.updateEye(eye.getX(), eye.getY(), eye.getZ());
        Entity vehicle = player.getVehicle();
        state.setVehicleId(vehicle == null ? -1 : vehicle.getEntityId());
        state.setViewerId(player.getEntityId());
        refreshBypass(player, state);

        WorldSettings worldSettings = config.worldSettings(world);
        if (!worldSettings.enabled() || state.bypassed()) {
            if (state.hasHiddenEntities()) {
                revealHiddenEntities(player, state);
            }
            return;
        }

        if (worldSettings.terrainMaskingEnabled()) {
            // Cheap look-direction pass, skipped while the player neither moves nor turns, then the throttled full scan.
            if (state.shouldRunFocus(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch())) {
                // Never skipped (it is what the player looks at), but its time counts too.
                long started = System.nanoTime();
                revealFocusCells(player, state, worldSettings, world, eye);
                terrainBudget.spend(System.nanoTime() - started);
            }
            refreshVisibleCells(player, state, worldSettings, world, eye);
        }
        refreshEntityVisibility(player, state, worldSettings, world, eye);
    }

    // Spectators are exempt; the permission is looked up only every couple of seconds.
    private void refreshBypass(Player player, PlayerState state) {
        boolean was = state.bypassed();
        state.setSpectator(player.getGameMode() == GameMode.SPECTATOR);
        if (state.shouldRefreshPermission(PERMISSION_INTERVAL_TICKS)) {
            state.setPermissionBypass(player.hasPermission("raytrace.bypass"));
        }
        boolean now = state.bypassed();
        if (was != now) {
            if (now) {
                revealHiddenEntities(player, state);
            } else {
                state.clearVisibleCells();
            }
        }
    }

    // Whether the given block shows real data to this viewer right now.
    public boolean isBlockVisible(Player viewer, int x, int y, int z) {
        PlayerState state = states.get(viewer.getUniqueId());
        return state != null && state.isCellVisible(CellPos.of(x, y, z, config.blockGrid().size()));
    }

    // Whether this entity id is currently withheld from the viewer.
    public boolean isEntityIdHidden(Player viewer, int entityId) {
        PlayerState state = states.get(viewer.getUniqueId());
        return state != null && state.isEntityHidden(entityId);
    }

    // Cached bypass state; thread-safe.
    public boolean isBypassedCached(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        return state != null && state.bypassed();
    }

    // Reveals the cell containing this block right away, e.g. after the player edits it.
    public void forceRevealBlock(Player player, int x, int y, int z) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null || state.bypassed()) {
            return;
        }
        // The block change alters occlusion even in an earned cell: drop cached entity verdicts.
        state.invalidateEntityChecks();
        // The new opening may show terrain no ray has reached yet: let an idle player's refinement passes run once more.
        state.restartRefinement();
        World world = player.getWorld();
        WorldSettings worldSettings = config.worldSettings(world);
        if (!worldSettings.terrainMaskingEnabled()) {
            return;
        }
        CellPos cell = CellPos.of(x, y, z, config.blockGrid().size());
        if (state.isCellVisible(cell)) {
            return;
        }
        state.markCellVisible(cell);
        sendCellChanges(player, state, world, worldSettings, List.of(cell), true);
    }

    // Reveals the player's surroundings immediately, used on join, teleport and respawn before the next scheduled scan.
    private void immediateBubbleReveal(Player player, PlayerState state, WorldSettings worldSettings, World world, double eyeX, double eyeY, double eyeZ) {
        if (!worldSettings.terrainMaskingEnabled() || state.bypassed()) {
            return;
        }
        LongIntMap touched = new LongIntMap(32);
        try {
            addBubble(world, worldSettings, eyeX, eyeY, eyeZ, touched);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Immediate bubble reveal skipped for " + player.getName(), exception);
            return;
        }
        state.bubbleMoved(BlockRay.floor(eyeX), BlockRay.floor(eyeY), BlockRay.floor(eyeZ));
        revealAll(player, state, world, worldSettings, touched, Integer.MAX_VALUE);
    }

    // Cheap pass along the view direction: five rays that reveal what the player looks at right away.
    private void revealFocusCells(Player player, PlayerState state, WorldSettings worldSettings, World world, Location eye) {
        GridSettings.BlockGrid scanSettings = config.blockGrid();
        int depthLimit = config.entityGrid().occlusionBlocks();
        RayContext ctx = new RayContext(world, worldSettings, scanSettings, FOCUS_STEP_BUDGET, Long.MAX_VALUE);
        LongIntMap touched = state.focusTouched;
        touched.clear();

        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        int focusDistance = effectiveScanRadius(player, world) * 16;
        for (int[] offset : FOCUS_RAY_OFFSETS) {
            double yaw = Math.toRadians(eye.getYaw()) + Math.toRadians(offset[0]);
            double pitch = Math.toRadians(eye.getPitch()) + Math.toRadians(offset[1]);
            double cosPitch = Math.cos(pitch);
            double dx = -Math.sin(yaw) * cosPitch;
            double dy = -Math.sin(pitch);
            double dz = Math.cos(yaw) * cosPitch;
            traceRay(ctx, ex, ey, ez, dx, dy, dz, focusDistance, touched);
            if (ctx.steps <= 0) {
                break;
            }
        }

        // The player's surroundings must stay real, or nearby mobs get hidden inside fake stone.
        if (state.bubbleMoved(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ())) {
            addBubble(world, worldSettings, ex, ey, ez, touched);
        }
        revealAll(player, state, world, worldSettings, touched, depthLimit);
    }

    // Marks every unearned cell of the map within the depth limit as earned and sends it.
    private void revealAll(Player player, PlayerState state, World world, WorldSettings worldSettings, LongIntMap touched, int depthLimit) {
        if (touched.isEmpty()) {
            return;
        }
        int gridSize = config.blockGrid().size();
        List<CellPos> revealed = new ArrayList<>();
        touched.forEach((key, depth) -> {
            if (depth > depthLimit) {
                // The ray reached this cell through too many solid blocks to be visible.
                return;
            }
            CellPos cell = new CellPos(CellPos.unpackX(key), CellPos.unpackY(key), CellPos.unpackZ(key), gridSize);
            if (!state.isCellVisible(cell)) {
                state.markCellVisible(cell);
                revealed.add(cell);
            }
        });
        sendCellChanges(player, state, world, worldSettings, revealed, true);
    }

    private void refreshVisibleCells(Player player, PlayerState state, WorldSettings worldSettings, World world, Location eye) {
        GridSettings.BlockGrid scanSettings = config.blockGrid();
        // Checked before the scan is claimed, so a player over the shared budget just scans on a later tick.
        long available = terrainBudget.available(scanSettings.maxScanMicrosPerTick() * 1_000L);
        if (available < MIN_SCAN_NANOS) {
            return;
        }
        if (!state.shouldRefreshScan(world.getName(), eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch(), scanSettings, REFINE_OFFSETS.length - 1)) {
            return;
        }
        long started = System.nanoTime();
        try {
            runTerrainScan(player, state, worldSettings, world, eye, scanSettings, Math.min(scanSettings.maxScanMicros() * 1_000L, available));
        } finally {
            terrainBudget.spend(System.nanoTime() - started);
        }
    }

    private void runTerrainScan(Player player, PlayerState state, WorldSettings worldSettings, World world, Location eye, GridSettings.BlockGrid scanSettings, long budgetNanos) {
        int scanRadiusChunks = effectiveScanRadius(player, world);
        int centerChunkX = eye.getBlockX() >> 4;
        int centerChunkZ = eye.getBlockZ() >> 4;

        // Hide and forget anything outside render distance to keep memory bounded.
        Set<CellPos> outside = state.forgetOutside(centerChunkX, centerChunkZ, scanRadiusChunks);
        if (!outside.isEmpty()) {
            sendCellChanges(player, state, world, worldSettings, new ArrayList<>(outside), false);
        }

        int maxDistanceBlocks = scanRadiusChunks * 16;
        RayPlan plan = rayPlan(scanSettings.size(), maxDistanceBlocks);
        long deadline = System.nanoTime() + budgetNanos;
        RayContext ctx = new RayContext(world, worldSettings, scanSettings, scanSettings.maxStepsPerScan(), deadline);
        LongIntMap touched = state.scanTouched;
        touched.clear();

        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();

        // Cast in slices: a scan that runs out stops, and the next one carries on from the next ray.
        int count = plan.count();
        int start = state.rayCursor() % count;
        boolean idle = state.refinePass() > 0;
        // A pass that is already complete is not cast again: the scan only runs on for deferred reveals or for the frontier.
        int toCast = Math.max(0, count - state.rayProgress());
        if (toCast == 0 && !idle) {
            // A moving player whose scan only ran on for deferred reveals: start the next pass.
            state.restartPass();
            toCast = count;
        }
        double frontierDistance = frontierDistance(scanSettings.size(), plan);
        if (idle) {
            ctx.setView(ex, ey, ez, eye.getYaw(), eye.getPitch());
            ctx.seedState = state;
            ctx.seedDistance = frontierDistance;
        }
        double[] offset = REFINE_OFFSETS[Math.min(state.refinePass(), REFINE_OFFSETS.length - 1)];
        int cast = castPattern(ctx, plan, ex, ey, ez, eye.getYaw(), eye.getPitch(), offset, start, toCast, maxDistanceBlocks, touched);
        boolean patternComplete = state.rayProgress() + cast >= count;
        state.advanceRays(cast, count);
        ctx.seedState = null;

        boolean frontierLeft = idle && growFrontier(state, ctx, touched, ex, ey, ez, maxDistanceBlocks, frontierDistance);
        boolean revealBudgetLeft = revealCells(player, state, world, worldSettings, eye, touched);
        state.setPendingDeferredReveals(!patternComplete || revealBudgetLeft || frontierLeft);
    }

    // Cells further away than this are wider than half the gap between two neighbouring rays.
    static double frontierDistance(int gridSize, RayPlan plan) {
        return gridSize / (2.0 * Math.max(plan.yawStep(), plan.pitchStep()));
    }

    // Casts up to toCast rays of the pattern, starting at ray start, rotated onto the look direction.
    int castPattern(RayContext ctx, RayPlan plan, double ex, double ey, double ez, float yawDegrees, float pitchDegrees, double[] offset, int start, int toCast, int maxDistance, LongIntMap touched) {
        double pitch = Math.toRadians(pitchDegrees) + offset[1] * plan.pitchStep();
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double yaw = Math.toRadians(yawDegrees) + offset[0] * plan.yawStep();
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        int count = plan.count();
        int cast = 0;
        while (cast < toCast) {
            if (ctx.exhausted()) {
                break;
            }
            int index = start + cast;
            if (index >= count) {
                index -= count;
            }
            double dx = plan.xs()[index];
            double dy = plan.ys()[index];
            double dz = plan.zs()[index];
            double pitchedY = dy * cosPitch - dz * sinPitch;
            double pitchedZ = dz * cosPitch + dy * sinPitch;
            double rx = dx * cosYaw - pitchedZ * sinYaw;
            double rz = pitchedZ * cosYaw + dx * sinYaw;
            traceRay(ctx, ex, ey, ez, rx, pitchedY, rz, maxDistance, touched);
            cast++;
        }
        return cast;
    }

    // Face and edge neighbours of a cell; edges carry the growth round wall corners.
    private static final int[][] FRONTIER_NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0}, {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}, {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1}};

    // Frontier growth for an idle player: test the neighbours of far reached cells with rays aimed straight at them.
    boolean growFrontier(PlayerState state, RayContext ctx, LongIntMap touched, double ex, double ey, double ez, int maxDistance, double minDistance) {
        int gridSize = ctx.gridSize;
        int depthLimit = config.entityGrid().occlusionBlocks();
        // Smaller cells mean more of them on the same surface: the budget grows with their number, from 8192 rays for 8-block cells up to 32768.
        state.ensureFrontierBudget(Math.max(8_192, Math.min(32_768, 8_192 * 64 / (gridSize * gridSize))));
        while (state.hasFrontier()) {
            if (ctx.exhausted()) {
                return true;
            }
            long key = state.pollFrontier();
            int cellX = CellPos.unpackX(key);
            int cellY = CellPos.unpackY(key);
            int cellZ = CellPos.unpackZ(key);
            for (int[] offset : FRONTIER_NEIGHBOURS) {
                int nx = cellX + offset[0];
                int ny = cellY + offset[1];
                int nz = cellZ + offset[2];
                long neighbour = CellPos.pack(nx, ny, nz);
                if (!state.markFrontierSeen(neighbour)) {
                    continue;
                }
                double half = gridSize / 2.0;
                double cx = nx * gridSize + half - ex;
                double cy = ny * gridSize + half - ey;
                double cz = nz * gridSize + half - ez;
                double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
                double priority = ctx.frontierPriority(nx, ny, nz);
                if (distance < minDistance || distance > maxDistance || Double.isNaN(priority) || !holdsHiddenTerrain(ctx, nx, ny, nz)) {
                    continue;
                }
                if (touched.get(neighbour, Integer.MAX_VALUE) <= depthLimit || state.isCellVisible(new CellPos(nx, ny, nz, gridSize)) || reachCell(state, ctx, touched, ex, ey, ez, nx, ny, nz, depthLimit, neighbour)) {
                    state.enqueueFrontier(neighbour, priority);
                } else if (ctx.exhausted()) {
                    // The budget ran out part-way through this cell's rays: test it again later.
                    state.forgetFrontierSeen(neighbour);
                }
                if (ctx.exhausted()) {
                    // Finish this cell's remaining neighbours on the next scan.
                    state.enqueueFrontier(key, ctx.frontierPriority(cellX, cellY, cellZ));
                    return true;
                }
            }
        }
        return false;
    }

    // Whether a cell lies where something is masked: below the ceiling and below the highest masked block of a loaded chunk it overlaps.
    private static boolean holdsHiddenTerrain(RayContext ctx, int cellX, int cellY, int cellZ) {
        int gridSize = ctx.gridSize;
        int minY = cellY * gridSize;
        if (minY >= ctx.maskCeilingY || minY + gridSize <= ctx.minY) {
            return false;
        }
        int minX = cellX * gridSize;
        int minZ = cellZ * gridSize;
        for (int chunkX = minX >> 4; chunkX <= (minX + gridSize - 1) >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (minZ + gridSize - 1) >> 4; chunkZ++) {
                int slot = ctx.slot(chunkX, chunkZ);
                if (ctx.slotChunk[slot] != null && ctx.slotTop[slot] > minY) {
                    return true;
                }
            }
        }
        return false;
    }

    // Aims at each cell face that looks towards the eye: its nearest point first, then four points across it.
    private boolean reachCell(PlayerState state, RayContext ctx, LongIntMap touched, double ex, double ey, double ez, int cellX, int cellY, int cellZ, int depthLimit, long cellKey) {
        int gridSize = ctx.gridSize;
        double[] min = {cellX * gridSize, cellY * gridSize, cellZ * gridSize};
        double[] eye = {ex, ey, ez};
        double[] point = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double plane;
            if (eye[axis] < min[axis]) {
                plane = min[axis] + 0.01;
            } else if (eye[axis] > min[axis] + gridSize) {
                plane = min[axis] + gridSize - 0.01;
            } else {
                // The eye is level with this pair of faces: neither looks towards it.
                continue;
            }
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            for (int sample = 0; sample < 5; sample++) {
                point[axis] = plane;
                if (sample == 0) {
                    double inset = Math.min(0.5, gridSize / 4.0);
                    point[u] = Math.max(min[u] + inset, Math.min(min[u] + gridSize - inset, eye[u]));
                    point[v] = Math.max(min[v] + inset, Math.min(min[v] + gridSize - inset, eye[v]));
                } else {
                    point[u] = min[u] + gridSize * (((sample - 1) & 1) + 0.5) / 2.0;
                    point[v] = min[v] + gridSize * (((sample - 1) >> 1) + 0.5) / 2.0;
                }
                if (!state.takeFrontierRay()) {
                    return false;
                }
                double dx = point[0] - ex;
                double dy = point[1] - ey;
                double dz = point[2] - ez;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length < 1.0e-6) {
                    continue;
                }
                dx /= length;
                dy /= length;
                dz /= length;
                // Follow the ray through the whole cell: its face may hang in open air, with the solid part deeper.
                double exit = Double.MAX_VALUE;
                double[] direction = {dx, dy, dz};
                for (int a = 0; a < 3; a++) {
                    if (direction[a] > 1.0e-12) {
                        exit = Math.min(exit, (min[a] + gridSize - eye[a]) / direction[a]);
                    } else if (direction[a] < -1.0e-12) {
                        exit = Math.min(exit, (min[a] - eye[a]) / direction[a]);
                    }
                }
                double reach = Math.max(length, exit == Double.MAX_VALUE ? length : exit);
                traceRay(ctx, ex, ey, ez, dx, dy, dz, (int) Math.ceil(reach) + 1, touched);
                if (touched.get(cellKey, Integer.MAX_VALUE) <= depthLimit) {
                    return true;
                }
                if (ctx.exhausted()) {
                    return false;
                }
            }
        }
        return reachExposedFaces(state, ctx, touched, ex, ey, ez, cellX, cellY, cellZ, depthLimit, cellKey);
    }

    // How many exposed block faces of one cell are aimed at, at most.
    private static final int EXPOSED_FACE_SAMPLES = 16;

    // Second try: aim at the cell's exposed block faces, for surfaces that run through the cell (caves, ledges).
    private boolean reachExposedFaces(PlayerState state, RayContext ctx, LongIntMap touched, double ex, double ey, double ez, int cellX, int cellY, int cellZ, int depthLimit, long cellKey) {
        int gridSize = ctx.gridSize;
        int minX = cellX * gridSize;
        int minY = Math.max(ctx.minY, cellY * gridSize);
        int minZ = cellZ * gridSize;
        int maxY = Math.min(ctx.maxY, cellY * gridSize + gridSize);
        ctx.steps -= gridSize * gridSize * gridSize;
        double[] eye = {ex, ey, ez};
        long[] faces = new long[64];
        int found = 0;
        for (int x = minX; x < minX + gridSize; x++) {
            for (int z = minZ; z < minZ + gridSize; z++) {
                int slot = ctx.slot(x >> 4, z >> 4);
                Object chunk = ctx.slotChunk[slot];
                int top = Math.min(maxY, ctx.slotTop[slot]);
                if (chunk == null) {
                    continue;
                }
                for (int y = minY; y < top; y++) {
                    if (!ctx.occludes(chunk, x, y, z)) {
                        continue;
                    }
                    int[] voxel = {x, y, z};
                    for (int axis = 0; axis < 3; axis++) {
                        int side;
                        if (eye[axis] < voxel[axis]) {
                            side = -1;
                        } else if (eye[axis] > voxel[axis] + 1) {
                            side = 1;
                        } else {
                            continue;
                        }
                        int nx = x + (axis == 0 ? side : 0);
                        int ny = y + (axis == 1 ? side : 0);
                        int nz = z + (axis == 2 ? side : 0);
                        if (ny >= ctx.minY && ny < ctx.maxY) {
                            int neighbourSlot = ctx.slot(nx >> 4, nz >> 4);
                            Object neighbourChunk = ctx.slotChunk[neighbourSlot];
                            if (neighbourChunk == null || ctx.occludes(neighbourChunk, nx, ny, nz)) {
                                continue;
                            }
                        }
                        if (found == faces.length) {
                            faces = Arrays.copyOf(faces, found * 2);
                        }
                        // The block's offsets inside the cell (16 bits each), the axis and the side.
                        faces[found++] = ((long) (x - minX) << 40) | ((long) (z - minZ) << 24) | ((long) (y - minY) << 8) | ((long) axis << 1) | (side > 0 ? 1L : 0L);
                    }
                }
            }
        }
        if (found == 0) {
            return false;
        }
        int stride = Math.max(1, found / EXPOSED_FACE_SAMPLES);
        for (int i = stride / 2; i < found; i += stride) {
            if (!state.takeFrontierRay() || ctx.exhausted()) {
                return false;
            }
            long face = faces[i];
            int x = minX + (int) (face >>> 40);
            int z = minZ + (int) ((face >>> 24) & 0xFFFF);
            int y = minY + (int) ((face >>> 8) & 0xFFFF);
            int axis = (int) ((face >>> 1) & 3);
            boolean positive = (face & 1L) != 0;
            // The centre of that block face, a hair inside the block.
            double px = x + 0.5;
            double py = y + 0.5;
            double pz = z + 0.5;
            double inside = positive ? 0.99 : 0.01;
            if (axis == 0) {
                px = x + inside;
            } else if (axis == 1) {
                py = y + inside;
            } else {
                pz = z + inside;
            }
            double dx = px - ex;
            double dy = py - ey;
            double dz = pz - ez;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0e-6) {
                continue;
            }
            traceRay(ctx, ex, ey, ez, dx / length, dy / length, dz / length, (int) Math.ceil(length) + 1, touched);
            if (touched.get(cellKey, Integer.MAX_VALUE) <= depthLimit) {
                return true;
            }
        }
        return false;
    }

    // Reveals cells reached through at most grids.entities.occlusion-blocks; near cells first.
    private boolean revealCells(Player player, PlayerState state, World world, WorldSettings worldSettings, Location eye, LongIntMap touched) {
        GridSettings.BlockGrid scanSettings = config.blockGrid();
        int gridSize = scanSettings.size();
        int cellCost = gridSize * gridSize * gridSize;
        long blockBudget = Math.max(scanSettings.maxRevealedBlocksPerScan(), cellCost);
        int depthLimit = config.entityGrid().occlusionBlocks();

        List<CellPos> candidates = new ArrayList<>(state.takeDeferredCells());
        touched.forEach((key, depth) -> {
            if (depth <= depthLimit) {
                CellPos cell = new CellPos(CellPos.unpackX(key), CellPos.unpackY(key), CellPos.unpackZ(key), gridSize);
                if (!state.isCellVisible(cell)) {
                    candidates.add(cell);
                }
            }
        });
        if (candidates.isEmpty()) {
            return false;
        }
        List<CellPos> ordered = candidates;
        if ((long) candidates.size() * cellCost > blockBudget) {
            // Nearest cells first.
            double ex = eye.getX();
            double ey = eye.getY();
            double ez = eye.getZ();
            int count = candidates.size();
            long[] keys = new long[count];
            for (int i = 0; i < count; i++) {
                float distance = (float) distanceSquared(candidates.get(i), ex, ey, ez, gridSize);
                keys[i] = ((long) Float.floatToIntBits(distance) << 32) | i;
            }
            Arrays.sort(keys);
            ordered = new ArrayList<>(count);
            for (long key : keys) {
                ordered.add(candidates.get((int) key));
            }
        }

        List<CellPos> revealed = new ArrayList<>();
        List<CellPos> leftover = null;
        for (int i = 0; i < ordered.size(); i++) {
            CellPos cell = ordered.get(i);
            if (state.isCellVisible(cell)) {
                continue;
            }
            if (blockBudget - cellCost < 0) {
                // Cap the work per pass; skipped cells are revealed on a later scan.
                leftover = new ArrayList<>(ordered.subList(i, Math.min(ordered.size(), i + MAX_DEFERRED_CELLS)));
                break;
            }
            blockBudget -= cellCost;
            state.markCellVisible(cell);
            revealed.add(cell);
        }
        sendCellChanges(player, state, world, worldSettings, revealed, true);
        if (leftover != null) {
            state.setDeferredCells(leftover);
            return true;
        }
        return false;
    }

    private static double distanceSquared(CellPos cell, double ex, double ey, double ez, int gridSize) {
        double dx = (cell.minBlockX() + gridSize / 2.0) - ex;
        double dy = (cell.minBlockY() + gridSize / 2.0) - ey;
        double dz = (cell.minBlockZ() + gridSize / 2.0) - ez;
        return dx * dx + dy * dy + dz * dz;
    }

    // Server and client view distance (whichever is smaller), plus a one-chunk margin, clamped to ABSOLUTE_MAX_RADIUS_CHUNKS.
    private int effectiveScanRadius(Player player, World world) {
        int serverViewDistance = world.getViewDistance();
        int clientViewDistance;
        try {
            clientViewDistance = player.getClientViewDistance();
        } catch (Throwable throwable) {
            // Not available on every server version; the server's view distance is a fine substitute.
            clientViewDistance = serverViewDistance;
        }
        int radius = Math.min(serverViewDistance, clientViewDistance) + VIEW_DISTANCE_MARGIN_CHUNKS;
        return Math.max(2, Math.min(radius, ABSOLUTE_MAX_RADIUS_CHUNKS));
    }

    // Everything one scan needs: a per-scan chunk cache, direct block reads and the scan's step and time budget.
    final class RayContext {
        final World world;
        final int minY;
        final int maxY;
        final int maskCeilingY;
        final int gridSize;
        final int grace;
        final boolean direct;
        final Object chunkSource;
        final UUID worldId;
        long deadline;
        int steps;
        boolean timeUp;
        // Idle passes only: rays that meet terrain at least seedDistance away in the view cone seed the frontier.
        PlayerState seedState;
        double seedDistance = Double.MAX_VALUE;
        // Eye and unit look direction for the frontier's view cone and ordering.
        double viewX;
        double viewY;
        double viewZ;
        double lookX;
        double lookY;
        double lookZ;

        void setView(double eyeX, double eyeY, double eyeZ, float yawDegrees, float pitchDegrees) {
            viewX = eyeX;
            viewY = eyeY;
            viewZ = eyeZ;
            double yaw = Math.toRadians(yawDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            lookX = -Math.sin(yaw) * Math.cos(pitch);
            lookY = -Math.sin(pitch);
            lookZ = Math.cos(yaw) * Math.cos(pitch);
        }

        // Frontier priority: minus the cosine of the angle from the crosshair, or NaN outside the view cone.
        double frontierPriority(int cellX, int cellY, int cellZ) {
            double half = gridSize / 2.0;
            double dx = cellX * gridSize + half - viewX;
            double dy = cellY * gridSize + half - viewY;
            double dz = cellZ * gridSize + half - viewZ;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0e-6) {
                return -1.0;
            }
            double cos = (dx * lookX + dy * lookY + dz * lookZ) / length;
            return cos < FRONTIER_VIEW_CONE_COS ? Double.NaN : -cos;
        }

        private static final int SLOTS = 128;
        private final long[] slotKey = new long[SLOTS];
        private final boolean[] slotUsed = new boolean[SLOTS];
        private final Object[] slotChunk = new Object[SLOTS];
        private final int[] slotTop = new int[SLOTS];

        RayContext(World world, WorldSettings worldSettings, GridSettings.BlockGrid grid, int stepBudget, long deadline) {
            this.world = world;
            this.minY = world.getMinHeight();
            this.maxY = world.getMaxHeight();
            this.maskCeilingY = worldSettings.maskCeilingY();
            this.gridSize = grid.size();
            this.grace = grid.occlusionGraceBlocks();
            this.chunkSource = NmsBlockReader.chunkSource(world);
            this.direct = chunkSource != null;
            this.worldId = world.getUID();
            this.deadline = deadline;
            this.steps = stepBudget;
        }

        boolean exhausted() {
            if (steps <= 0 || timeUp) {
                return true;
            }
            if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) {
                timeUp = true;
                return true;
            }
            return false;
        }

        // The slot holding this chunk, loading it into the per-scan cache on first use.
        int slot(int chunkX, int chunkZ) {
            long key = chunkKey(chunkX, chunkZ);
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 57);
            if (slotUsed[slot] && slotKey[slot] == key) {
                return slot;
            }
            Object chunk;
            if (testTerrain != null) {
                chunk = Boolean.TRUE;
            } else if (direct) {
                chunk = NmsBlockReader.chunkAt(chunkSource, chunkX, chunkZ);
            } else {
                chunk = world.isChunkLoaded(chunkX, chunkZ) ? world.getChunkAt(chunkX, chunkZ) : null;
            }
            slotUsed[slot] = true;
            slotKey[slot] = key;
            slotChunk[slot] = chunk;
            // Cells above the highest masked block of a chunk hold no hidden terrain, so rays do not record them.
            int bound = peekMaxHard(worldId, chunkX, chunkZ);
            slotTop[slot] = Math.min(maskCeilingY, bound);
            return slot;
        }

        boolean occludes(Object chunk, int x, int y, int z) {
            if (testTerrain != null) {
                return testTerrain.solid(x, y, z);
            }
            if (direct) {
                return NmsBlockReader.isOccluding(chunk, x & 15, y, z & 15);
            }
            return ((Chunk) chunk).getBlock(x & 15, y, z & 15).getType().isOccluding();
        }

        // Self-check only: a made-up world, every chunk loaded, solid where this says so.
        TestTerrain testTerrain;
    }

    @FunctionalInterface
    interface TestTerrain {
        boolean solid(int x, int y, int z);
    }

    // 3D DDA through cells below the flat mask ceiling.
    void traceRay(RayContext ctx, double ex, double ey, double ez, double dx, double dy, double dz, int maxDistanceBlocks, LongIntMap touched) {
        int minY = ctx.minY;
        int maxY = ctx.maxY;
        int maskCeilingY = ctx.maskCeilingY;
        int gridSize = ctx.gridSize;
        int grace = ctx.grace;

        if (ey >= maskCeilingY && dy >= 0.0) {
            // Above the protected range and not descending - cannot reach masked terrain.
            return;
        }

        int x = BlockRay.floor(ex);
        int y = BlockRay.floor(ey);
        int z = BlockRay.floor(ez);

        int stepX = BlockRay.signum(dx);
        int stepY = BlockRay.signum(dy);
        int stepZ = BlockRay.signum(dz);

        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        // Axis-aligned rays must get Double.MAX_VALUE, not NaN from a division by zero.
        double tMaxX = stepX > 0 ? (x + 1 - ex) / dx : (stepX < 0 ? (ex - x) / -dx : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1 - ey) / dy : (stepY < 0 ? (ey - y) / -dy : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1 - ez) / dz : (stepZ < 0 ? (ez - z) / -dz : Double.MAX_VALUE);

        int depth = 0;
        boolean firstVoxel = true;

        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        int slot = -1;
        Object chunk = null;
        int chunkTop = maskCeilingY;
        double t = 0.0;

        while (true) {
            if (y >= minY && y < maxY) {
                int cx = x >> 4;
                int cz = z >> 4;
                if (cx != chunkX || cz != chunkZ) {
                    chunkX = cx;
                    chunkZ = cz;
                    slot = ctx.slot(cx, cz);
                    chunk = ctx.slotChunk[slot];
                    chunkTop = ctx.slotTop[slot];
                    if (chunk == null) {
                        // Outside loaded chunks - the client cannot see beyond them anyway.
                        return;
                    }
                }
                boolean occluding = false;
                if (!firstVoxel) {
                    occluding = ctx.occludes(chunk, x, y, z);
                    if (occluding) {
                        depth++;
                    }
                }
                if (y < chunkTop) {
                    int cellX = Math.floorDiv(x, gridSize);
                    int cellY = Math.floorDiv(y, gridSize);
                    int cellZ = Math.floorDiv(z, gridSize);
                    long cellKey = CellPos.pack(cellX, cellY, cellZ);
                    touched.putMin(cellKey, depth);
                    if (occluding && depth == 1 && ctx.seedState != null && t >= ctx.seedDistance) {
                        // The first surface this ray met, far enough away that neighbouring cells may lie between two pattern rays.
                        double priority = ctx.frontierPriority(cellX, cellY, cellZ);
                        if (!Double.isNaN(priority)) {
                            ctx.seedState.offerFrontierSeed(cellKey, priority);
                        }
                    }
                }
                if (occluding && depth > grace) {
                    // Too many solid blocks: stop.
                    return;
                }
            }
            firstVoxel = false;

            if (--ctx.steps <= 0) {
                return;
            }
            if ((ctx.steps & 1023) == 0 && ctx.deadline != Long.MAX_VALUE && System.nanoTime() > ctx.deadline) {
                ctx.timeUp = true;
                return;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }

            if (t > maxDistanceBlocks) {
                return;
            }
            if (y < minY) {
                // Below the world floor - nothing left to see.
                return;
            }
            if (y >= maskCeilingY && dy >= 0.0) {
                // Rose above the protected range.
                return;
            }
        }
    }

    // Marks the player's immediate surroundings as visible, so the ground under their feet never desyncs.
    private void addBubble(World world, WorldSettings worldSettings, double eyeX, double eyeY, double eyeZ, LongIntMap touched) {
        int minY = world.getMinHeight();
        int maskCeilingY = worldSettings.maskCeilingY();
        int gridSize = config.blockGrid().size();
        int centerX = BlockRay.floor(eyeX);
        int centerY = BlockRay.floor(eyeY);
        int centerZ = BlockRay.floor(eyeZ);
        int lowY = Math.max(minY, centerY - BUBBLE_DOWN_BLOCKS);
        int highY = Math.min(maskCeilingY - 1, centerY + BUBBLE_UP_BLOCKS);
        if (lowY > highY) {
            return;
        }
        // Walk the cells the box overlaps (at most a few), not its blocks.
        for (int cellX = Math.floorDiv(centerX - BUBBLE_HORIZONTAL_BLOCKS, gridSize); cellX <= Math.floorDiv(centerX + BUBBLE_HORIZONTAL_BLOCKS, gridSize); cellX++) {
            for (int cellY = Math.floorDiv(lowY, gridSize); cellY <= Math.floorDiv(highY, gridSize); cellY++) {
                for (int cellZ = Math.floorDiv(centerZ - BUBBLE_HORIZONTAL_BLOCKS, gridSize); cellZ <= Math.floorDiv(centerZ + BUBBLE_HORIZONTAL_BLOCKS, gridSize); cellZ++) {
                    touched.putMin(CellPos.pack(cellX, cellY, cellZ), 0);
                }
            }
        }
    }

    // A growing list of block changes inside one 16x16x16 section.
    private static final class SectionChanges {
        final int sectionX;
        final int sectionY;
        final int sectionZ;
        short[] positions = new short[64];
        Object[] states = new Object[64];
        int count;

        SectionChanges(int sectionX, int sectionY, int sectionZ) {
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
        }

        void add(int x, int y, int z, Object state) {
            if (count == positions.length) {
                positions = Arrays.copyOf(positions, count * 2);
                states = Arrays.copyOf(states, count * 2);
            }
            // The server packs a section-relative position as (x << 8) | (z << 4) | y.
            positions[count] = (short) (((x & 15) << 8) | ((z & 15) << 4) | (y & 15));
            states[count] = state;
            count++;
        }
    }

    // Sends the real blocks of these cells (reveal, cells already earned) or their fake blocks (hide).
    private void sendCellChanges(Player player, PlayerState state, World world, WorldSettings worldSettings, List<CellPos> cells, boolean reveal) {
        if (cells.isEmpty()) {
            return;
        }
        if (!directBlockChangesBroken && NmsBlockReader.available() && state.channel() != null) {
            try {
                if (sendCellChangesDirect(state, world, worldSettings, cells, reveal)) {
                    return;
                }
            } catch (Throwable throwable) {
                directBlockChangesBroken = true;
                if (loggedDirectBlockFailure.compareAndSet(false, true)) {
                    plugin.getLogger().log(Level.WARNING, "Raytrace could not send terrain changes straight from server block states; falling back to the slower Bukkit path.", throwable);
                }
            }
        }
        sendCellChangesBukkit(player, world, worldSettings, cells, reveal);
    }

    // Builds section packets straight from server block states; blocks equal to the fake block are skipped.
    private boolean sendCellChangesDirect(PlayerState state, World world, WorldSettings worldSettings, List<CellPos> cells, boolean reveal) {
        Object source = NmsBlockReader.chunkSource(world);
        if (source == null) {
            return false;
        }
        int minY = world.getMinHeight();
        Map<Long, SectionChanges> sections = new HashMap<>();

        for (CellPos cell : cells) {
            int baseX = cell.minBlockX();
            int baseY = cell.minBlockY();
            int baseZ = cell.minBlockZ();
            int size = cell.size();
            int lastChunkX = Integer.MIN_VALUE;
            int lastChunkZ = Integer.MIN_VALUE;
            Object chunk = null;
            CeilingEntry entry = null;
            Object[] fakeStates = new Object[size];
            for (int x = baseX; x < baseX + size; x++) {
                for (int z = baseZ; z < baseZ + size; z++) {
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                        lastChunkX = chunkX;
                        lastChunkZ = chunkZ;
                        // Never load or generate a chunk here; the chunk mask fills it in when sent.
                        chunk = NmsBlockReader.chunkAt(source, chunkX, chunkZ);
                        entry = chunk == null ? null : pointEntry(world, worldSettings, chunkX, chunkZ);
                    }
                    if (chunk == null) {
                        continue;
                    }
                    int ceiling = hardCeiling(world, worldSettings, entry, x, z);
                    if (ceiling <= minY) {
                        continue;
                    }
                    int fromY = Math.max(baseY, minY);
                    int toY = Math.min(baseY + size, ceiling);
                    for (int y = fromY; y < toY; y++) {
                        Object fake = fakeStates[y - baseY];
                        if (fake == null) {
                            fake = fakeBlockStateFor(worldSettings, y);
                            if (fake == null) {
                                return false;
                            }
                            fakeStates[y - baseY] = fake;
                        }
                        Object newState = fake;
                        if (reveal) {
                            newState = NmsBlockReader.stateAt(chunk, x & 15, y, z & 15);
                            if (newState == null) {
                                return false;
                            }
                            if (newState == fake) {
                                // The client already shows exactly this block.
                                continue;
                            }
                        }
                        long key = CellPos.pack(x >> 4, y >> 4, z >> 4);
                        SectionChanges section = sections.get(key);
                        if (section == null) {
                            section = new SectionChanges(x >> 4, y >> 4, z >> 4);
                            sections.put(key, section);
                        }
                        section.add(x, y, z, newState);
                    }
                }
            }
        }

        List<Object> packets = new ArrayList<>(sections.size());
        for (SectionChanges section : sections.values()) {
            if (section.count == 0) {
                continue;
            }
            Object packet = NativeProtocol.newSectionBlocksUpdate(section.sectionX, section.sectionY, section.sectionZ, section.positions, section.states, section.count);
            if (packet == null) {
                return false;
            }
            packets.add(packet);
        }
        sendDirectBatch(state, packets);
        return true;
    }

    // The Bukkit way: one multi-block change per chunk. Slower, but needs no server internals.
    private void sendCellChangesBukkit(Player player, World world, WorldSettings worldSettings, List<CellPos> cells, boolean reveal) {
        Map<ChunkXZ, Map<Position, BlockData>> batch = new HashMap<>();
        int minY = world.getMinHeight();
        for (CellPos cell : cells) {
            int baseX = cell.minBlockX();
            int baseY = cell.minBlockY();
            int baseZ = cell.minBlockZ();
            int size = cell.size();
            for (int x = baseX; x < baseX + size; x++) {
                int chunkX = x >> 4;
                for (int z = baseZ; z < baseZ + size; z++) {
                    int chunkZ = z >> 4;
                    int ceiling = hardCeiling(world, worldSettings, x, z);
                    if (ceiling <= minY) {
                        continue;
                    }
                    // Never load or generate a chunk here; the chunk mask fills it in when sent.
                    if (reveal && !world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    Map<Position, BlockData> changes = batch.computeIfAbsent(new ChunkXZ(chunkX, chunkZ), ignored -> new HashMap<>());
                    for (int y = baseY; y < baseY + size; y++) {
                        if (y < minY || y >= ceiling) {
                            continue;
                        }
                        Material fake = worldSettings.fakeBlockFor(y);
                        if (reveal) {
                            Block block = world.getBlockAt(x, y, z);
                            if (block.getType() == fake) {
                                continue;
                            }
                            changes.put(Position.block(x, y, z), block.getBlockData());
                        } else {
                            changes.put(Position.block(x, y, z), fakeBlockData(fake));
                        }
                    }
                }
            }
        }
        for (Map<Position, BlockData> changes : batch.values()) {
            if (changes.isEmpty()) {
                continue;
            }
            try {
                player.sendMultiBlockChange(changes);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.FINE, "Failed to send block-change batch to " + player.getName(), exception);
            }
        }
    }

    private BlockData fakeBlockData(Material material) {
        return fakeBlockDataCache.computeIfAbsent(material, Material::createBlockData);
    }

    // The cached canonical ray pattern for a ray count, built facing +Z and rotated at scan time.
    RayPlan rayPlan(int gridSize, int maxRadiusBlocks) {
        RayCounts counts = computeRayCounts(gridSize, maxRadiusBlocks);
        RayPlan cached = rayPlanCache.get(counts);
        if (cached != null) {
            return cached;
        }
        if (rayPlanCache.size() >= 64) {
            // Drop the cache instead of growing without bound; it rebuilds on demand.
            rayPlanCache.clear();
        }
        RayPlan plan = buildRayPlan(counts);
        rayPlanCache.put(counts, plan);
        return plan;
    }

    // Picks ray counts from grid size and view radius, then scales them to the total-ray budget.
    private RayCounts computeRayCounts(int gridSize, int maxRadiusBlocks) {
        double stepRadians = gridSize / (double) Math.max(1, maxRadiusBlocks);
        int front = raysForSpan(FRONT_SPAN_DEGREES, stepRadians);
        int side = raysForSpan(SIDE_SPAN_DEGREES, stepRadians);
        int back = raysForSpan(BACK_SPAN_DEGREES, stepRadians);
        int vertical = raysForSpan(VERTICAL_SPAN_DEGREES, stepRadians);

        long total = (long) (front + side * 2L + back) * vertical;
        int target = config.blockGrid().targetTotalRays();
        if (total > target) {
            double scale = Math.sqrt(target / (double) total);
            front = scaleRayCount(front, scale);
            side = scaleRayCount(side, scale);
            back = scaleRayCount(back, scale);
            vertical = scaleRayCount(vertical, scale);
        }
        // Odd counts, so one ray always points exactly ahead and one exactly behind.
        return new RayCounts(toOdd(front), side, toOdd(back), toOdd(vertical));
    }

    private static int toOdd(int count) {
        return count % 2 == 0 ? count + 1 : count;
    }

    private static int raysForSpan(double spanDegrees, double stepRadians) {
        double spanRadians = Math.toRadians(spanDegrees);
        return Math.max(1, (int) Math.ceil(spanRadians / stepRadians) + 1);
    }

    private static int scaleRayCount(int count, double scale) {
        return Math.max(1, (int) Math.round(count * scale));
    }

    private static RayPlan buildRayPlan(RayCounts counts) {
        int horizontal = counts.front() + counts.side() * 2 + counts.back();
        int total = Math.max(1, horizontal * counts.vertical());
        float[] xs = new float[total];
        float[] ys = new float[total];
        float[] zs = new float[total];
        int index = 0;
        index = addDirections(xs, ys, zs, index, FRONT_MIN_YAW, FRONT_MAX_YAW, counts.front(), counts.vertical());
        index = addDirections(xs, ys, zs, index, SIDE_MIN_YAW, SIDE_MAX_YAW, counts.side(), counts.vertical());
        index = addDirections(xs, ys, zs, index, -SIDE_MAX_YAW, -SIDE_MIN_YAW, counts.side(), counts.vertical());
        index = addDirections(xs, ys, zs, index, BACK_MIN_YAW, BACK_MAX_YAW, counts.back(), counts.vertical());
        double yawStep = Math.toRadians(FRONT_SPAN_DEGREES) / Math.max(1, counts.front() - 1);
        double pitchStep = Math.toRadians(VERTICAL_SPAN_DEGREES) / Math.max(1, counts.vertical() - 1);
        return new RayPlan(total, xs, ys, zs, yawStep, pitchStep);
    }

    private static int addDirections(float[] xs, float[] ys, float[] zs, int index, double minOffset, double maxOffset, int horizontalRays, int verticalRays) {
        if (horizontalRays <= 0 || verticalRays <= 0) {
            return index;
        }
        for (int h = 0; h < horizontalRays; h++) {
            double yawOffset = horizontalRays == 1 ? (minOffset + maxOffset) / 2.0 : minOffset + (maxOffset - minOffset) * h / (horizontalRays - 1);
            double rayYaw = Math.toRadians(yawOffset);
            double sin = Math.sin(rayYaw);
            double cos = Math.cos(rayYaw);
            for (int v = 0; v < verticalRays; v++) {
                double pitch = verticalRays == 1 ? 0.0 : Math.toRadians(VERTICAL_MIN_PITCH + VERTICAL_SPAN_DEGREES * v / (verticalRays - 1));
                double horizontalLength = Math.cos(pitch);
                xs[index] = (float) (-sin * horizontalLength);
                ys[index] = (float) (-Math.sin(pitch));
                zs[index] = (float) (cos * horizontalLength);
                index++;
            }
        }
        return index;
    }

    // Installs the native packet handler on this player's connection. @return true if the handler is in place
    private boolean installNativeHandler(Player player, PlayerState state) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            io.netty.channel.Channel channel = NativeProtocol.channelOf(handle);
            if (channel == null) {
                throw new IllegalStateException("the player has no network channel");
            }
            Object connection = NativeProtocol.connectionOf(handle);
            state.setConnection(connection, channel);
            // The handler carries the player's state itself, so no lookup is needed per packet.
            NativeProtocol.install(channel, new NativeProtocol.OutboundHandler(this::intercept, state));
            return true;
        } catch (Throwable throwable) {
            if (nativeUnavailableLogged.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Raytrace could not install its native packet handler; protection is inactive for this player. (" + throwable + ")");
            }
            return false;
        }
    }

    // Sends a packet that skips this plugin's own handler, so it cannot be filtered again.
    private void sendDirect(PlayerState state, Object packet) {
        try {
            io.netty.channel.Channel channel = state.channel();
            if (channel != null) {
                io.netty.channel.ChannelHandlerContext context = channel.pipeline().context("raytrace");
                if (context != null) {
                    // A write from this context starts at the next handler towards the encoder.
                    context.writeAndFlush(packet);
                    return;
                }
            }
            Object connection = state.connection();
            if (connection != null) {
                NativeProtocol.send(connection, packet);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Could not send a packet directly", throwable);
        }
    }

    // Sends several packets past this plugin's handler with a single flush.
    private void sendDirectBatch(PlayerState state, List<Object> packets) {
        if (packets.size() < 2) {
            for (Object packet : packets) {
                sendDirect(state, packet);
            }
            return;
        }
        int sent = 0;
        try {
            io.netty.channel.Channel channel = state.channel();
            io.netty.channel.ChannelHandlerContext context = channel == null ? null : channel.pipeline().context("raytrace");
            if (context != null) {
                for (; sent < packets.size(); sent++) {
                    context.write(packets.get(sent));
                }
                context.flush();
                return;
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Could not send a packet batch directly", throwable);
        }
        // No handler context, or a failure part-way: send what is left one by one.
        for (int i = sent; i < packets.size(); i++) {
            sendDirect(state, packets.get(i));
        }
    }

    private enum PacketKind {
        CHUNK, BLOCK_UPDATE, SECTION_UPDATE, SPAWN, REMOVE, ENTITY, SOUND_ENTITY, SOUND, BLOCK_ENTITY, BLOCK_EVENT, EFFECT, BUNDLE, WAYPOINT, RESET, OTHER
    }

    // Classifies a packet class once; the per-packet work is then a single class-value lookup.
    private static final ClassValue<PacketKind> PACKET_KINDS = new ClassValue<>() {
        @Override
        protected PacketKind computeValue(Class<?> type) {
            String name = type.getSimpleName();
            return switch (name) {
                case "ClientboundLevelChunkWithLightPacket" -> PacketKind.CHUNK;
                case "ClientboundBlockUpdatePacket", "ClientboundAddTransientBlockPacket" -> PacketKind.BLOCK_UPDATE;
                case "ClientboundSectionBlocksUpdatePacket" -> PacketKind.SECTION_UPDATE;
                case "ClientboundAddEntityPacket", "ClientboundAddLivingEntityPacket", "ClientboundAddPlayerPacket", "ClientboundAddExperienceOrbPacket" -> PacketKind.SPAWN;
                case "ClientboundTrackedWaypointPacket" -> PacketKind.WAYPOINT;
                // The client drops every entity it has when it receives one of these.
                case "ClientboundRespawnPacket", "ClientboundLoginPacket", "ClientboundStartConfigurationPacket" -> PacketKind.RESET;
                case "ClientboundRemoveEntitiesPacket" -> PacketKind.REMOVE;
                // "Pos", "Rot" and "PosRot" are the nested ClientboundMoveEntityPacket subclasses.
                case "ClientboundMoveEntityPacket", "ClientboundTeleportEntityPacket", "ClientboundSetEntityMotionPacket", "ClientboundEntityEventPacket", "ClientboundEntityMetadataPacket", "ClientboundSetEquipmentPacket", "ClientboundSetEntityDataPacket", "ClientboundAnimatePacket", "ClientboundMoveMinecartPacket", "ClientboundEntityPositionSyncPacket", "ClientboundRotateHeadPacket", "ClientboundUpdateAttributesPacket", "ClientboundUpdateMobEffectPacket", "ClientboundRemoveMobEffectPacket", "ClientboundHurtAnimationPacket", "ClientboundDamageEventPacket", "ClientboundSetPassengersPacket", "ClientboundTakeItemEntityPacket", "ClientboundProjectilePowerPacket", "Pos", "Rot", "PosRot" -> PacketKind.ENTITY;
                case "ClientboundSoundEntityPacket" -> PacketKind.SOUND_ENTITY;
                case "ClientboundSoundPacket" -> PacketKind.SOUND;
                case "ClientboundBlockEntityDataPacket" -> PacketKind.BLOCK_ENTITY;
                case "ClientboundBlockEventPacket", "ClientboundBlockDestructionPacket" -> PacketKind.BLOCK_EVENT;
                // Explosions are left out on purpose: they carry the player's knockback, so they always pass.
                case "ClientboundLevelEventPacket", "ClientboundLevelParticlesPacket", "ClientboundParticlesPacket" -> PacketKind.EFFECT;
                case "ClientboundBundlePacket" -> PacketKind.BUNDLE;
                default -> PacketKind.OTHER;
            };
        }
    };

    // The names an entity id can have in the entity packets of the supported versions.
    private static final String[] ENTITY_ID_FIELDS = {"id", "entityId", "entity", "vehicle", "itemId"};
    private static final String[] SPAWN_ID_FIELDS = {"id", "entityId"};

    // @return the packet to forward, a private replacement, or null to drop it
    private Object intercept(Object owner, Object packet) {
        try {
            PacketKind kind = PACKET_KINDS.get(packet.getClass());
            if (kind == PacketKind.OTHER) {
                return packet;
            }
            return interceptKind((PlayerState) owner, packet, kind);
        } catch (Throwable throwable) {
            // Never swallow a packet because of a plugin failure: the protection is important, but breaking the connection is worse.
            return packet;
        }
    }

    private Object interceptKind(PlayerState state, Object packet, PacketKind kind) {
        switch (kind) {
            case BUNDLE -> {
                return rewriteBundle(state, packet);
            }
            case REMOVE -> {
                return filterRemovePacket(state, packet);
            }
            case RESET -> {
                state.forgetClientEntities();
                return packet;
            }
            case SPAWN -> {
                // Drop the spawn for this connection only. What the client does get is recorded even for exempt viewers (locator bar).
                World spawnWorld = state.world();
                WorldSettings spawnSettings = spawnWorld == null ? null : config.worldSettings(spawnWorld);
                boolean drop = spawnSettings != null && !state.bypassed() && spawnSettings.enabled() && handleSpawnPacket(state, spawnWorld, spawnSettings, packet);
                if (!drop) {
                    noteClientSpawn(state, packet);
                }
                return drop ? null : packet;
            }
            default -> {
                // handled below, behind the common checks
            }
        }
        if ((kind == PacketKind.ENTITY || kind == PacketKind.SOUND_ENTITY) && !state.hasHiddenEntities()) {
            return packet;
        }
        if (kind == PacketKind.WAYPOINT && !config.hideLocatorPositions()) {
            return packet;
        }
        World world = state.world();
        if (world == null || state.bypassed()) {
            return packet;
        }
        WorldSettings worldSettings = config.worldSettings(world);
        if (!worldSettings.enabled()) {
            // A disabled world must stay completely untouched, including its effects and sounds.
            return packet;
        }
        switch (kind) {
            case CHUNK -> {
                // Send this player a privately masked copy; never swallow the chunk.
                if (!worldSettings.terrainMaskingEnabled()) {
                    return packet;
                }
                Object masked = maskChunkDataPacket(state, world, worldSettings, packet);
                if (masked != null) {
                    return masked;
                }
                if (loggedFirstChunkMaskFailure.compareAndSet(false, true)) {
                    plugin.getLogger().warning("Raytrace could not mask a chunk packet; that chunk may show its real blocks until it is resent. The protection keeps running for other packets. (Further occurrences are not logged.)");
                }
                return packet;
            }
            case BLOCK_UPDATE -> {
                return maskBlockUpdatePacket(state, world, worldSettings, packet);
            }
            case SECTION_UPDATE -> {
                return maskSectionBlocksUpdatePacket(state, world, worldSettings, packet);
            }
            case ENTITY -> {
                return hideEntityPacket(state, packet) ? null : packet;
            }
            case SOUND_ENTITY -> {
                // A hidden entity's noise stays hidden, but important sounds always play.
                return hideEntityPacket(state, packet) && !isImportantSound(packet) ? null : packet;
            }
            case SOUND -> {
                return hideSoundPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case BLOCK_ENTITY -> {
                // Chests, signs and other block entities are separate packets; hiding the chunk section alone does not stop a late block-entity update.
                return hideBlockEntityPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case BLOCK_EVENT -> {
                return hideBlockEventPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case EFFECT -> {
                return hideEffectPacket(state, world, packet) ? null : packet;
            }
            case WAYPOINT -> {
                return rewriteWaypoint(state, packet);
            }
            default -> {
                return packet;
            }
        }
    }

    // Locator bar (1.21.6+): an entity the client does not have gets only a direction, never its exact position.
    private Object rewriteWaypoint(PlayerState state, Object packet) {
        String operation;
        Object waypoint;
        UUID uuid;
        String type;
        try {
            operation = ((Enum<?>) NativeProtocol.invoke(packet, "operation")).name();
            waypoint = NativeProtocol.invoke(packet, "waypoint");
            Object identifier = NativeProtocol.invoke(waypoint, "id");
            Object left = NativeProtocol.invoke(identifier, "left");
            if (!(left instanceof java.util.Optional<?> optional) || optional.isEmpty() || !(optional.get() instanceof UUID value)) {
                return packet;
            }
            uuid = value;
            type = ((Enum<?>) NativeProtocol.get(waypoint, "type")).name();
        } catch (RuntimeException exception) {
            return packet;
        }
        if ("UNTRACK".equals(operation)) {
            state.forgetConvertedWaypoint(uuid);
            return packet;
        }
        boolean precise = "VEC3I".equals(type) || "CHUNK".equals(type);
        boolean converted = state.isWaypointConverted(uuid);
        // Only an entity the client already has is pinned exactly; anything else gets the direction alone.
        if (!state.isOnClient(uuid)) {
            if (!precise) {
                return packet;
            }
            // An exact position must never go out: on any failure the update is dropped.
            double[] position = waypointPosition(waypoint, type);
            if (position == null || !state.hasEye()) {
                return null;
            }
            Object icon;
            try {
                icon = NativeProtocol.invoke(waypoint, "icon");
            } catch (RuntimeException exception) {
                return null;
            }
            // Same formula as the server's own azimuth waypoints: atan2 of the receiver-minus- source vector rotated 90 degrees clockwise.
            double vx = state.eyeX() - position[0];
            double vz = state.eyeZ() - position[1];
            float azimuth = (float) Math.atan2(vx, -vz);
            Object replacement;
            if ("TRACK".equals(operation)) {
                replacement = NativeProtocol.newAzimuthWaypoint("TRACK", uuid, icon, azimuth);
            } else if (converted) {
                replacement = NativeProtocol.newAzimuthWaypoint("UPDATE", uuid, icon, azimuth);
            } else {
                // An update cannot change a waypoint's type: replace it, as the server does.
                Object remove = NativeProtocol.newWaypointRemove(uuid);
                Object track = NativeProtocol.newAzimuthWaypoint("TRACK", uuid, icon, azimuth);
                replacement = remove == null || track == null ? null : NativeProtocol.newGameBundle(new ArrayList<>(List.of(remove, track)));
            }
            if (replacement == null) {
                return null;
            }
            state.markWaypointConverted(uuid);
            return replacement;
        }
        if (!converted) {
            return packet;
        }
        state.forgetConvertedWaypoint(uuid);
        if ("TRACK".equals(operation) || !precise) {
            return packet;
        }
        // Visible again, and the client still holds the direction-only waypoint: swap the exact one back in.
        Object remove = NativeProtocol.newWaypointRemove(uuid);
        Object track = NativeProtocol.newWaypointPacket("TRACK", waypoint);
        Object rebuilt = remove == null || track == null ? null : NativeProtocol.newGameBundle(new ArrayList<>(List.of(remove, track)));
        return rebuilt == null ? packet : rebuilt;
    }

    // The horizontal position (x, z) a precise waypoint points at, or null.
    private static double[] waypointPosition(Object waypoint, String type) {
        try {
            if ("VEC3I".equals(type)) {
                Object vector = NativeProtocol.get(waypoint, "vector");
                Number x = NativeProtocol.readNumber(vector, "getX", "x");
                Number z = NativeProtocol.readNumber(vector, "getZ", "z");
                return x == null || z == null ? null : new double[]{x.intValue() + 0.5, z.intValue() + 0.5};
            }
            Object chunk = NativeProtocol.get(waypoint, "chunkPos");
            Number x = NativeProtocol.readNumber(chunk, "x", "getX");
            Number z = NativeProtocol.readNumber(chunk, "z", "getZ");
            return x == null || z == null ? null : new double[]{x.intValue() * 16 + 8.0, z.intValue() * 16 + 8.0};
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // Filters the packets inside a bundle, so a hidden spawn never reaches the client.
    private Object rewriteBundle(PlayerState state, Object bundle) {
        List<Object> packets = NativeProtocol.bundleSubPackets(bundle);
        if (packets == null) {
            return bundle;
        }
        List<Object> result = null;
        for (int i = 0; i < packets.size(); i++) {
            Object packet = packets.get(i);
            Object handled = intercept(state, packet);
            if (handled != packet && result == null) {
                result = new ArrayList<>(packets.subList(0, i));
            }
            if (result != null && handled != null) {
                List<Object> nested = handled != packet && PACKET_KINDS.get(handled.getClass()) == PacketKind.BUNDLE ? NativeProtocol.bundleSubPackets(handled) : null;
                if (nested != null) {
                    // A replacement that is itself a bundle (waypoint swap): bundles cannot nest.
                    result.addAll(nested);
                } else {
                    result.add(handled);
                }
            }
        }
        if (result == null) {
            return bundle;
        }
        if (result.isEmpty()) {
            return null;
        }
        Object rebuilt = NativeProtocol.newBundle(bundle, result);
        if (rebuilt == null && loggedFirstBundleFailure.compareAndSet(false, true)) {
            plugin.getLogger().warning("Raytrace could not rebuild a packet bundle; the affected bundle was sent unfiltered. The periodic scan still hides entities, but a spawn may be visible for a moment on this server version.");
        }
        return rebuilt == null ? bundle : rebuilt;
    }

    // Whether the entity this packet is about is hidden from the viewer.
    private boolean hideEntityPacket(PlayerState state, Object packet) {
        if (!state.hasHiddenEntities()) {
            // The overwhelmingly common case: nothing is hidden, so the id is never even read.
            return false;
        }
        int entityId = NativeProtocol.readEntityId(packet, ENTITY_ID_FIELDS);
        return entityId != NativeProtocol.NO_ENTITY_ID && state.isEntityHidden(entityId);
    }

    // Prunes bookkeeping on removes; a remove for an entity the client never got is dropped.
    private Object filterRemovePacket(PlayerState state, Object packet) {
        int[] ids;
        try {
            ids = NativeProtocol.removeEntityIds(packet);
        } catch (RuntimeException exception) {
            return packet;
        }
        for (int id : ids) {
            state.noteClientRemove(id);
        }
        if (!state.hasHiddenEntities() && !state.hasKnownSpawns()) {
            // Nothing this client knows is tracked, so every id can pass through untouched.
            return packet;
        }
        int[] keep = null;
        int kept = 0;
        for (int i = 0; i < ids.length; i++) {
            int entityId = ids[i];
            if (state.isEntityHidden(entityId)) {
                // The server stopped tracking it (despawned, died or left range): forget it entirely so it is never replayed as a ghost.
                state.unhideEntity(entityId);
                if (keep == null) {
                    keep = new int[ids.length];
                    System.arraycopy(ids, 0, keep, 0, i);
                    kept = i;
                }
            } else {
                state.forgetSpawn(entityId);
                if (keep != null) {
                    keep[kept++] = entityId;
                }
            }
        }
        if (keep == null) {
            return packet;
        }
        if (kept == 0) {
            return null;
        }
        Object copy = NativeProtocol.newRemoveEntities(Arrays.copyOf(keep, kept));
        return copy == null ? packet : copy;
    }

    // What an entity type is, and how big; read once per type from a spawn packet.
    private record SpawnType(EntityKind kind, double width, double height) {}

    private final Map<Object, SpawnType> spawnTypes = new ConcurrentHashMap<>();

    // Older servers (before 1.21.5) spawn experience orbs with a packet of their own that has no entity type in it.
    private static final SpawnType LEGACY_ORB = new SpawnType(EntityKind.ITEM, 0.5, 0.5);

    private SpawnType spawnTypeOf(Object packet) {
        Object type;
        try {
            type = NativeProtocol.invoke(packet, "getType");
        } catch (RuntimeException exception) {
            return "ClientboundAddExperienceOrbPacket".equals(packet.getClass().getSimpleName()) ? LEGACY_ORB : null;
        }
        SpawnType cached = spawnTypes.get(type);
        if (cached != null) {
            return cached;
        }
        EntityKind kind = null;
        try {
            Object base = NativeProtocol.invoke(type, "getBaseClass");
            if (base instanceof Class<?> baseClass) {
                kind = EntityKinds.of(baseClass);
            }
        } catch (RuntimeException ignored) {
            // No base class accessor on this version: fall back to the Bukkit type below.
        }
        // Newer versions return the base Entity class for every type, so only a non-OTHER answer is trusted.
        if (kind == null || kind == EntityKind.OTHER) {
            EntityKind byBukkit = kindFromBukkitType(type);
            if (byBukkit != null) {
                kind = byBukkit;
            }
        }
        if (kind == null) {
            kind = EntityKind.OTHER;
        }
        double width = 0.6;
        double height = 1.8;
        try {
            Number w = (Number) NativeProtocol.invoke(type, "getWidth");
            Number h = (Number) NativeProtocol.invoke(type, "getHeight");
            width = w.doubleValue();
            height = h.doubleValue();
        } catch (RuntimeException ignored) {
            // keep the default size
        }
        SpawnType resolved = new SpawnType(kind, width, height);
        spawnTypes.put(type, resolved);
        return resolved;
    }

    // Classifies an NMS entity type through the Bukkit registry and its entity class; null if the registry does not know it.
    private static EntityKind kindFromBukkitType(Object type) {
        String path = registryPath(type);
        if (path == null || path.isEmpty()) {
            return null;
        }
        org.bukkit.entity.EntityType bukkitType = null;
        try {
            bukkitType = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.minecraft(path));
        } catch (RuntimeException ignored) {
            // keep the null: an unknown type stays unclassified below
        }
        if (bukkitType == null && path.equals("lightning_bolt")) {
            // The NMS description id differs from the registry key for this one type.
            try {
                bukkitType = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.minecraft("lightning"));
            } catch (RuntimeException ignored) {
                // keep the null
            }
        }
        if (bukkitType == null) {
            return null;
        }
        Class<?> entityClass;
        try {
            entityClass = bukkitType.getEntityClass();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (entityClass == null) {
            return null;
        }
        return BUKKIT_KINDS.get(entityClass);
    }

    // The hiding category of a Bukkit entity class; matches EntityKinds for server classes.
    private static final ClassValue<EntityKind> BUKKIT_KINDS = new ClassValue<>() {
        @Override
        protected EntityKind computeValue(Class<?> type) {
            if (Player.class.isAssignableFrom(type)) {
                return EntityKind.PLAYER;
            }
            if (Display.class.isAssignableFrom(type) || Hanging.class.isAssignableFrom(type) || org.bukkit.entity.ArmorStand.class.isAssignableFrom(type)) {
                return EntityKind.DECOR;
            }
            if (Vehicle.class.isAssignableFrom(type)) {
                return EntityKind.VEHICLE;
            }
            if (Item.class.isAssignableFrom(type) || org.bukkit.entity.ExperienceOrb.class.isAssignableFrom(type)) {
                return EntityKind.ITEM;
            }
            if (org.bukkit.entity.Projectile.class.isAssignableFrom(type)) {
                return EntityKind.PROJECTILE;
            }
            if (org.bukkit.entity.FallingBlock.class.isAssignableFrom(type) || org.bukkit.entity.TNTPrimed.class.isAssignableFrom(type) || org.bukkit.entity.EnderCrystal.class.isAssignableFrom(type) || org.bukkit.entity.EnderSignal.class.isAssignableFrom(type) || org.bukkit.entity.EvokerFangs.class.isAssignableFrom(type) || org.bukkit.entity.AreaEffectCloud.class.isAssignableFrom(type) || org.bukkit.entity.Interaction.class.isAssignableFrom(type) || org.bukkit.entity.OminousItemSpawner.class.isAssignableFrom(type)) {
                return EntityKind.MISC;
            }
            if (LivingEntity.class.isAssignableFrom(type)) {
                return EntityKind.MOB;
            }
            return EntityKind.OTHER;
        }
    };

    // The registry path of an NMS entity type ("chicken" for entity.minecraft.chicken).
    private static String registryPath(Object type) {
        String description = null;
        try {
            Object value = NativeProtocol.invoke(type, "getDescriptionId");
            if (value instanceof String text) {
                description = text;
            }
        } catch (RuntimeException ignored) {
            // fall back to toString() below
        }
        if (description == null || description.isEmpty()) {
            description = String.valueOf(type);
        }
        String lower = description.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String path = dot >= 0 ? lower.substring(dot + 1) : lower;
        // A description like "EntityType{minecraft:chicken}" is not expected, but strip any trailing decoration so the registry path stays clean.
        int brace = path.indexOf('}');
        if (brace >= 0) {
            path = path.substring(0, brace);
        }
        int colon = path.lastIndexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        return path;
    }

    // Checks a spawn packet before it reaches the client. True means drop it; the spawn is kept for a later reveal.
    private boolean handleSpawnPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.hidesAnyEntity()) {
            return false;
        }
        int entityId = NativeProtocol.readEntityId(packet, SPAWN_ID_FIELDS);
        if (entityId == NativeProtocol.NO_ENTITY_ID) {
            return false;
        }
        if (state.isEntityHidden(entityId)) {
            // Introduced again while we are hiding it: keep it hidden.
            return true;
        }
        if (entityId == state.vehicleId()) {
            // Never withhold what the viewer is riding: the client would dismount and desync.
            return false;
        }
        SpawnType type = spawnTypeOf(packet);
        if (type == null || !worldSettings.hides(type.kind())) {
            return false;
        }
        if (type.kind() == EntityKind.PROJECTILE && state.viewerId() != -1 && spawnData(packet) == state.viewerId()) {
            // A projectile's spawn data is its owner's id: the viewer's own projectiles always stay visible.
            return false;
        }
        // Keep the packet before it can be swallowed: it is the fallback for bringing the entity back if the tracker cannot re-introduce it.
        state.rememberSpawn(entityId, packet);
        if (state.recentlyRevealed(entityId)) {
            return false;
        }

        double x;
        double y;
        double z;
        UUID uuid;
        try {
            x = ((Number) NativeProtocol.invoke(packet, "getX")).doubleValue();
            y = ((Number) NativeProtocol.invoke(packet, "getY")).doubleValue();
            z = ((Number) NativeProtocol.invoke(packet, "getZ")).doubleValue();
        } catch (RuntimeException exception) {
            // Unknown packet shape: leave it to the periodic scan rather than risk hiding a spawn the plugin cannot reason about.
            return false;
        }
        try {
            uuid = (UUID) NativeProtocol.invoke(packet, "getUUID");
        } catch (RuntimeException exception) {
            // The legacy experience-orb packet carries no UUID; the periodic scan finds the orb by its id, and the kept spawn packet brings it back.
            uuid = null;
        }

        // An underground spawn is withheld at any distance; the periodic scan reveals it once it is visible.
        if (isEntityInHiddenTerrain(state, world, worldSettings, x, y, z)) {
            state.hideEntity(entityId, uuid, state.takeSpawn(entityId));
            return true;
        }

        if (!state.hasEye()) {
            return false;
        }
        double ex = state.eyeX();
        double ey = state.eyeY();
        double ez = state.eyeZ();
        double dx = x - ex;
        double dy = y - ey;
        double dz = z - ez;
        double radius = config.entityGrid().scanRadiusBlocks();
        // The same box as the periodic scan; outside it the server's tracker decides.
        if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
            return false;
        }

        boolean hidden;
        try {
            hidden = isEntityOccluded(state, world, worldSettings, ex, ey, ez, x, y, z, type.width(), type.height(), config.entityGrid().occlusionBlocks(), true);
        } catch (RuntimeException exception) {
            if (loggedFirstOcclusionFailure.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Entity occlusion check failed at least once (further occurrences are not logged) - the affected spawn is left visible for now", exception);
            }
            return false;
        }

        if (hidden) {
            state.hideEntity(entityId, uuid, state.takeSpawn(entityId));
            return true;
        }
        return false;
    }

    // Records an entity spawn that is going out to the client.
    private static void noteClientSpawn(PlayerState state, Object packet) {
        int entityId = NativeProtocol.readEntityId(packet, SPAWN_ID_FIELDS);
        if (entityId == NativeProtocol.NO_ENTITY_ID) {
            return;
        }
        try {
            if (NativeProtocol.invoke(packet, "getUUID") instanceof UUID uuid) {
                state.noteClientSpawn(entityId, uuid);
            }
        } catch (RuntimeException exception) {
            // The legacy experience-orb packet has no UUID, and orbs have no waypoint.
        }
    }

    // The int "data" of a spawn packet (the owner id for projectiles), or Integer.MIN_VALUE.
    private static int spawnData(Object packet) {
        try {
            Object value = NativeProtocol.invoke(packet, "getData");
            return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
        } catch (RuntimeException exception) {
            return Integer.MIN_VALUE;
        }
    }

    // Whether an effect packet (particles, level events) must be swallowed.
    private boolean hideEffectPacket(PlayerState state, World world, Object packet) {
        if (!config.hideEffects()) {
            return false;
        }
        double[] origin = packetPosition(packet);
        return origin != null && isEffectHidden(state, world, config.worldSettings(world), origin[0], origin[1], origin[2]);
    }

    // Mutes only far-away sounds that are also behind solid blocks.
    private boolean hideSoundPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!config.hideEffects()) {
            return false;
        }
        double[] origin = packetPosition(packet);
        if (origin == null || !isFarAndOccluded(state, world, origin[0], origin[1], origin[2])) {
            return false;
        }
        // The name lookup is the slow part, so it only runs for sounds that would be muted.
        return !isImportantSound(packet);
    }

    // Critical world sounds stay audible even through terrain.
    private boolean isImportantSound(Object packet) {
        String[] important = config.importantSounds();
        if (important.length == 0) {
            return false;
        }
        String id = soundId(packet);
        if (id == null) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (String part : important) {
            if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }

    // Reads the registry name of the sound carried by a sound packet.
    private static String soundId(Object packet) {
        try {
            Object holder;
            try {
                holder = NativeProtocol.invoke(packet, "getSound");
            } catch (Throwable ignored) {
                holder = NativeProtocol.get(packet, "sound");
            }
            if (holder == null) {
                return null;
            }
            try {
                Object name = NativeProtocol.invoke(holder, "getRegisteredName");
                if (name instanceof String value) {
                    return value;
                }
            } catch (Throwable ignored) {
                // Direct holders have no registry key; fall through to the event location.
            }
            Object event = NativeProtocol.invoke(holder, "value");
            Object location = NativeProtocol.invoke(event, "location");
            return location == null ? null : location.toString();
        } catch (Throwable throwable) {
            return null;
        }
    }

    // Block-entity data (chests, signs, ...) follows the hidden state of its block.
    private boolean hideBlockEntityPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return false;
        }
        int[] block = packetBlockPos(packet);
        return block != null && isHiddenBlock(state, world, worldSettings, block[0], block[1], block[2]);
    }

    // A block event (chest lid, block crack stage, ...) must not animate a block the viewer sees as fake stone.
    private boolean hideBlockEventPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (worldSettings.terrainMaskingEnabled()) {
            int[] block = packetBlockPos(packet);
            if (block != null && isHiddenBlock(state, world, worldSettings, block[0], block[1], block[2])) {
                return true;
            }
        }
        return hideEffectPacket(state, world, packet);
    }

    // The block position a packet carries in a pos/position field, or null.
    private static int[] packetBlockPos(Object packet) {
        try {
            Object position = NativeProtocol.readFirstField(packet, "pos", "position");
            if (position == null) {
                return null;
            }
            Number x = NativeProtocol.readNumber(position, "getX", "x");
            Number y = NativeProtocol.readNumber(position, "getY", "y");
            Number z = NativeProtocol.readNumber(position, "getZ", "z");
            if (x == null || y == null || z == null) {
                return null;
            }
            return new int[]{x.intValue(), y.intValue(), z.intValue()};
        } catch (Throwable throwable) {
            return null;
        }
    }

    // The point a positional packet is about (sounds, particles, level events), or null.
    private static double[] packetPosition(Object packet) {
        try {
            Number x = NativeProtocol.readNumber(packet, "getX", "x");
            Number y = NativeProtocol.readNumber(packet, "getY", "y");
            Number z = NativeProtocol.readNumber(packet, "getZ", "z");
            if (x == null || y == null || z == null) {
                Object position = NativeProtocol.readFirstField(packet, "center", "pos", "position");
                if (position == null) {
                    return null;
                }
                x = NativeProtocol.readNumber(position, "getX", "x");
                y = NativeProtocol.readNumber(position, "getY", "y");
                z = NativeProtocol.readNumber(position, "getZ", "z");
            }
            if (x == null || y == null || z == null) {
                return null;
            }
            return new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()};
        } catch (Throwable throwable) {
            return null;
        }
    }

    // Masks the chunk with per-column ceilings; earned cells stay real. Returns a private copy, or null.
    private Object maskChunkDataPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return null;
        }
        int minY = world.getMinHeight();
        int sectionCount = (world.getMaxHeight() - minY) / 16;
        if (sectionCount <= 0) {
            return null;
        }
        int chunkX;
        int chunkZ;
        Object chunkData;
        try {
            chunkX = (int) NativeProtocol.get(packet, "x");
            chunkZ = (int) NativeProtocol.get(packet, "z");
            chunkData = NativeProtocol.get(packet, "chunkData");
        } catch (Throwable throwable) {
            return null;
        }
        SurfaceHeights.ChunkCeilings ceilings = ceilingsForChunk(world, worldSettings, chunkX, chunkZ, chunkData);
        if (ceilings == null) {
            return null;
        }
        Object factory = NativeProtocol.palettedContainerFactoryRequired() ? palettedContainerFactory() : null;
        if (NativeProtocol.palettedContainerFactoryRequired() && factory == null) {
            return null;
        }
        NativeProtocol.BlockVisibility visible = state.hasVisibleCellsInChunk(chunkX, chunkZ) ? visibilityFor(state) : null;
        return NativeProtocol.maskChunkData(packet, factory, sectionCount, minY, ceilings.values(), ceilings.oreValues(), y -> fakeBlockStateFor(worldSettings, y), chunkX, chunkZ, visible, NmsBlockReader::isSensitiveBlockState);
    }

    // Remembers the last earned cell for the column-ordered chunk mask.
    private NativeProtocol.BlockVisibility visibilityFor(PlayerState state) {
        if (!state.hasVisibleCells()) {
            return null;
        }
        int gridSize = config.blockGrid().size();
        return new NativeProtocol.BlockVisibility() {
            private int lastX = Integer.MIN_VALUE;
            private int lastZ = Integer.MIN_VALUE;
            private int lastCellX;
            private int lastCellZ;
            private int lastCellY = Integer.MIN_VALUE;
            private boolean lastVisible;

            @Override
            public boolean isVisible(int x, int y, int z) {
                if (x != lastX || z != lastZ) {
                    lastX = x;
                    lastZ = z;
                    lastCellX = Math.floorDiv(x, gridSize);
                    lastCellZ = Math.floorDiv(z, gridSize);
                    lastCellY = Integer.MIN_VALUE;
                }
                int cellY = Math.floorDiv(y, gridSize);
                if (cellY != lastCellY) {
                    lastCellY = cellY;
                    lastVisible = state.isCellVisible(new CellPos(lastCellX, cellY, lastCellZ, gridSize));
                }
                return lastVisible;
            }

            @Override
            public boolean anyInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
                // One lookup per overlapped cell instead of one per block.
                for (int cellX = Math.floorDiv(minX, gridSize); cellX <= Math.floorDiv(maxX, gridSize); cellX++) {
                    for (int cellZ = Math.floorDiv(minZ, gridSize); cellZ <= Math.floorDiv(maxZ, gridSize); cellZ++) {
                        for (int cellY = Math.floorDiv(minY, gridSize); cellY <= Math.floorDiv(maxY, gridSize); cellY++) {
                            if (state.isCellVisible(new CellPos(cellX, cellY, cellZ, gridSize))) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
        };
    }

    // @return a startup-visible problem with chunk masking, or null
    public String chunkMaskingProblem() {
        String problem = NativeProtocol.chunkFormatProblem();
        if (problem != null) {
            return problem;
        }
        if (NativeProtocol.palettedContainerFactoryRequired() && palettedContainerFactory() == null) {
            return "the server's chunk container factory could not be created";
        }
        return null;
    }

    // @return a problem rewriting block updates per player, or null
    public String blockUpdateSupportProblem() {
        return NativeProtocol.blockUpdateFormatProblem();
    }

    // @return a problem reading positional effect packets, or null
    public String effectPositionSupportProblem() {
        return NativeProtocol.effectPositionSupportProblem();
    }

    // @return a problem looking inside entity-spawn bundles, or null
    public String bundleSupportProblem() {
        return NativeProtocol.bundleSupportProblem();
    }

    // Returns the per-column ceilings for a chunk packet, cached briefly because digging changes them.
    private SurfaceHeights.ChunkCeilings ceilingsForChunk(World world, WorldSettings worldSettings, int chunkX, int chunkZ, Object chunkData) {
        CeilingKey key = new CeilingKey(world.getUID(), chunkX, chunkZ);
        long now = System.nanoTime();
        CeilingEntry cached = ceilingCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return new SurfaceHeights.ChunkCeilings(cached.ceilings(), cached.oreCeilings(), true);
        }
        SurfaceHeights.ChunkCeilings computed = SurfaceHeights.ceilingsForChunk(world, worldSettings, chunkX, chunkZ, chunkData);
        if (computed == null || computed.values() == null || computed.oreValues() == null) {
            return null;
        }
        // Never cache the flat fallback.
        if (computed.fromTerrain()) {
            if (ceilingCache.size() > MAX_CEILING_ENTRIES_PER_WORLD) {
                ceilingCache.values().removeIf(old -> old.expiresAt() <= now);
                if (ceilingCache.size() > MAX_CEILING_ENTRIES_PER_WORLD) {
                    ceilingCache.clear();
                }
            }
            CeilingEntry entry = newCeilingEntry(computed, now);
            ceilingCache.put(key, entry);
            storePointEntry(world.getUID(), chunkX, chunkZ, entry);
        }
        return computed;
    }

    private static CeilingEntry newCeilingEntry(SurfaceHeights.ChunkCeilings computed, long now) {
        int max = Integer.MIN_VALUE;
        for (int value : computed.oreValues()) {
            if (value > max) {
                max = value;
            }
        }
        return new CeilingEntry(computed.values(), computed.oreValues(), max, now + CEILING_CACHE_NANOS, now + CEILING_BOUND_NANOS);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    void storePointEntry(UUID worldId, int chunkX, int chunkZ, CeilingEntry entry) {
        ConcurrentHashMap<Long, CeilingEntry> perWorld = pointCeilings.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        if (perWorld.size() > MAX_CEILING_ENTRIES_PER_WORLD) {
            long now = System.nanoTime();
            perWorld.values().removeIf(old -> old.boundUntil() <= now);
            if (perWorld.size() > MAX_CEILING_ENTRIES_PER_WORLD) {
                perWorld.clear();
            }
        }
        perWorld.put(chunkKey(chunkX, chunkZ), entry);
    }

    // The highest masked Y in a chunk if it is known (even from a while ago), otherwise MAX_VALUE.
    private int peekMaxHard(UUID worldId, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, CeilingEntry> perWorld = pointCeilings.get(worldId);
        if (perWorld == null) {
            return Integer.MAX_VALUE;
        }
        CeilingEntry entry = perWorld.get(chunkKey(chunkX, chunkZ));
        return entry != null && entry.boundUntil() > System.nanoTime() ? entry.maxHard() : Integer.MAX_VALUE;
    }

    // Per-column ceilings of one chunk for point queries, computed on demand and shared by every viewer.
    private CeilingEntry pointEntry(World world, WorldSettings worldSettings, int chunkX, int chunkZ) {
        if (worldSettings.surfaceBuffer() < 0) {
            return null;
        }
        UUID worldId = world.getUID();
        ConcurrentHashMap<Long, CeilingEntry> perWorld = pointCeilings.get(worldId);
        long now = System.nanoTime();
        if (perWorld != null) {
            CeilingEntry cached = perWorld.get(chunkKey(chunkX, chunkZ));
            if (cached != null && cached.expiresAt() > now) {
                return cached;
            }
        }
        SurfaceHeights.ChunkCeilings computed;
        try {
            computed = SurfaceHeights.ceilingsForChunk(world, worldSettings, chunkX, chunkZ, null);
        } catch (RuntimeException exception) {
            return null;
        }
        if (computed == null || !computed.fromTerrain()) {
            return null;
        }
        CeilingEntry entry = newCeilingEntry(computed, now);
        storePointEntry(worldId, chunkX, chunkZ, entry);
        return entry;
    }

    // The hard cutoff for one column: ores and containers below it need an earned cell.
    private int hardCeiling(World world, WorldSettings worldSettings, CeilingEntry entry, int x, int z) {
        int flat = Math.min(worldSettings.maskCeilingY(), world.getMaxHeight());
        if (entry == null) {
            return flat;
        }
        return Math.min(flat, entry.oreCeilings()[((x & 15) << 4) | (z & 15)]);
    }

    private int hardCeiling(World world, WorldSettings worldSettings, int x, int z) {
        return hardCeiling(world, worldSettings, pointEntry(world, worldSettings, x >> 4, z >> 4), x, z);
    }

    // The masking ceiling for one column, with the soil buffer applied.
    private int effectiveCeiling(World world, WorldSettings worldSettings, int x, int z) {
        int flat = Math.min(worldSettings.maskCeilingY(), world.getMaxHeight());
        CeilingEntry entry = pointEntry(world, worldSettings, x >> 4, z >> 4);
        if (entry == null) {
            return flat;
        }
        return Math.min(flat, entry.ceilings()[((x & 15) << 4) | (z & 15)]);
    }

    // Whether a block is below the hard cutoff and not earned.
    private boolean isHiddenBlock(PlayerState state, World world, WorldSettings worldSettings, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= worldSettings.maskCeilingY()) {
            return false;
        }
        if (state.isCellVisible(CellPos.of(x, y, z, config.blockGrid().size()))) {
            return false;
        }
        return y < hardCeiling(world, worldSettings, x, z);
    }

    // Returns (and caches) the server's own chunk container factory.
    public Object palettedContainerFactory() {
        Object cached = palettedContainerFactory;
        if (cached != null) {
            return cached;
        }
        try {
            Object server = Bukkit.getServer();
            if (server == null) {
                return null;
            }
            Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess");
            Object minecraftServer = NativeProtocol.invoke(server, "getServer");
            Object registryAccess = NativeProtocol.invoke(minecraftServer, "registryAccess");
            Class<?> factoryClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainerFactory");
            Object factory = NativeProtocol.invokeStatic(factoryClass, "create", new Class<?>[]{registryAccessClass}, new Object[]{registryAccess});
            palettedContainerFactory = factory;
            return factory;
        } catch (Throwable throwable) {
            plugin.getLogger().fine("Raytrace could not reach the server's chunk container factory: " + throwable);
            return null;
        }
    }

    // Returns (and caches) the server block state for the fake block at this Y.
    private Object fakeBlockStateFor(WorldSettings worldSettings, int y) {
        Material material = worldSettings.fakeBlockFor(y);
        Object cached = fakeStateCache.get(material);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks");
            Object block = NativeProtocol.staticField(blocks.getName(), material.name());
            if (block == null) {
                return null;
            }
            Object state = block.getClass().getMethod("defaultBlockState").invoke(block);
            if (state != null) {
                fakeStateCache.put(material, state);
            }
            return state;
        } catch (Throwable throwable) {
            return null;
        }
    }


    // Masks one block change into a private packet; never mutates the shared original.
    private Object maskBlockUpdatePacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return packet;
        }
        try {
            Object position = NativeProtocol.get(packet, "pos");
            int x = (int) NativeProtocol.get(position, "x");
            int y = (int) NativeProtocol.get(position, "y");
            int z = (int) NativeProtocol.get(position, "z");
            if (!isHiddenBlock(state, world, worldSettings, x, y, z)) {
                return packet;
            }
            Object fakeState = fakeBlockStateFor(worldSettings, y);
            if (fakeState == null) {
                // Without a fake state, drop the change: the client keeps its fake terrain.
                return null;
            }
            Object copy = NativeProtocol.copyBlockUpdate(packet, fakeState);
            if (copy == null) {
                reportBlockCopyFailure();
            }
            return copy;
        } catch (Throwable throwable) {
            return packet;
        }
    }

    // The same for the batched block change (pistons, fluids, ...).
    private Object maskSectionBlocksUpdatePacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return packet;
        }
        try {
            Object sectionPos = NativeProtocol.get(packet, "sectionPos");
            Object positionsObject = NativeProtocol.get(packet, "positions");
            Object statesObject = NativeProtocol.get(packet, "states");
            if (!(positionsObject instanceof short[] positions) || !(statesObject instanceof Object[] states)) {
                return packet;
            }
            int baseX = (int) NativeProtocol.invoke(sectionPos, "minBlockX");
            int baseY = (int) NativeProtocol.invoke(sectionPos, "minBlockY");
            int baseZ = (int) NativeProtocol.invoke(sectionPos, "minBlockZ");
            if (baseY >= worldSettings.maskCeilingY()) {
                // The whole section is above the masked range.
                return packet;
            }
            Object[] replacements = null;
            for (int i = 0; i < positions.length && i < states.length; i++) {
                // The server packs a section-relative position as (x << 8) | (z << 4) | y.
                short packed = positions[i];
                int x = baseX + ((packed >> 8) & 15);
                int y = baseY + (packed & 15);
                int z = baseZ + ((packed >> 4) & 15);
                if (!isHiddenBlock(state, world, worldSettings, x, y, z)) {
                    continue;
                }
                Object fakeState = fakeBlockStateFor(worldSettings, y);
                if (fakeState == null) {
                    // Never send a half-masked update: drop it, the client keeps its fake terrain.
                    return null;
                }
                if (replacements == null) {
                    replacements = new Object[positions.length];
                }
                // The packet writes each entry from BlockState#getId, so replacing the state object is enough - no short id has to be encoded by hand.
                replacements[i] = fakeState;
            }
            if (replacements == null) {
                return packet;
            }
            Object copy = NativeProtocol.copySectionBlocksUpdate(packet, positions, states, replacements);
            if (copy == null) {
                reportBlockCopyFailure();
            }
            return copy;
        } catch (Throwable throwable) {
            return packet;
        }
    }

    // Reports the first failure to build a per-player copy of a shared block-change packet.
    private void reportBlockCopyFailure() {
        if (loggedFirstBlockCopyFailure.compareAndSet(false, true)) {
            plugin.getLogger().warning("Raytrace could not build a per-player block-change packet; the affected update was dropped to avoid exposing hidden blocks. The protection still runs, but this server version needs a compatibility fix. (Further occurrences are not logged.)");
        }
    }

    // Whether an effect at this point should be hidden: inside masked terrain or behind a wall.
    private boolean isEffectHidden(PlayerState state, World world, WorldSettings worldSettings, double x, double y, double z) {
        if (!state.hasEye()) {
            return false;
        }
        double ex = state.eyeX();
        double ey = state.eyeY();
        double ez = state.eyeZ();
        double dx = ex - x;
        double dy = ey - y;
        double dz = ez - z;
        if (dx * dx + dy * dy + dz * dz < 4.0) {
            // Right next to the viewer: nothing can be in between.
            return false;
        }
        if (isHiddenPoint(state, world, worldSettings, x, y, z)) {
            return true;
        }
        int threshold = config.entityGrid().occlusionBlocks();
        try {
            // Runs on the Netty thread, so only direct server reads, never tick-thread Bukkit access.
            return BlockRay.countOccludingAuto(world, ex, ey, ez, x, y, z, threshold, null, 0, 0, true) >= threshold;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // Whether a sound is both far away and blocked by enough terrain that the viewer should not hear it.
    private boolean isFarAndOccluded(PlayerState state, World world, double x, double y, double z) {
        if (!state.hasEye()) {
            return false;
        }
        double ex = state.eyeX();
        double ey = state.eyeY();
        double ez = state.eyeZ();
        double dx = ex - x;
        double dy = ey - y;
        double dz = ez - z;
        double far = config.farSoundDistance();
        if (dx * dx + dy * dy + dz * dz < far * far) {
            return false;
        }
        int threshold = config.entityGrid().occlusionBlocks();
        try {
            return BlockRay.countOccludingAuto(world, ex, ey, ez, x, y, z, threshold, null, 0, 0, true) >= threshold;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // Whether this point is hidden terrain for the viewer, using the real per-column ceiling rather than the flat configured one.
    private boolean isHiddenPoint(PlayerState state, World world, WorldSettings worldSettings, double x, double y, double z) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return false;
        }
        int blockX = BlockRay.floor(x);
        int blockY = BlockRay.floor(y);
        int blockZ = BlockRay.floor(z);
        if (blockY < world.getMinHeight() || blockY >= worldSettings.maskCeilingY()) {
            return false;
        }
        if (state.isCellVisible(CellPos.of(blockX, blockY, blockZ, config.blockGrid().size()))) {
            return false;
        }
        return blockY < effectiveCeiling(world, worldSettings, blockX, blockZ);
    }

    // Whether an entity stands in terrain this viewer has not revealed.
    private boolean isEntityInHiddenTerrain(PlayerState state, World world, WorldSettings worldSettings, double x, double y, double z) {
        try {
            return isHiddenPoint(state, world, worldSettings, x, y, z);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // Whether a cell holds terrain the viewer has not earned.
    private boolean isMaskingCell(PlayerState state, World world, WorldSettings worldSettings, int cellX, int cellY, int cellZ, int gridSize) {
        int minCellY = cellY * gridSize;
        if (minCellY >= worldSettings.maskCeilingY()) {
            return false;
        }
        if (state.isCellVisible(new CellPos(cellX, cellY, cellZ, gridSize))) {
            return false;
        }
        UUID worldId = world.getUID();
        int minBlockX = cellX * gridSize;
        int minBlockZ = cellZ * gridSize;
        for (int chunkX = minBlockX >> 4; chunkX <= (minBlockX + gridSize - 1) >> 4; chunkX++) {
            for (int chunkZ = minBlockZ >> 4; chunkZ <= (minBlockZ + gridSize - 1) >> 4; chunkZ++) {
                if (peekMaxHard(worldId, chunkX, chunkZ) > minCellY) {
                    // Unknown chunks count as holding terrain: the safe answer.
                    return true;
                }
            }
        }
        return false;
    }

    // Whether every sample point on the entity silhouette is blocked.
    private boolean isEntityOccluded(PlayerState state, World world, WorldSettings worldSettings, double ex, double ey, double ez, double baseX, double baseY, double baseZ, double width, double height, int threshold, boolean packetThread) {
        width = Math.max(0.2, width);
        height = Math.max(0.2, height);
        double centerX = baseX;
        double centerY = baseY + height / 2.0;
        double centerZ = baseZ;

        // One probe for all sample rays of this entity.
        MaskProbe probe = new MaskProbe(state, world, worldSettings, config.blockGrid().size());
        // The centre ray settles the common "plainly visible" case immediately.
        if (!isPointHidden(probe, ex, ey, ez, centerX, centerY, centerZ, threshold, packetThread)) {
            return false;
        }

        double dx = centerX - ex;
        double dz = centerZ - ez;
        double horizontalLength = Math.sqrt(dx * dx + dz * dz);
        double perpX = horizontalLength < 1.0e-4 ? 1.0 : -dz / horizontalLength;
        double perpZ = horizontalLength < 1.0e-4 ? 0.0 : dx / horizontalLength;
        int samples = Math.max(1, Math.min(9, config.entityGrid().size()));

        for (int ix = 0; ix < samples; ix++) {
            double t = samples == 1 ? 0.0 : (ix / (double) (samples - 1)) - 0.5;
            double sampleX = centerX + perpX * width * t;
            double sampleZ = centerZ + perpZ * width * t;
            for (int iy = 0; iy < samples; iy++) {
                double v = samples == 1 ? 0.5 : iy / (double) (samples - 1);
                double sampleY = baseY + height * v;
                if (!isPointHidden(probe, ex, ey, ez, sampleX, sampleY, sampleZ, threshold, packetThread)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Blocked by a real wall or by terrain this viewer still sees as fake stone.
    private boolean isPointHidden(MaskProbe probe, double ex, double ey, double ez, double tx, double ty, double tz, int threshold, boolean packetThread) {
        WorldSettings worldSettings = probe.worldSettings;
        int maskCeilingY = worldSettings.maskCeilingY();
        if (!worldSettings.terrainMaskingEnabled() || maskCeilingY == Integer.MIN_VALUE || Math.min(ey, ty) >= maskCeilingY) {
            return BlockRay.countOccludingAuto(probe.world, ex, ey, ez, tx, ty, tz, threshold, null, 0, ENTITY_CORNER_SKIPS, packetThread) >= threshold;
        }
        probe.masked = false;
        int count = BlockRay.countOccludingAuto(probe.world, ex, ey, ez, tx, ty, tz, threshold, probe, probe.gridSize, ENTITY_CORNER_SKIPS, packetThread);
        return count >= threshold || probe.masked;
    }

    // Stops a sight line at the first cell of terrain the viewer still sees as fake.
    private final class MaskProbe implements BlockRay.CellVisitor {
        final PlayerState state;
        final World world;
        final WorldSettings worldSettings;
        final int gridSize;
        boolean masked;

        MaskProbe(PlayerState state, World world, WorldSettings worldSettings, int gridSize) {
            this.state = state;
            this.world = world;
            this.worldSettings = worldSettings;
            this.gridSize = gridSize;
        }

        @Override
        public boolean visit(int cellX, int cellY, int cellZ) {
            if (isMaskingCell(state, world, worldSettings, cellX, cellY, cellZ, gridSize)) {
                masked = true;
                return false;
            }
            return true;
        }
    }

    // Whether this Bukkit entity could be hidden at all in this world (a cheap pre-filter).
    private static boolean isCandidate(Entity entity, WorldSettings worldSettings) {
        return worldSettings.hides(BUKKIT_KINDS.get(entity.getClass()));
    }

    // Hides entities the viewer cannot see and re-introduces the ones that became visible.
    private void refreshEntityVisibility(Player player, PlayerState state, WorldSettings worldSettings, World world, Location eye) {
        if (!worldSettings.hidesAnyEntity()) {
            if (state.hasHiddenEntities()) {
                // The world stopped hiding entities (config reload): hand them back.
                revealHiddenEntities(player, state);
            }
            return;
        }
        GridSettings.EntityGrid entityGrid = config.entityGrid();
        // A pass over the nearby entities that ran out of time carries on next tick; a new pass starts only when one is due.
        long available = entityBudget.available(entityGrid.maxScanMicrosPerTick() * 1_000L);
        if (available < MIN_SCAN_NANOS) {
            return;
        }
        boolean continuing = state.entityPass() != null;
        if (!continuing && !state.shouldRefreshEntityScan(entityGrid.scanIntervalTicks())) {
            return;
        }
        long started = System.nanoTime();
        try {
            runEntityScan(player, state, worldSettings, world, eye, entityGrid, started + Math.min(entityGrid.maxScanMicros() * 1_000L, available));
        } finally {
            entityBudget.spend(System.nanoTime() - started);
        }
    }

    private void runEntityScan(Player player, PlayerState state, WorldSettings worldSettings, World world, Location eye, GridSettings.EntityGrid entityGrid, long deadline) {
        double radius = entityGrid.scanRadiusBlocks();
        if (state.entityPass() == null) {
            Collection<Entity> nearby;
            try {
                nearby = world.getNearbyEntities(eye, radius, radius, radius, entity -> entity != player && isCandidate(entity, worldSettings));
            } catch (RuntimeException exception) {
                if (loggedFirstNearbyFailure.compareAndSet(false, true)) {
                    plugin.getLogger().log(Level.WARNING, "Failed to list nearby entities for " + player.getName() + " (further occurrences are not logged); entity hiding is paused for that scan", exception);
                }
                return;
            }
            state.startEntityPass(new ArrayList<>(nearby), state.beginEntityScan());
        }
        List<Entity> pass = state.entityPass();
        int scanId = state.entityPassId();
        int index = state.entityPassIndex();
        long tick = state.tickCounter();
        int cellVersion = state.cellVersion();
        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        int threshold = entityGrid.occlusionBlocks();
        // The viewer's vehicle and passengers always stay visible, or the mount would break.
        Entity viewerVehicle = player.getVehicle();
        int vehicleId = viewerVehicle == null ? -1 : viewerVehicle.getEntityId();
        List<Entity> viewerPassengers = player.getPassengers();

        int first = index;
        for (; index < pass.size(); index++) {
            if (index > first && System.nanoTime() > deadline) {
                // Out of time: the rest of this pass waits for the next tick.
                state.setEntityPassIndex(index);
                return;
            }
            Entity entity = pass.get(index);
            EntitySnapshot snapshot = EntitySnapshot.read(entity);
            if (snapshot == null || !snapshot.valid() || !worldSettings.hides(snapshot.kind())) {
                continue;
            }
            int entityId = snapshot.entityId();
            boolean attached = entityId == vehicleId || containsEntityId(viewerPassengers, entityId) || isViewersProjectile(entity, player);
            PlayerState.EntityCheck check = state.entityCheck(entityId);
            boolean shouldHide;
            boolean terrainHidden = false;
            if (!attached && check != null && isVerdictFresh(check, ex, ey, ez, snapshot, cellVersion, tick)) {
                shouldHide = check.hidden;
                terrainHidden = check.hiddenTerrain;
            } else if (attached) {
                shouldHide = false;
                if (check == null) {
                    check = state.newEntityCheck(entityId);
                }
                check.hidden = false;
                check.hiddenTerrain = false;
                // Re-judged from scratch once the viewer dismounts.
                check.cellVersion = cellVersion - 1;
            } else {
                try {
                    terrainHidden = isEntityInHiddenTerrain(state, world, worldSettings, snapshot.x(), snapshot.y(), snapshot.z());
                    boolean occluded = !terrainHidden && isEntityOccluded(state, world, worldSettings, ex, ey, ez, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.width(), snapshot.height(), threshold, false);
                    shouldHide = terrainHidden || occluded;
                } catch (RuntimeException exception) {
                    if (loggedFirstOcclusionFailure.compareAndSet(false, true)) {
                        plugin.getLogger().log(Level.WARNING, "Entity occlusion check failed at least once (further occurrences are not logged) - the affected entity is left visible for now", exception);
                    }
                    shouldHide = false;
                    terrainHidden = false;
                }
                if (check == null) {
                    check = state.newEntityCheck(entityId);
                }
                check.eyeX = ex;
                check.eyeY = ey;
                check.eyeZ = ez;
                check.x = snapshot.x();
                check.y = snapshot.y();
                check.z = snapshot.z();
                check.cellVersion = cellVersion;
                check.tick = tick;
                check.hidden = shouldHide;
                check.hiddenTerrain = terrainHidden;
            }
            check.scanId = scanId;

            boolean hidden = state.isEntityHidden(entityId);
            if (shouldHide) {
                if (!hidden) {
                    if (check.hiddenTerrain) {
                        // Underground / in hidden terrain: the player never legitimately saw this entity, so there is nothing to keep steady - hide at once.
                        hideVisibleEntity(state, snapshot);
                        check.hiddenSinceTick = -1L;
                    } else {
                        // Behind a wall: hide only after it stayed occluded for ENTITY_HIDE_DELAY_TICKS.
                        if (check.hiddenSinceTick < 0L) {
                            check.hiddenSinceTick = tick;
                        }
                        if (tick - check.hiddenSinceTick >= ENTITY_HIDE_DELAY_TICKS) {
                            hideVisibleEntity(state, snapshot);
                            check.hiddenSinceTick = -1L;
                        }
                    }
                }
                // Already hidden and still hidden: nothing to do.
            } else {
                // Visible again: cancel any pending hide.
                check.hiddenSinceTick = -1L;
                if (hidden) {
                    revealEntity(player, state, entityId);
                }
            }
        }

        state.finishEntityPass();

        // Hidden entities this pass did not judge: gone, beyond the scan radius, or spawned after the pass took its list.
        for (PlayerState.HiddenEntity entry : List.copyOf(state.hiddenEntities())) {
            PlayerState.EntityCheck check = state.entityCheck(entry.id);
            if (check != null && check.scanId == scanId) {
                continue;
            }
            if (entry.uuid == null) {
                // A legacy orb has no UUID; the server still tracks it, so replay its kept spawn.
                revealEntity(player, state, entry.id);
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.uuid);
            if (entity == null) {
                state.unhideEntity(entry.id);
                continue;
            }
            Location location = locationIn(entity, world);
            if (location != null && Math.abs(location.getX() - ex) <= radius && Math.abs(location.getY() - ey) <= radius && Math.abs(location.getZ() - ez) <= radius) {
                // Within the scan box but not in this pass's list (it arrived after the list was taken): the next pass judges it.
                continue;
            }
            if (location != null && isEntityInHiddenTerrain(state, world, worldSettings, location.getX(), location.getY(), location.getZ())) {
                // Still alive outside the scan radius but underground in unrevealed terrain: keep it hidden.
                continue;
            }
            // Alive but outside the managed radius: do not withhold it forever.
            revealEntity(player, state, entry.id);
        }
        state.endEntityScan(scanId);
    }

    // The entity's location if it is in this world, otherwise null. Runs on the region thread.
    private static Location locationIn(Entity entity, World world) {
        try {
            World entityWorld = entity.getWorld();
            if (entityWorld == null || !entityWorld.getUID().equals(world.getUID())) {
                return null;
            }
            return entity.getLocation();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // The viewer's own arrow, trident or fishing hook.
    private static boolean isViewersProjectile(Entity entity, Player viewer) {
        return entity instanceof org.bukkit.entity.Projectile projectile && viewer.equals(projectile.getShooter());
    }

    private static boolean containsEntityId(List<Entity> entities, int entityId) {
        for (int i = 0, size = entities.size(); i < size; i++) {
            if (entities.get(i).getEntityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVerdictFresh(PlayerState.EntityCheck check, double ex, double ey, double ez, EntitySnapshot snapshot, int cellVersion, long tick) {
        if (check.cellVersion != cellVersion || tick - check.tick >= ENTITY_VERDICT_MAX_AGE_TICKS) {
            return false;
        }
        double vx = ex - check.eyeX;
        double vy = ey - check.eyeY;
        double vz = ez - check.eyeZ;
        if (vx * vx + vy * vy + vz * vz > ENTITY_VERDICT_MOVE_SQ) {
            return false;
        }
        double mx = snapshot.x() - check.x;
        double my = snapshot.y() - check.y;
        double mz = snapshot.z() - check.z;
        return mx * mx + my * my + mz * mz <= ENTITY_VERDICT_MOVE_SQ;
    }

    // Takes an entity the client currently knows away from it.
    private void hideVisibleEntity(PlayerState state, EntitySnapshot snapshot) {
        int entityId = snapshot.entityId();
        // Mark it hidden before the destroy goes out, so every later packet about it is dropped.
        state.hideEntity(entityId, snapshot.uuid(), state.takeSpawn(entityId));
        Object packet = NativeProtocol.newRemoveEntities(entityId);
        if (packet == null) {
            if (loggedFirstTrackerFailure.compareAndSet(false, true)) {
                plugin.getLogger().warning("Raytrace could not build a remove-entity packet; entities cannot be hidden after they were shown on this server version.");
            }
            return;
        }
        sendDirect(state, packet);
    }

    // Hands a hidden entity back to the client.
    private void revealEntity(Player player, PlayerState state, int entityId) {
        PlayerState.HiddenEntity entry = state.unhideEntity(entityId);
        if (entry == null) {
            return;
        }
        state.markRevealed(entityId);
        introduceEntity(player, state, entry);
    }

    // Makes the client learn about an entity again: through the tracker, or the kept spawn as a fallback.
    private void introduceEntity(Player player, PlayerState state, PlayerState.HiddenEntity entry) {
        if (entry.uuid != null && repairViaTracker(player, entry.uuid)) {
            return;
        }
        replaySpawn(state, entry);
    }

    private void replaySpawn(PlayerState state, PlayerState.HiddenEntity entry) {
        if (entry.spawnPacket != null) {
            sendDirect(state, entry.spawnPacket);
            if (entry.uuid != null) {
                // Sent past this plugin's own handler, so it is recorded here.
                state.noteClientSpawn(entry.id, entry.uuid);
            }
        }
    }

    // Asks the tracker to send the entity afresh by hiding and showing it through the Bukkit API.
    private boolean repairViaTracker(Player viewer, UUID uuid) {
        Entity entity = regionOwnedEntity(uuid);
        if (entity == null) {
            return false;
        }
        boolean hidden = false;
        try {
            viewer.hideEntity(plugin, entity);
            hidden = true;
            viewer.showEntity(plugin, entity);
            return true;
        } catch (Throwable throwable) {
            if (loggedFirstTrackerFailure.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Player#hideEntity/showEntity failed; the cached spawn packet is used to bring entities back instead", throwable);
            }
            if (hidden) {
                try {
                    viewer.showEntity(plugin, entity);
                } catch (Throwable ignored) {
                    // nothing more can be done from here
                }
            }
            return false;
        }
    }

    // The entity, but only when this thread may legally touch it.
    private Entity regionOwnedEntity(UUID uuid) {
        try {
            Entity entity = Bukkit.getEntity(uuid);
            return entity != null && Bukkit.isOwnedByCurrentRegion(entity) ? entity : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private void revealHiddenEntities(Player player, PlayerState state) {
        for (PlayerState.HiddenEntity entry : List.copyOf(state.hiddenEntities())) {
            if (entry.uuid != null && Bukkit.getEntity(entry.uuid) == null) {
                state.unhideEntity(entry.id);
                continue;
            }
            revealEntity(player, state, entry.id);
        }
    }

    private record RayCounts(int front, int side, int back, int vertical) {}

    // A canonical ray pattern facing +Z, rotated onto the player at scan time; yawStep/pitchStep are the ray spacing in radians.
    record RayPlan(int count, float[] xs, float[] ys, float[] zs, double yawStep, double pitchStep) {}

    private record ChunkXZ(int x, int z) {}
}
