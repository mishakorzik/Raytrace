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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Terrain and entity visibility engine. Packet handlers read only concurrent state; Bukkit
 * entity changes stay on the owning player's scheduler.
 */
public final class RaytraceEngine {

    private static final long TICK_PERIOD = 1L;

    /** Hard cap on the scan radius, regardless of view distance. */
    private static final int ABSOLUTE_MAX_RADIUS_CHUNKS = 32;
    /** Extra chunks past the player's view distance, so edge chunks are earned before they appear. */
    private static final int VIEW_DISTANCE_MARGIN_CHUNKS = 1;

    /** Horizontal cones in yaw degrees; front is widest and back narrowest. */
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

    /** One thin corner clip per entity ray, so grazing a corner is not counted as a wall. */
    private static final int ENTITY_CORNER_SKIPS = 1;

    /**
     * Small body-sized safety bubble. It is deliberately much smaller than the old 9x13x9
     * box: a large bubble exposed nearby ores and repeated the same work every tick.
     */
    private static final int BUBBLE_HORIZONTAL_BLOCKS = 2;
    private static final int BUBBLE_DOWN_BLOCKS = 3;
    private static final int BUBBLE_UP_BLOCKS = 1;

    /** Horizontal and vertical spread of the focus rays, in degrees. */
    private static final double FOCUS_HORIZONTAL_OFFSET_DEGREES = 20.0;
    private static final double FOCUS_VERTICAL_OFFSET_DEGREES = 14.0;
    /** Crosshair plus four rays around it, as yaw/pitch offsets in degrees. */
    private static final int[][] FOCUS_RAY_OFFSETS = {
            {0, 0},
            {(int) FOCUS_HORIZONTAL_OFFSET_DEGREES, 0},
            {(int) -FOCUS_HORIZONTAL_OFFSET_DEGREES, 0},
            {0, (int) FOCUS_VERTICAL_OFFSET_DEGREES},
            {0, (int) -FOCUS_VERTICAL_OFFSET_DEGREES}
    };
    /** Reach of the focus rays, in blocks. */
    private static final int FOCUS_MAX_DISTANCE = 64;
    /** Cap on blocks the focus pass may examine per tick. */
    private static final int FOCUS_STEP_BUDGET = 2_000;

    /** How often the (comparatively expensive) permission lookup is repeated. */
    private static final long PERMISSION_INTERVAL_TICKS = 40L;
    /** An unchanged entity verdict is reused for at most this many ticks. */
    private static final long ENTITY_VERDICT_MAX_AGE_TICKS = 40L;
    /** Movement below this (blocks, squared) of both viewer and entity keeps the old verdict. */
    private static final double ENTITY_VERDICT_MOVE_SQ = 0.0625;
    /** How long the per-column ceilings of a chunk are reused for masking decisions (3 seconds). */
    private static final long CEILING_CACHE_NANOS = 3_000_000_000L;
    /** A stale chunk ceiling is still a fine upper bound for skipping air cells (2 minutes). */
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
    private final AtomicBoolean loggedFirstBundleFailure = new AtomicBoolean();
    private final AtomicBoolean loggedDirectBlockFailure = new AtomicBoolean();
    private final AtomicBoolean nativeUnavailableLogged = new AtomicBoolean();

    /** Set once the direct (server-state) block-change path fails; the Bukkit path takes over. */
    private volatile boolean directBlockChangesBroken;

    /** The masking ceiling per column of a chunk, as seen by the chunk packets (short-lived). */
    private final Map<CeilingKey, CeilingEntry> ceilingCache = new ConcurrentHashMap<>();
    /** The same data for point lookups (block updates, effects, entity checks), per world. */
    private final Map<UUID, ConcurrentHashMap<Long, CeilingEntry>> pointCeilings = new ConcurrentHashMap<>();
    /** The server's own block state for each fake block material. */
    private final Map<Material, Object> fakeStateCache = new ConcurrentHashMap<>();
    /** The server's own factory for the block-state and biome containers of a section. */
    private volatile Object palettedContainerFactory;

    private record CeilingKey(UUID world, int chunkX, int chunkZ) {
    }

    /** Per-column ceilings of one chunk. {@code maxHard} bounds every masked Y in the chunk. */
    private record CeilingEntry(int[] ceilings, int[] oreCeilings, int maxHard, long expiresAt, long boundUntil) {
    }

    public RaytraceEngine(Plugin plugin, RaytraceConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Folia: the enable thread is the global region, not the player's, so touching their
            // chunks here may be rejected. Dispatch through the player's own scheduler.
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
        PlayerState state = states.computeIfAbsent(player.getUniqueId(), PlayerState::new);
        Location eye = player.getEyeLocation();
        state.setWorld(eye.getWorld());
        state.setSpectator(player.getGameMode() == GameMode.SPECTATOR);
        state.setPermissionBypass(player.hasPermission("raytrace.bypass"));
        state.updateEye(eye.getX(), eye.getY(), eye.getZ());
        installNativeHandler(player, state);
        if (eye.getWorld() != null) {
            immediateBubbleReveal(player, state, config.worldSettings(eye.getWorld()), eye.getWorld(),
                    eye.getX(), eye.getY(), eye.getZ());
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
            state.markOffline();
            state.clearEntities();
        }
    }

    /** Full reset after a world change or respawn; same-world teleports keep what was earned. */
    public void resetPlayer(Player player) {
        resetPlayer(player, null);
    }

    /**
     * Reset after a teleport, respawn or world change.
     *
     * @param destination where the player is going, if it is already known. The teleport event
     *                    fires before the move, so the player's own location is still the old
     *                    one; revealing around it would leave the client standing inside fake
     *                    terrain at the destination.
     */
    public void resetPlayer(Player player, Location destination) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        World newWorld = destination != null && destination.getWorld() != null
                ? destination.getWorld() : player.getWorld();
        World oldWorld = state.world();
        boolean worldChanged = oldWorld == null || !oldWorld.getUID().equals(newWorld.getUID());
        if (worldChanged) {
            // A different world: nothing earned or hidden carries over, and the client has
            // already dropped every entity it knew.
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

    /**
     * Re-evaluates every online player, e.g. after a config reload. Each reset runs on that
     * player's own Folia region scheduler.
     */
    public void resetAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin, ignored -> resetPlayer(player, null), null);
        }
    }

    /** Drops world-derived caches that depend on configuration. */
    public void clearConfigCaches() {
        ceilingCache.clear();
        pointCeilings.clear();
    }

    public void onChunkUnload(World world, int chunkX, int chunkZ) {
        UUID worldId = world.getUID();
        for (PlayerState state : states.values()) {
            World stateWorld = state.world();
            // Cells belong to the world the player is in; a chunk of another dimension at the
            // same coordinates must not erase them.
            if (stateWorld != null && stateWorld.getUID().equals(worldId)) {
                state.forgetColumn(chunkX, chunkZ);
            }
        }
        pointCeilings.getOrDefault(worldId, EMPTY_CEILINGS).remove(chunkKey(chunkX, chunkZ));
    }

    private static final ConcurrentHashMap<Long, CeilingEntry> EMPTY_CEILINGS = new ConcurrentHashMap<>();

    private void startTask(Player player, PlayerState state) {
        ScheduledTask existing = tasks.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> tick(player, state),
                () -> onQuit(player),
                1L,
                TICK_PERIOD
        );
        tasks.put(player.getUniqueId(), task);
    }

    private void tick(Player player, PlayerState state) {
        if (!player.isOnline()) {
            return;
        }
        state.incrementTick();
        try {
            tickInternal(player, state);
        } catch (Throwable throwable) {
            // Keep the task alive: one bad call must not end protection for this player.
            plugin.getLogger().log(Level.WARNING, "Raytrace tick failed for " + player.getName(), throwable);
        }
    }

    private void tickInternal(Player player, PlayerState state) {
        // One location read per tick; everything below works from it.
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return;
        }
        state.setWorld(world);
        state.updateEye(eye.getX(), eye.getY(), eye.getZ());
        refreshBypass(player, state);

        WorldSettings worldSettings = config.worldSettings(world);
        if (!worldSettings.enabled() || state.bypassed()) {
            if (state.hasHiddenEntities()) {
                revealHiddenEntities(player, state);
            }
            return;
        }

        if (worldSettings.terrainMaskingEnabled()) {
            // Cheap look-direction pass, skipped while the player neither moves nor turns,
            // then the throttled full scan.
            if (state.shouldRunFocus(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch())) {
                revealFocusCells(player, state, worldSettings, world, eye);
            }
            refreshVisibleCells(player, state, worldSettings, world, eye);
        }
        refreshEntityVisibility(player, state, worldSettings, world, eye);
    }

    /** Spectators are exempt; the permission is looked up only every couple of seconds. */
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

    /**
     * Whether this player is exempt from the protection: spectator mode or the
     * {@code raytrace.bypass} permission (not granted to operators by default).
     */
    public boolean isBypassed(Player player) {
        return player.getGameMode() == GameMode.SPECTATOR
                || player.hasPermission("raytrace.bypass");
    }

    /** Cached {@link #isBypassed(Player)}, refreshed every tick (permission every two seconds). */
    public boolean isBypassedCached(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            return isBypassed(player);
        }
        return state.bypassed();
    }

    /** Whether the given block currently shows real data to this viewer. */
    public boolean isBlockVisible(Player viewer, int x, int y, int z) {
        PlayerState state = states.get(viewer.getUniqueId());
        return state != null && state.isCellVisible(CellPos.of(x, y, z, config.blockGrid().size()));
    }

    /** Whether this raw entity id is currently withheld from the viewer. */
    public boolean isEntityIdHidden(Player viewer, int entityId) {
        PlayerState state = states.get(viewer.getUniqueId());
        return state != null && state.isEntityHidden(entityId);
    }

    /** Reveals the cell containing this block right away, e.g. after the player edits it. */
    public void forceRevealBlock(Player player, int x, int y, int z) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null || state.bypassed()) {
            return;
        }
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

    /**
     * Reveals the player's surroundings immediately, used on join, teleport and respawn before
     * the next scheduled scan.
     */
    private void immediateBubbleReveal(Player player, PlayerState state, WorldSettings worldSettings,
                                       World world, double eyeX, double eyeY, double eyeZ) {
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

    /**
     * Cheap pass along the view direction: five rays (crosshair plus four around it) that reveal
     * what the player is looking at immediately, without waiting for the full scan.
     */
    private void revealFocusCells(Player player, PlayerState state, WorldSettings worldSettings,
                                  World world, Location eye) {
        GridSettings.BlockGrid scanSettings = config.blockGrid();
        int depthLimit = config.entityGrid().occlusionBlocks();
        RayContext ctx = new RayContext(world, worldSettings, scanSettings, FOCUS_STEP_BUDGET, Long.MAX_VALUE);
        LongIntMap touched = state.focusTouched;
        touched.clear();

        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        for (int[] offset : FOCUS_RAY_OFFSETS) {
            double yaw = Math.toRadians(eye.getYaw()) + Math.toRadians(offset[0]);
            double pitch = Math.toRadians(eye.getPitch()) + Math.toRadians(offset[1]);
            double cosPitch = Math.cos(pitch);
            double dx = -Math.sin(yaw) * cosPitch;
            double dy = -Math.sin(pitch);
            double dz = Math.cos(yaw) * cosPitch;
            traceRay(ctx, ex, ey, ez, dx, dy, dz, FOCUS_MAX_DISTANCE, touched);
            if (ctx.steps <= 0) {
                break;
            }
        }

        // The player's surroundings must stay real, or nearby mobs get hidden inside fake
        // stone. The bubble only changes when the player enters another block.
        if (state.bubbleMoved(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ())) {
            addBubble(world, worldSettings, ex, ey, ez, touched);
        }
        revealAll(player, state, world, worldSettings, touched, depthLimit);
    }

    /** Marks every unearned cell of the map within the depth limit as earned and sends it. */
    private void revealAll(Player player, PlayerState state, World world, WorldSettings worldSettings,
                           LongIntMap touched, int depthLimit) {
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
        if (!state.shouldRefreshScan(world.getName(), eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch(), scanSettings)) {
            return;
        }

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
        long deadline = System.nanoTime() + scanSettings.maxScanMicros() * 1_000L;
        RayContext ctx = new RayContext(world, worldSettings, scanSettings, scanSettings.maxStepsPerScan(), deadline);
        LongIntMap touched = state.scanTouched;
        touched.clear();

        // Pitch first in the canonical frame, then yaw. Minecraft's pitch is positive downwards,
        // matching the pattern.
        double pitch = Math.toRadians(eye.getPitch());
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double yaw = Math.toRadians(eye.getYaw());
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();

        // The pattern is cast in slices: a scan that runs out of time or steps stops, and the
        // next scan carries on from the next ray instead of restarting at ray zero (which would
        // starve the rays at the end of the pattern forever).
        int count = plan.count();
        int start = state.rayCursor() % count;
        int cast = 0;
        while (cast < count) {
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
            // Rotate the canonical pattern onto the player's actual look direction.
            double pitchedY = dy * cosPitch - dz * sinPitch;
            double pitchedZ = dz * cosPitch + dy * sinPitch;
            double rx = dx * cosYaw - pitchedZ * sinYaw;
            double rz = pitchedZ * cosYaw + dx * sinYaw;
            traceRay(ctx, ex, ey, ez, rx, pitchedY, rz, maxDistanceBlocks, touched);
            cast++;
        }
        boolean patternComplete = state.rayProgress() + cast >= count;
        state.advanceRays(cast, count);

        boolean revealBudgetLeft = revealCells(player, state, world, worldSettings, eye, touched);
        state.setPendingDeferredReveals(!patternComplete || revealBudgetLeft);
    }

    /**
     * Reveals cells reached through at most {@code grids.entities.occlusion-blocks}; near cells
     * first. Cells that do not fit the per-scan budget are remembered for the next scan.
     *
     * @return true if cells are still waiting for budget
     */
    private boolean revealCells(Player player, PlayerState state, World world, WorldSettings worldSettings,
                                Location eye, LongIntMap touched) {
        GridSettings.BlockGrid scanSettings = config.blockGrid();
        int gridSize = scanSettings.size();
        int cellCost = gridSize * gridSize * gridSize;
        long blockBudget = scanSettings.maxRevealedBlocksPerScan();
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
        if ((long) candidates.size() * cellCost > blockBudget) {
            double ex = eye.getX();
            double ey = eye.getY();
            double ez = eye.getZ();
            candidates.sort(Comparator.comparingDouble(cell -> distanceSquared(cell, ex, ey, ez, gridSize)));
        }

        List<CellPos> revealed = new ArrayList<>();
        List<CellPos> leftover = null;
        for (int i = 0; i < candidates.size(); i++) {
            CellPos cell = candidates.get(i);
            if (state.isCellVisible(cell)) {
                continue;
            }
            if (blockBudget - cellCost < 0) {
                // Cap the work per pass; skipped cells are revealed on a later scan.
                leftover = new ArrayList<>(candidates.subList(i, Math.min(candidates.size(), i + MAX_DEFERRED_CELLS)));
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

    /**
     * Server and client view distance (whichever is smaller), plus a one-chunk margin, clamped to
     * {@link #ABSOLUTE_MAX_RADIUS_CHUNKS}.
     */
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

    /**
     * Everything one scan needs to walk rays quickly: the loaded chunks are looked up once and
     * remembered, block reads go straight to the server's chunk data, and the scan carries its
     * own step and time budget.
     */
    private final class RayContext {
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

        private static final int SLOTS = 128;
        private final long[] slotKey = new long[SLOTS];
        private final boolean[] slotUsed = new boolean[SLOTS];
        private final Object[] slotChunk = new Object[SLOTS];
        private final int[] slotTop = new int[SLOTS];
        private int lastSlot = -1;

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

        /** The slot holding this chunk, loading it into the per-scan cache on first use. */
        int slot(int chunkX, int chunkZ) {
            long key = chunkKey(chunkX, chunkZ);
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 57);
            if (slotUsed[slot] && slotKey[slot] == key) {
                return slot;
            }
            Object chunk;
            if (direct) {
                chunk = NmsBlockReader.chunkAt(chunkSource, chunkX, chunkZ);
            } else {
                chunk = world.isChunkLoaded(chunkX, chunkZ) ? world.getChunkAt(chunkX, chunkZ) : null;
            }
            slotUsed[slot] = true;
            slotKey[slot] = key;
            slotChunk[slot] = chunk;
            // Cells above the highest masked block of a chunk hold no hidden terrain, so rays
            // do not record them. Only an existing (possibly old) answer is used: computing one
            // here would cost more than the walk it speeds up.
            int bound = peekMaxHard(worldId, chunkX, chunkZ);
            slotTop[slot] = Math.min(maskCeilingY, bound);
            return slot;
        }

        boolean occludes(Object chunk, int x, int y, int z) {
            if (direct) {
                return NmsBlockReader.isOccluding(chunk, x & 15, y, z & 15);
            }
            return ((Chunk) chunk).getBlock(x & 15, y, z & 15).getType().isOccluding();
        }
    }

    /**
     * 3D DDA through cells below the flat mask ceiling. The block grace does not reset in air,
     * but open air alone never stops the ray.
     */
    private void traceRay(RayContext ctx, double ex, double ey, double ez, double dx, double dy, double dz,
                          int maxDistanceBlocks, LongIntMap touched) {
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
                    touched.putMin(CellPos.pack(Math.floorDiv(x, gridSize), Math.floorDiv(y, gridSize),
                            Math.floorDiv(z, gridSize)), depth);
                }
                if (occluding && depth > grace) {
                    // Too many solid blocks: stop. Open air does not stop the ray, so a clear
                    // shaft is still followed down to the bottom.
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

    /**
     * Marks the player's immediate surroundings as visible, biased downward so missing ground
     * under their feet cannot cause client-side collision desync. Cells are added with depth 0.
     * The box covers the body plus a one-block margin; it is not a reveal-through-terrain
     * allowance. A larger box would expose shallow ores even when the grid is small.
     */
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
        for (int cellX = Math.floorDiv(centerX - BUBBLE_HORIZONTAL_BLOCKS, gridSize);
             cellX <= Math.floorDiv(centerX + BUBBLE_HORIZONTAL_BLOCKS, gridSize); cellX++) {
            for (int cellY = Math.floorDiv(lowY, gridSize); cellY <= Math.floorDiv(highY, gridSize); cellY++) {
                for (int cellZ = Math.floorDiv(centerZ - BUBBLE_HORIZONTAL_BLOCKS, gridSize);
                     cellZ <= Math.floorDiv(centerZ + BUBBLE_HORIZONTAL_BLOCKS, gridSize); cellZ++) {
                    touched.putMin(CellPos.pack(cellX, cellY, cellZ), 0);
                }
            }
        }
    }

    /** A growing list of block changes inside one 16x16x16 section. */
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

    /**
     * Sends the real blocks of these cells (reveal) or their fake blocks (hide). The cells of a
     * reveal must already be marked as earned, so the packet is not masked again.
     */
    private void sendCellChanges(Player player, PlayerState state, World world, WorldSettings worldSettings,
                                 List<CellPos> cells, boolean reveal) {
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
                    plugin.getLogger().log(Level.WARNING, "Raytrace could not send terrain changes straight from "
                            + "server block states; falling back to the slower Bukkit path.", throwable);
                }
            }
        }
        sendCellChangesBukkit(player, world, worldSettings, cells, reveal);
    }

    /**
     * Builds section packets straight from the server's block states: no Bukkit object per block,
     * and blocks that already equal what the client currently shows are not sent at all.
     *
     * @return false if this server cannot do it (the caller falls back)
     */
    private boolean sendCellChangesDirect(PlayerState state, World world, WorldSettings worldSettings,
                                          List<CellPos> cells, boolean reveal) {
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
                        Object fake = fakeBlockState(worldSettings, y);
                        if (fake == null) {
                            return false;
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
            Object packet = NativeProtocol.newSectionBlocksUpdate(section.sectionX, section.sectionY,
                    section.sectionZ, section.positions, section.states, section.count);
            if (packet == null) {
                return false;
            }
            packets.add(packet);
        }
        for (Object packet : packets) {
            sendDirect(state, packet);
        }
        return true;
    }

    /** The Bukkit way: one multi-block change per chunk. Slower, but needs no server internals. */
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
                    Map<Position, BlockData> changes =
                            batch.computeIfAbsent(new ChunkXZ(chunkX, chunkZ), ignored -> new HashMap<>());
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

    /**
     * The cached canonical ray pattern for a ray count, built facing +Z and rotated at scan time.
     */
    private RayPlan rayPlan(int gridSize, int maxRadiusBlocks) {
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

    /** Picks ray counts from grid size and view radius, then scales them to the total-ray budget. */
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
        return new RayCounts(front, side, back, vertical);
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
        return new RayPlan(total, xs, ys, zs);
    }

    private static int addDirections(float[] xs, float[] ys, float[] zs, int index, double minOffset, double maxOffset,
                                     int horizontalRays, int verticalRays) {
        if (horizontalRays <= 0 || verticalRays <= 0) {
            return index;
        }
        for (int h = 0; h < horizontalRays; h++) {
            double yawOffset = horizontalRays == 1 ? (minOffset + maxOffset) / 2.0
                    : minOffset + (maxOffset - minOffset) * h / (horizontalRays - 1);
            double rayYaw = Math.toRadians(yawOffset);
            double sin = Math.sin(rayYaw);
            double cos = Math.cos(rayYaw);
            for (int v = 0; v < verticalRays; v++) {
                double pitch = verticalRays == 1 ? 0.0
                        : Math.toRadians(VERTICAL_MIN_PITCH + VERTICAL_SPAN_DEGREES * v / (verticalRays - 1));
                double horizontalLength = Math.cos(pitch);
                xs[index] = (float) (-sin * horizontalLength);
                ys[index] = (float) (-Math.sin(pitch));
                zs[index] = (float) (cos * horizontalLength);
                index++;
            }
        }
        return index;
    }

    /** Installs the native packet handler on this player's connection. */
    private void installNativeHandler(Player player, PlayerState state) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            io.netty.channel.Channel channel = NativeProtocol.channelOf(handle);
            Object connection = NativeProtocol.connectionOf(handle);
            state.setConnection(connection, channel);
            // The handler carries the player's state itself, so no lookup is needed per packet.
            NativeProtocol.install(channel, new NativeProtocol.OutboundHandler(this::intercept, state));
        } catch (Throwable throwable) {
            if (nativeUnavailableLogged.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Raytrace could not install its native packet handler; "
                        + "protection is inactive for this player. (" + throwable + ")");
            }
        }
    }

    /** Sends a packet that skips this plugin's own handler, so it cannot be filtered again. */
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

    private enum PacketKind {
        CHUNK, BLOCK_UPDATE, SECTION_UPDATE, SPAWN, REMOVE, ENTITY, SOUND_ENTITY, SOUND,
        BLOCK_ENTITY, BLOCK_EVENT, EFFECT, BUNDLE, OTHER
    }

    /** Classifies a packet class once; the per-packet work is then a single class-value lookup. */
    private static final ClassValue<PacketKind> PACKET_KINDS = new ClassValue<>() {
        @Override
        protected PacketKind computeValue(Class<?> type) {
            String name = type.getSimpleName();
            return switch (name) {
                case "ClientboundLevelChunkWithLightPacket" -> PacketKind.CHUNK;
                case "ClientboundBlockUpdatePacket", "ClientboundAddTransientBlockPacket" -> PacketKind.BLOCK_UPDATE;
                case "ClientboundSectionBlocksUpdatePacket" -> PacketKind.SECTION_UPDATE;
                case "ClientboundAddEntityPacket", "ClientboundAddLivingEntityPacket",
                     "ClientboundAddPlayerPacket" -> PacketKind.SPAWN;
                case "ClientboundRemoveEntitiesPacket" -> PacketKind.REMOVE;
                case "ClientboundMoveEntityPacket", "ClientboundTeleportEntityPacket",
                     "ClientboundSetEntityMotionPacket", "ClientboundEntityEventPacket",
                     "ClientboundEntityMetadataPacket", "ClientboundSetEquipmentPacket",
                     "ClientboundSetEntityDataPacket", "ClientboundAnimatePacket",
                     "ClientboundMoveMinecartPacket", "ClientboundEntityPositionSyncPacket",
                     "ClientboundRotateHeadPacket", "ClientboundUpdateAttributesPacket",
                     "ClientboundUpdateMobEffectPacket", "ClientboundRemoveMobEffectPacket",
                     "ClientboundHurtAnimationPacket", "ClientboundDamageEventPacket",
                     "ClientboundSetPassengersPacket",
                     // Simple names of the nested ClientboundMoveEntityPacket subclasses.
                     "Pos", "Rot", "PosRot" -> PacketKind.ENTITY;
                case "ClientboundSoundEntityPacket" -> PacketKind.SOUND_ENTITY;
                case "ClientboundSoundPacket" -> PacketKind.SOUND;
                case "ClientboundBlockEntityDataPacket" -> PacketKind.BLOCK_ENTITY;
                case "ClientboundBlockEventPacket", "ClientboundBlockDestructionPacket" -> PacketKind.BLOCK_EVENT;
                // Explosions carry both a sound and the local player's knockback. Dropping the
                // packet loses the explosion sound and can desync knockback, so it is always
                // forwarded; the short particle burst is an acceptable trade.
                case "ClientboundLevelEventPacket", "ClientboundLevelParticlesPacket",
                     "ClientboundParticlesPacket" -> PacketKind.EFFECT;
                case "ClientboundBundlePacket" -> PacketKind.BUNDLE;
                default -> PacketKind.OTHER;
            };
        }
    };

    /** The names an entity id can have in the entity packets of the supported versions. */
    private static final String[] ENTITY_ID_FIELDS = {"id", "entityId", "entity", "vehicle"};
    private static final String[] SPAWN_ID_FIELDS = {"id", "entityId"};

    /** @return the packet to forward, a private replacement, or null to drop it */
    private Object intercept(Object owner, Object packet) {
        try {
            PacketKind kind = PACKET_KINDS.get(packet.getClass());
            if (kind == PacketKind.OTHER) {
                return packet;
            }
            return interceptKind((PlayerState) owner, packet, kind);
        } catch (Throwable throwable) {
            // Never swallow a packet because of a plugin failure: the protection is important,
            // but breaking the connection is worse.
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
            default -> {
                // handled below, behind the common checks
            }
        }
        World world = state.world();
        if (world == null || state.bypassed()) {
            return packet;
        }
        WorldSettings worldSettings = config.worldSettings(world);
        switch (kind) {
            case CHUNK -> {
                // Change the per-player chunk packet in place; never swallow it.
                maskChunkDataPacket(state, world, worldSettings, packet);
                return packet;
            }
            case BLOCK_UPDATE -> {
                return maskBlockUpdatePacket(state, world, worldSettings, packet);
            }
            case SECTION_UPDATE -> {
                return maskSectionBlocksUpdatePacket(state, world, worldSettings, packet);
            }
            case SPAWN -> {
                // Drop the spawn for this connection only; never mutate the shared packet.
                return handleSpawnPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case ENTITY -> {
                return hideEntityPacket(state, packet) ? null : packet;
            }
            case SOUND_ENTITY -> {
                // A hidden entity's ambient noise may stay hidden, but a boss death or an
                // explosion must never disappear just because the source is occluded.
                return hideEntityPacket(state, packet) && !isImportantSound(packet) ? null : packet;
            }
            case SOUND -> {
                return hideSoundPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case BLOCK_ENTITY -> {
                // Chests, signs and other block entities are separate packets; hiding the
                // chunk section alone does not stop a late block-entity update.
                return hideBlockEntityPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case BLOCK_EVENT -> {
                return hideBlockEventPacket(state, world, worldSettings, packet) ? null : packet;
            }
            case EFFECT -> {
                return hideEffectPacket(state, world, packet) ? null : packet;
            }
            default -> {
                return packet;
            }
        }
    }

    /**
     * Looks inside a bundle. The server introduces an entity to a client as one bundle of
     * spawn, data, equipment and attributes; only by filtering the packets inside can an
     * underground mob be kept from the client completely, instead of being spawned and then
     * removed a few ticks later.
     */
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
                result.add(handled);
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

    /** Whether the entity this packet is about is hidden from the viewer. */
    private boolean hideEntityPacket(PlayerState state, Object packet) {
        if (!state.hasHiddenEntities()) {
            // The overwhelmingly common case: nothing is hidden, so the id is never even read.
            return false;
        }
        Integer entityId = NativeProtocol.readEntityId(packet, ENTITY_ID_FIELDS);
        return entityId != null && state.isEntityHidden(entityId);
    }

    /**
     * Prunes per-viewer bookkeeping when the server removes entities. A remove for an entity
     * this client never received (or that was already taken away) is dropped: the client has
     * nothing to remove, and sending it is exactly the "spawned, then immediately removed"
     * traffic this plugin is meant to avoid.
     */
    private Object filterRemovePacket(PlayerState state, Object packet) {
        int[] ids;
        try {
            ids = NativeProtocol.removeEntityIds(packet);
        } catch (RuntimeException exception) {
            return packet;
        }
        int[] keep = null;
        int kept = 0;
        for (int i = 0; i < ids.length; i++) {
            int entityId = ids[i];
            if (state.isEntityHidden(entityId)) {
                // The server stopped tracking it (despawned, died or left range): forget it
                // entirely so it is never replayed as a ghost.
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

    /** What an entity type is, and how big; read once per type from a spawn packet. */
    private record SpawnType(EntityKind kind, double width, double height) {
    }

    private static final SpawnType UNKNOWN_SPAWN_TYPE = new SpawnType(EntityKind.OTHER, 0.6, 1.8);
    private final Map<Object, SpawnType> spawnTypes = new ConcurrentHashMap<>();

    private SpawnType spawnTypeOf(Object packet) {
        Object type;
        try {
            type = NativeProtocol.invoke(packet, "getType");
        } catch (RuntimeException exception) {
            return null;
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
            // No base class accessor on this version: fall back to the type's name.
        }
        if (kind == null) {
            // Only when the base class is unavailable: a base class that says "other" (arrows,
            // experience orbs, TNT...) is a real answer and must not be second-guessed by name.
            kind = kindFromTypeName(String.valueOf(type));
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
        SpawnType resolved = kind == null ? UNKNOWN_SPAWN_TYPE : new SpawnType(kind, width, height);
        spawnTypes.put(type, resolved);
        return resolved;
    }

    /**
     * Fallback classification from the entity type's description id, e.g.
     * {@code entity.minecraft.chest_minecart}. Only the last segment is compared and only as a
     * whole word: the namespace itself contains "raft" ("minecraft").
     */
    private static EntityKind kindFromTypeName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String path = lower.substring(lower.lastIndexOf('.') + 1);
        if (path.equals("minecart") || path.endsWith("_minecart") || path.equals("boat")
                || path.endsWith("_boat") || path.equals("raft") || path.endsWith("_raft")) {
            return EntityKind.VEHICLE;
        }
        if (path.equals("item_frame") || path.equals("glow_item_frame") || path.equals("painting")
                || path.equals("armor_stand") || path.equals("leash_knot") || path.endsWith("_display")) {
            return EntityKind.DECOR;
        }
        if (path.equals("item")) {
            return EntityKind.ITEM;
        }
        if (path.equals("player")) {
            return EntityKind.PLAYER;
        }
        return null;
    }

    /**
     * Checks a spawn packet before it reaches the client. A withheld spawn is remembered so the
     * entity can be introduced properly the moment it becomes visible.
     *
     * @return true if the spawn packet must not reach the client
     */
    private boolean handleSpawnPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.hidesAnyEntity()) {
            return false;
        }
        Integer entityId = NativeProtocol.readEntityId(packet, SPAWN_ID_FIELDS);
        if (entityId == null) {
            return false;
        }
        if (state.isEntityHidden(entityId)) {
            // Introduced again while we are hiding it: keep it hidden.
            return true;
        }
        SpawnType type = spawnTypeOf(packet);
        if (type == null || !worldSettings.hides(type.kind())) {
            return false;
        }
        // Keep the packet before it can be swallowed: it is the fallback for bringing the
        // entity back if the tracker cannot re-introduce it.
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
            uuid = (UUID) NativeProtocol.invoke(packet, "getUUID");
        } catch (RuntimeException exception) {
            // Unknown packet shape: leave it to the periodic scan rather than risk hiding a
            // spawn the plugin cannot reason about.
            return false;
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
        if (dx * dx + dy * dy + dz * dz > radius * radius) {
            // The periodic scan cannot manage an entity outside its radius, so suppressing the
            // spawn here would leave it invisible forever. Let the server's tracker do its job.
            return false;
        }

        boolean hidden;
        try {
            hidden = isEntityInHiddenTerrain(state, world, worldSettings, x, y, z)
                    || isEntityOccluded(state, world, worldSettings, ex, ey, ez, x, y, z,
                    type.width(), type.height(), config.entityGrid().occlusionBlocks(), true);
        } catch (RuntimeException exception) {
            if (loggedFirstOcclusionFailure.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Entity occlusion check failed at least once (further "
                        + "occurrences are not logged) - the affected spawn is left "
                        + "visible for now", exception);
            }
            return false;
        }

        if (hidden) {
            state.hideEntity(entityId, uuid, state.takeSpawn(entityId), false);
            return true;
        }
        return false;
    }

    /** Whether an effect packet (particles, level events) must be swallowed. */
    private boolean hideEffectPacket(PlayerState state, World world, Object packet) {
        if (!config.hideEffects()) {
            return false;
        }
        double[] origin = packetPosition(packet);
        return origin != null && isEffectHidden(state, world, config.worldSettings(world),
                origin[0], origin[1], origin[2]);
    }

    /** Mutes only far-away sounds that are also behind solid blocks. */
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

    /**
     * Critical world sounds stay audible even through terrain. They are rare, and losing a boss
     * death, an explosion or a portal event is far worse than the small amount of information
     * the sound reveals.
     */
    private static boolean isImportantSound(Object packet) {
        String id = soundId(packet);
        if (id == null) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("explode") || lower.contains("explosion")
                || lower.contains("ender_dragon") || lower.contains("end_crystal")
                || lower.contains("wither") || lower.contains("end_portal")
                || lower.contains("beacon") || lower.contains("raid") || lower.contains("bell");
    }

    /** Reads the registry name of the sound carried by a sound packet. */
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

    /** Block-entity data (chests, signs, ...) follows the hidden state of its block. */
    private boolean hideBlockEntityPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return false;
        }
        int[] block = packetBlockPos(packet);
        return block != null && isHiddenBlock(state, world, worldSettings, block[0], block[1], block[2]);
    }

    /**
     * A block event (chest lid, block crack stage, ...) must not animate a block the viewer sees
     * as fake stone. Other effects still respect {@code hide-effects}.
     */
    private boolean hideBlockEventPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (worldSettings.terrainMaskingEnabled()) {
            int[] block = packetBlockPos(packet);
            if (block != null && isHiddenBlock(state, world, worldSettings, block[0], block[1], block[2])) {
                return true;
            }
        }
        return hideEffectPacket(state, world, packet);
    }

    /** The block position a packet carries in a {@code pos}/{@code position} field, or null. */
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

    /** The point a positional packet is about (sounds, particles, level events), or null. */
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

    /**
     * Masks the packet's chunk snapshot with per-column ceilings. Earned cells stay real, so a
     * late chunk packet cannot overwrite a reveal.
     */
    private boolean maskChunkDataPacket(PlayerState state, World world, WorldSettings worldSettings, Object packet) {
        if (!worldSettings.terrainMaskingEnabled()) {
            return false;
        }
        int minY = world.getMinHeight();
        int sectionCount = (world.getMaxHeight() - minY) / 16;
        if (sectionCount <= 0) {
            return false;
        }
        int chunkX;
        int chunkZ;
        Object chunkData;
        try {
            chunkX = (int) NativeProtocol.get(packet, "x");
            chunkZ = (int) NativeProtocol.get(packet, "z");
            chunkData = NativeProtocol.get(packet, "chunkData");
        } catch (Throwable throwable) {
            return false;
        }
        SurfaceHeights.ChunkCeilings ceilings = ceilingsForChunk(world, worldSettings, chunkX, chunkZ, chunkData);
        if (ceilings == null) {
            return false;
        }
        Object factory = NativeProtocol.palettedContainerFactoryRequired() ? palettedContainerFactory() : null;
        if (NativeProtocol.palettedContainerFactoryRequired() && factory == null) {
            return false;
        }
        NativeProtocol.BlockVisibility visible = state.hasVisibleCellsInChunk(chunkX, chunkZ) ? visibilityFor(state) : null;
        return NativeProtocol.maskChunkData(packet, factory, sectionCount, minY,
                ceilings.values(), ceilings.oreValues(),
                y -> fakeBlockStateFor(worldSettings, y), chunkX, chunkZ, visible,
                NmsBlockReader::isSensitiveBlockState);
    }

    /** Remembers the last earned cell for the column-ordered chunk mask. */
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

    /** @return a startup-visible problem with chunk masking, or null */
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

    /** @return a problem rewriting block updates per player, or null */
    public String blockUpdateSupportProblem() {
        return NativeProtocol.blockUpdateFormatProblem();
    }

    /** @return a problem reading positional effect packets, or null */
    public String effectPositionSupportProblem() {
        return NativeProtocol.effectPositionSupportProblem();
    }

    /** @return a problem looking inside entity-spawn bundles, or null */
    public String bundleSupportProblem() {
        return NativeProtocol.bundleSupportProblem();
    }

    /** Returns the per-column ceilings for a chunk packet, cached briefly because digging changes them. */
    private SurfaceHeights.ChunkCeilings ceilingsForChunk(World world, WorldSettings worldSettings, int chunkX, int chunkZ, Object chunkData) {
        CeilingKey key = new CeilingKey(world.getUID(), chunkX, chunkZ);
        long now = System.nanoTime();
        CeilingEntry cached = ceilingCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return new SurfaceHeights.ChunkCeilings(cached.ceilings(), cached.oreCeilings(), true);
        }
        SurfaceHeights.ChunkCeilings computed = SurfaceHeights.ceilingsForChunk(
                world, worldSettings, chunkX, chunkZ, chunkData);
        if (computed == null || computed.values() == null || computed.oreValues() == null) {
            return null;
        }
        // Never cache the flat fallback. A chunk packet can be built while its live height map
        // is still settling; caching that over-masked result is what previously kept a square
        // of fake stone over water after the real heights had become available.
        if (computed.fromTerrain()) {
            if (ceilingCache.size() > MAX_CEILING_ENTRIES_PER_WORLD) {
                ceilingCache.clear();
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
        return new CeilingEntry(computed.values(), computed.oreValues(), max,
                now + CEILING_CACHE_NANOS, now + CEILING_BOUND_NANOS);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private void storePointEntry(UUID worldId, int chunkX, int chunkZ, CeilingEntry entry) {
        ConcurrentHashMap<Long, CeilingEntry> perWorld =
                pointCeilings.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        if (perWorld.size() > MAX_CEILING_ENTRIES_PER_WORLD) {
            perWorld.clear();
        }
        perWorld.put(chunkKey(chunkX, chunkZ), entry);
    }

    /**
     * The highest masked Y in a chunk if it is known (even from a while ago), otherwise
     * {@link Integer#MAX_VALUE}. Never computes anything.
     */
    private int peekMaxHard(UUID worldId, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, CeilingEntry> perWorld = pointCeilings.get(worldId);
        if (perWorld == null) {
            return Integer.MAX_VALUE;
        }
        CeilingEntry entry = perWorld.get(chunkKey(chunkX, chunkZ));
        return entry != null && entry.boundUntil() > System.nanoTime() ? entry.maxHard() : Integer.MAX_VALUE;
    }

    /**
     * Per-column ceilings of one chunk for point queries, computed on demand and shared by every
     * viewer. A block update, a particle or an entity check used to walk the server's height map
     * reflectively for its single column; now one chunk answers all of them for a few seconds.
     *
     * @return the ceilings, or null when the chunk is not loaded or the world has no per-column data
     */
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

    /** The hard cutoff for one column: ores and containers below it need an earned cell. */
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

    /** The masking ceiling for one column, with the soil buffer applied. */
    private int effectiveCeiling(World world, WorldSettings worldSettings, int x, int z) {
        int flat = Math.min(worldSettings.maskCeilingY(), world.getMaxHeight());
        CeilingEntry entry = pointEntry(world, worldSettings, x >> 4, z >> 4);
        if (entry == null) {
            return flat;
        }
        return Math.min(flat, entry.ceilings()[((x & 15) << 4) | (z & 15)]);
    }

    /**
     * Whether a block is below the hard cutoff and not earned. This deliberately uses the
     * un-buffered cutoff: block updates and block-entity packets must not reveal ores or
     * containers that the soil buffer keeps visually real in the chunk snapshot.
     */
    private boolean isHiddenBlock(PlayerState state, World world, WorldSettings worldSettings, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= worldSettings.maskCeilingY()) {
            return false;
        }
        if (state.isCellVisible(CellPos.of(x, y, z, config.blockGrid().size()))) {
            return false;
        }
        return y < hardCeiling(world, worldSettings, x, z);
    }

    /** Returns (and caches) the server's own chunk container factory. */
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

    /** Returns (and caches) the server block state for the fake block at this Y. */
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

    private Object fakeBlockState(WorldSettings worldSettings, int y) {
        return fakeBlockStateFor(worldSettings, y);
    }

    /** Masks one block change into a private packet; never mutates the shared original. */
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
            Object fakeState = fakeBlockState(worldSettings, y);
            if (fakeState == null) {
                // Without a fake state the only safe choice is not to tell this client about
                // the change at all; it keeps seeing the fake terrain it already has.
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

    /** The same for the batched block change (pistons, fluids, ...). */
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
                Object fakeState = fakeBlockState(worldSettings, y);
                if (fakeState == null) {
                    // Do not send a half-masked update: swallowing it leaves the client with
                    // the fake terrain it already has, which is safer than a real state leak.
                    return null;
                }
                if (replacements == null) {
                    replacements = new Object[positions.length];
                }
                // The packet writes each entry from BlockState#getId, so replacing the state
                // object is enough - no short id has to be encoded by hand.
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

    /** Reports the first failure to build a per-player copy of a shared block-change packet. */
    private void reportBlockCopyFailure() {
        if (loggedFirstBlockCopyFailure.compareAndSet(false, true)) {
            plugin.getLogger().warning("Raytrace could not build a per-player block-change packet; "
                    + "the affected update was dropped to avoid exposing hidden blocks. "
                    + "The protection still runs, but this server version needs a compatibility "
                    + "fix. (Further occurrences are not logged.)");
        }
    }

    /** Whether an effect at this point should be hidden: inside masked terrain or behind a wall. */
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

    /**
     * Whether a sound is both far away and blocked by enough terrain that the viewer should not
     * hear it. Nearby sounds stay audible, as they would in reality.
     */
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
            return BlockRay.countOccludingAuto(world, ex, ey, ez, x, y, z, threshold, null, 0,
                    0, true) >= threshold;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Whether this point is hidden terrain for the viewer, using the real per-column ceiling
     * rather than the flat configured one.
     */
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

    /**
     * Whether an entity stands in terrain this viewer has not revealed. Checked before a spawn
     * packet leaves the server, so no position leaks.
     */
    private boolean isEntityInHiddenTerrain(PlayerState state, World world, WorldSettings worldSettings, double x, double y, double z) {
        try {
            return isHiddenPoint(state, world, worldSettings, x, y, z);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Whether a cell holds terrain the viewer has not earned. Cells that lie entirely above the
     * highest masked block of the chunks they overlap hold nothing to hide, so open air above
     * the ground never counts as "unrevealed" - otherwise a mob on the surface would be hidden
     * for as long as the scan had not got round to the air cells in between.
     */
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

    /** Whether every sample point on the entity silhouette is blocked. */
    private boolean isEntityOccluded(PlayerState state, World world, WorldSettings worldSettings, double ex, double ey, double ez, double baseX, double baseY, double baseZ, double width, double height, int threshold, boolean packetThread) {
        width = Math.max(0.2, width);
        height = Math.max(0.2, height);
        double centerX = baseX;
        double centerY = baseY + height / 2.0;
        double centerZ = baseZ;

        // The centre ray settles the common "plainly visible" case immediately.
        if (!isPointHidden(state, world, worldSettings, ex, ey, ez, centerX, centerY, centerZ,
                threshold, packetThread)) {
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
                if (!isPointHidden(state, world, worldSettings, ex, ey, ez, sampleX, sampleY, sampleZ, threshold, packetThread)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Blocked by a real wall or by terrain this viewer still sees as fake stone. */
    private boolean isPointHidden(PlayerState state, World world, WorldSettings worldSettings, double ex, double ey, double ez, double tx, double ty, double tz, int threshold, boolean packetThread) {
        int maskCeilingY = worldSettings.maskCeilingY();
        if (!worldSettings.terrainMaskingEnabled() || maskCeilingY == Integer.MIN_VALUE || Math.min(ey, ty) >= maskCeilingY) {
            return BlockRay.countOccludingAuto(world, ex, ey, ez, tx, ty, tz, threshold, null, 0, ENTITY_CORNER_SKIPS, packetThread) >= threshold;
        }
        int gridSize = config.blockGrid().size();
        boolean[] masked = {false};
        BlockRay.CellVisitor visitor = (cellX, cellY, cellZ) -> {
            if (isMaskingCell(state, world, worldSettings, cellX, cellY, cellZ, gridSize)) {
                masked[0] = true;
                return false;
            }
            return true;
        };
        int count = BlockRay.countOccludingAuto(world, ex, ey, ez, tx, ty, tz, threshold, visitor, gridSize, ENTITY_CORNER_SKIPS, packetThread);
        return count >= threshold || masked[0];
    }

    /** Whether this Bukkit entity could be hidden at all in this world (a cheap pre-filter). */
    private static boolean isCandidate(Entity entity, WorldSettings worldSettings) {
        if (entity instanceof LivingEntity) {
            // Armor stands are living entities and count as decoration.
            return worldSettings.hideMobs() || worldSettings.hidePlayers() || worldSettings.hideDecorations();
        }
        if (entity instanceof Vehicle) {
            return worldSettings.hideVehicles();
        }
        if (entity instanceof Item) {
            return worldSettings.hideItems();
        }
        if (entity instanceof Hanging || entity instanceof Display) {
            return worldSettings.hideDecorations();
        }
        return false;
    }

    /**
     * Hides entities the viewer cannot see and re-introduces the ones that became visible. A
     * verdict is reused while neither the viewer nor the entity moved and nothing was earned,
     * so a quiet scene costs almost nothing.
     */
    private void refreshEntityVisibility(Player player, PlayerState state, WorldSettings worldSettings, World world, Location eye) {
        if (!worldSettings.hidesAnyEntity()) {
            if (state.hasHiddenEntities()) {
                // The world stopped hiding entities (config reload): hand them back.
                revealHiddenEntities(player, state);
            }
            return;
        }
        GridSettings.EntityGrid entityGrid = config.entityGrid();
        if (!state.shouldRefreshEntityScan(entityGrid.scanIntervalTicks())) {
            return;
        }

        double radius = entityGrid.scanRadiusBlocks();
        Collection<Entity> nearby;
        try {
            nearby = world.getNearbyEntities(eye, radius, radius, radius, entity -> entity != player && isCandidate(entity, worldSettings));
        } catch (RuntimeException exception) {
            if (loggedFirstNearbyFailure.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Failed to list nearby entities for " + player.getName() + " (further occurrences are not logged); entity hiding is paused for that scan", exception);
            }
            return;
        }

        int scanId = state.beginEntityScan();
        long tick = state.tickCounter();
        int cellVersion = state.cellVersion();
        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        int threshold = entityGrid.occlusionBlocks();

        for (Entity entity : nearby) {
            EntitySnapshot snapshot = EntitySnapshot.read(entity);
            if (snapshot == null || !snapshot.valid() || !worldSettings.hides(snapshot.kind())) {
                continue;
            }
            int entityId = snapshot.entityId();
            PlayerState.EntityCheck check = state.entityCheck(entityId);
            boolean shouldHide;
            if (check != null && isVerdictFresh(check, ex, ey, ez, snapshot, cellVersion, tick)) {
                shouldHide = check.hidden;
            } else {
                try {
                    shouldHide = isEntityInHiddenTerrain(state, world, worldSettings, snapshot.x(), snapshot.y(), snapshot.z()) || isEntityOccluded(state, world, worldSettings, ex, ey, ez, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.width(), snapshot.height(), threshold, false);
                } catch (RuntimeException exception) {
                    if (loggedFirstOcclusionFailure.compareAndSet(false, true)) {
                        plugin.getLogger().log(Level.WARNING, "Entity occlusion check failed at least once (further occurrences are not logged) - the affected entity is left visible for now", exception);
                    }
                    shouldHide = false;
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
            }
            check.scanId = scanId;

            boolean hidden = state.isEntityHidden(entityId);
            if (shouldHide) {
                if (!hidden) {
                    hideVisibleEntity(state, snapshot);
                } else {
                    PlayerState.HiddenEntity entry = state.hiddenEntity(entityId);
                    if (entry != null) {
                        entry.confirmed = true;
                    }
                }
            } else if (hidden) {
                revealEntity(player, state, entityId);
            }
        }

        // Hidden entities that were not in this scan: gone, or beyond the scan radius.
        for (PlayerState.HiddenEntity entry : List.copyOf(state.hiddenEntities())) {
            PlayerState.EntityCheck check = state.entityCheck(entry.id);
            if (check != null && check.scanId == scanId) {
                continue;
            }
            Entity entity = entry.uuid == null ? null : Bukkit.getEntity(entry.uuid);
            if (entity == null) {
                state.unhideEntity(entry.id);
            } else {
                // Still alive but outside the radius this plugin manages: do not keep it
                // withheld forever (the spawn-time check skips such entities for the same reason).
                revealEntity(player, state, entry.id);
            }
        }
        state.endEntityScan(scanId);
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

    /** Takes an entity the client currently knows away from it. */
    private void hideVisibleEntity(PlayerState state, EntitySnapshot snapshot) {
        int entityId = snapshot.entityId();
        // Mark it hidden before the destroy goes out, so every later packet about it is dropped.
        state.hideEntity(entityId, snapshot.uuid(), state.takeSpawn(entityId), true);
        Object packet = NativeProtocol.newRemoveEntities(entityId);
        if (packet == null) {
            if (loggedFirstTrackerFailure.compareAndSet(false, true)) {
                plugin.getLogger().warning("Raytrace could not build a remove-entity packet; "
                        + "entities cannot be hidden after they were shown on this server version.");
            }
            return;
        }
        sendDirect(state, packet);
    }

    /** Hands a hidden entity back to the client. */
    private void revealEntity(Player player, PlayerState state, int entityId) {
        PlayerState.HiddenEntity entry = state.unhideEntity(entityId);
        if (entry == null) {
            return;
        }
        state.markRevealed(entityId);
        introduceEntity(player, state, entry);
    }

    /**
     * Makes the client learn about an entity again. The server's own tracker does it best: it
     * sends the spawn together with the current metadata, equipment and position. Only when
     * the tracker cannot be reached from this thread is the spawn packet kept earlier replayed,
     * which shows the entity as it was when it was first withheld.
     */
    private void introduceEntity(Player player, PlayerState state, PlayerState.HiddenEntity entry) {
        if (entry.uuid != null && repairViaTracker(player, entry.uuid)) {
            return;
        }
        replaySpawn(state, entry);
    }

    private void replaySpawn(PlayerState state, PlayerState.HiddenEntity entry) {
        if (entry.spawnPacket != null) {
            sendDirect(state, entry.spawnPacket);
        }
    }

    /**
     * Asks the tracker to send the entity afresh by hiding and showing it through the Bukkit API.
     * The entity is not hidden at that point, so everything the tracker sends reaches the client.
     */
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
                plugin.getLogger().log(Level.WARNING, "Player#hideEntity/showEntity failed; the cached spawn "
                        + "packet is used to bring entities back instead", throwable);
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

    /** The entity, but only when this thread may legally touch it. */
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

    private record RayCounts(int front, int side, int back, int vertical) {
    }

    /** A canonical ray pattern, built facing +Z and rotated onto the player's yaw at scan time. */
    private record RayPlan(int count, float[] xs, float[] ys, float[] zs) {
    }

    private record ChunkXZ(int x, int z) {
    }
}
