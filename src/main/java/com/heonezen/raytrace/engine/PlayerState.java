package com.heonezen.raytrace.engine;

import com.heonezen.raytrace.config.GridSettings;
import com.heonezen.raytrace.util.CellPos;
import com.heonezen.raytrace.util.ConcurrentIntSet;
import com.heonezen.raytrace.util.LongIntMap;
import io.netty.channel.Channel;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Per-player state. Netty threads touch only the concurrent fields; everything else is tick-thread only.
final class PlayerState {

    // An entity withheld from this client: it has no spawn on the client right now.
    static final class HiddenEntity {
        final int id;
        final UUID uuid;
        // The spawn packet to replay if the tracker cannot re-introduce the entity. May be null.
        final Object spawnPacket;

        HiddenEntity(int id, UUID uuid, Object spawnPacket) {
            this.id = id;
            this.uuid = uuid;
            this.spawnPacket = spawnPacket;
        }
    }

    // The last visibility verdict for one entity, so an unchanged situation is not re-ray-traced.
    static final class EntityCheck {
        double eyeX;
        double eyeY;
        double eyeZ;
        double x;
        double y;
        double z;
        int cellVersion;
        long tick;
        int scanId;
        boolean hidden;
        // Hidden because it is in hidden terrain (underground), not just behind a wall: no grace delay.
        boolean hiddenTerrain;
        // Tick the still-visible entity was first found occluded, or -1. It is hidden after ENTITY_HIDE_DELAY_TICKS.
        long hiddenSinceTick = -1L;
    }

    private final Set<CellPos> visibleCells = ConcurrentHashMap.newKeySet();
    // Earned cells grouped by the chunk column of their minimum corner.
    private final ConcurrentHashMap<Long, Set<CellPos>> columns = new ConcurrentHashMap<>();
    private final AtomicInteger cellVersion = new AtomicInteger();

    private final ConcurrentHashMap<Integer, HiddenEntity> hiddenEntities = new ConcurrentHashMap<>();
    private final ConcurrentIntSet hiddenIds = new ConcurrentIntSet();
    // Spawn packets of entities the client currently knows and that could be hidden later.
    private final ConcurrentHashMap<Integer, Object> knownSpawns = new ConcurrentHashMap<>();
    // Entities this plugin just introduced itself; their spawn must not be re-judged at once.
    private final ConcurrentHashMap<Integer, Integer> revealedAt = new ConcurrentHashMap<>();
    // Waypoints this client holds as a direction only, because the exact position was withheld.
    private final Set<UUID> convertedWaypoints = ConcurrentHashMap.newKeySet();
    // Entities the client currently has, both ways round (any thread).
    private final ConcurrentHashMap<UUID, Integer> clientEntities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UUID> clientEntityUuids = new ConcurrentHashMap<>();
    // A pass over nearby entities that a time limit cut short (tick thread only).
    private List<org.bukkit.entity.Entity> entityPass;
    private int entityPassIndex;
    private int entityPassId;

    private volatile Object connection;
    private volatile Channel channel;
    private volatile World world;

    private volatile boolean bypassed;
    private volatile boolean spectator;
    private volatile boolean permissionBypass;
    private volatile boolean eyeCached;
    // Entity id of what the player is riding, or -1. Read by the spawn check on Netty threads.
    private volatile int vehicleId = -1;
    private volatile int viewerId = -1;
    private volatile double eyeX;
    private volatile double eyeY;
    private volatile double eyeZ;

    private long tickCounter;
    private long lastScanTick = Long.MIN_VALUE;
    private long lastEntityScanTick = Long.MIN_VALUE;
    private long lastPermissionTick = Long.MIN_VALUE;
    private String lastScanWorld;
    private double lastScanX;
    private double lastScanY;
    private double lastScanZ;
    private float lastScanYaw;
    private float lastScanPitch;

    private boolean focusValid;
    private double focusX;
    private double focusY;
    private double focusZ;
    private float focusYaw;
    private float focusPitch;

    private boolean bubbleValid;
    private int bubbleX;
    private int bubbleY;
    private int bubbleZ;

    private boolean pendingDeferredReveals;
    private int rayCursor;
    private int rayProgress;
    private int refinePass;
    private List<CellPos> deferredCells = new ArrayList<>();

    private final Map<Integer, EntityCheck> entityChecks = new HashMap<>();
    private volatile int entityScanId;

    final LongIntMap focusTouched = new LongIntMap(512);
    final LongIntMap scanTouched = new LongIntMap(2048);

    Object connection() {
        return connection;
    }

    Channel channel() {
        return channel;
    }

    void setConnection(Object connection, Channel channel) {
        this.connection = connection;
        this.channel = channel;
    }

    World world() {
        return world;
    }

    void setWorld(World world) {
        this.world = world;
    }

    boolean isCellVisible(CellPos cell) {
        return visibleCells.contains(cell);
    }

    boolean hasVisibleCells() {
        return !visibleCells.isEmpty();
    }

    // Bumped whenever the set of earned cells changes; entity verdicts depend on it.
    int cellVersion() {
        return cellVersion.get();
    }

    void markCellVisible(CellPos cell) {
        if (visibleCells.add(cell)) {
            columns.computeIfAbsent(columnKey(cell.chunkX(), cell.chunkZ()), ignored -> ConcurrentHashMap.newKeySet()).add(cell);
            cellVersion.incrementAndGet();
        }
    }

    // Whether any earned cell can touch this chunk.
    boolean hasVisibleCellsInChunk(int chunkX, int chunkZ) {
        return columnHasCells(chunkX, chunkZ) || columnHasCells(chunkX - 1, chunkZ) || columnHasCells(chunkX, chunkZ - 1) || columnHasCells(chunkX - 1, chunkZ - 1);
    }

    private boolean columnHasCells(int chunkX, int chunkZ) {
        Set<CellPos> set = columns.get(columnKey(chunkX, chunkZ));
        return set != null && !set.isEmpty();
    }

    private static long columnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    // Forgets the cells whose minimum corner is in this chunk column.
    void forgetColumn(int chunkX, int chunkZ) {
        Set<CellPos> removed = columns.remove(columnKey(chunkX, chunkZ));
        if (removed != null && !removed.isEmpty()) {
            visibleCells.removeAll(removed);
            cellVersion.incrementAndGet();
        }
    }

    // Forgets cells outside the radius; returns the first ring for re-masking (the client unloaded the rest).
    Set<CellPos> forgetOutside(int centerChunkX, int centerChunkZ, int radiusChunks) {
        Set<CellPos> forgotten = null;
        boolean changed = false;
        for (Map.Entry<Long, Set<CellPos>> entry : columns.entrySet()) {
            long key = entry.getKey();
            int columnX = (int) (key >> 32);
            int columnZ = (int) key;
            int deltaX = Math.abs(columnX - centerChunkX);
            int deltaZ = Math.abs(columnZ - centerChunkZ);
            if (deltaX <= radiusChunks && deltaZ <= radiusChunks) {
                continue;
            }
            Set<CellPos> cells = entry.getValue();
            columns.remove(key, cells);
            visibleCells.removeAll(cells);
            changed = true;
            if (Math.max(deltaX, deltaZ) <= radiusChunks + 1) {
                if (forgotten == null) {
                    forgotten = new HashSet<>();
                }
                forgotten.addAll(cells);
            }
        }
        if (changed) {
            cellVersion.incrementAndGet();
        }
        return forgotten == null ? Set.of() : forgotten;
    }

    // Forgets every earned cell and returns them for re-masking (config reload).
    Set<CellPos> forgetInside() {
        Set<CellPos> forgotten = new HashSet<>();
        boolean changed = false;
        for (Map.Entry<Long, Set<CellPos>> entry : columns.entrySet()) {
            long key = entry.getKey();
            int columnX = (int) (key >> 32);
            int columnZ = (int) key;
            Set<CellPos> cells = entry.getValue();
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            columns.remove(key, cells);
            visibleCells.removeAll(cells);
            forgotten.addAll(cells);
            changed = true;
        }
        if (changed) {
            cellVersion.incrementAndGet();
        }
        lastScanTick = Long.MIN_VALUE;
        lastScanWorld = null;
        pendingDeferredReveals = false;
        refinePass = 0;
        resetFrontier();
        deferredCells = new ArrayList<>();
        return forgotten;
    }

    // Forces the next terrain scan to run immediately on the next tick, even standing still.
    void invalidateScan() {
        lastScanTick = Long.MIN_VALUE;
    }

    // Forgets the terrain the viewer had earned, without touching the hidden entities.
    void clearVisibleCells() {
        visibleCells.clear();
        columns.clear();
        cellVersion.incrementAndGet();
        lastScanTick = Long.MIN_VALUE;
        lastScanWorld = null;
        pendingDeferredReveals = false;
        rayCursor = 0;
        rayProgress = 0;
        refinePass = 0;
        resetFrontier();
        deferredCells = new ArrayList<>();
        focusValid = false;
        bubbleValid = false;
        entityChecks.clear();
    }

    void incrementTick() {
        tickCounter++;
    }

    long tickCounter() {
        return tickCounter;
    }

    // Whether a terrain scan is due. An idle player gets refinePasses extra passes, shifted between the earlier rays.
    boolean shouldRefreshScan(String worldName, double x, double y, double z, float yaw, float pitch, GridSettings.BlockGrid settings, int refinePasses) {
        long last = lastScanTick;
        if (last != Long.MIN_VALUE && tickCounter - last < settings.scanTicks()) {
            return false;
        }
        boolean sameWorld = worldName.equals(lastScanWorld);
        boolean still = false;
        if (sameWorld) {
            double dx = x - lastScanX;
            double dy = y - lastScanY;
            double dz = z - lastScanZ;
            double moveThresholdSq = settings.moveThresholdBlocks() * settings.moveThresholdBlocks();
            boolean barelyMoved = dx * dx + dy * dy + dz * dz < moveThresholdSq;
            boolean barelyTurned = yawDelta(yaw, lastScanYaw) < settings.yawThresholdDegrees() && Math.abs(pitch - lastScanPitch) < settings.pitchThresholdDegrees();
            still = barelyMoved && barelyTurned;
        }
        if (!still) {
            // Real movement: the next idle period refines from the start again.
            refinePass = 0;
            resetFrontier();
        }
        if (still && !pendingDeferredReveals) {
            if (refinePass >= refinePasses) {
                return false;
            }
            // The previous pass is complete and nothing moved: run a shifted pass.
            refinePass++;
            rayProgress = 0;
        } else if (!pendingDeferredReveals || !sameWorld) {
            // A scan started by movement begins a new pass over the ray pattern.
            rayProgress = 0;
        }
        lastScanWorld = worldName;
        lastScanX = x;
        lastScanY = y;
        lastScanZ = z;
        lastScanYaw = yaw;
        lastScanPitch = pitch;
        lastScanTick = tickCounter;
        return true;
    }

    // The idle pass: 0 is the plain pattern, 1 and up the shifted refinement passes.
    int refinePass() {
        return refinePass;
    }

    // Lets an idle player's refinement passes run again, e.g. after a block was broken.
    void restartRefinement() {
        refinePass = 0;
        resetFrontier();
    }

    // Frontier growth (tick thread only): far cells a ray reached, whose neighbours are checked with rays aimed straight at them.
    private static final int FRONTIER_MAX_CELLS = 65_536;

    // A cell waiting for its neighbours to be tested; the lowest priority (nearest the crosshair) goes first.
    private record FrontierCell(long key, double priority) {}

    private final java.util.PriorityQueue<FrontierCell> frontier = new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(FrontierCell::priority));
    private final LongIntMap frontierSeen = new LongIntMap(256);
    // The targeted-ray budget of this idle period; -1 until the engine sets it.
    private int frontierBudget = -1;
    private int frontierRaysLeft = -1;

    private void resetFrontier() {
        frontier.clear();
        frontierSeen.clear();
        frontierBudget = -1;
        frontierRaysLeft = -1;
    }

    // Sets the targeted-ray budget of this idle period, once.
    void ensureFrontierBudget(int budget) {
        if (frontierBudget < 0) {
            frontierBudget = budget;
            frontierRaysLeft = budget;
        }
    }

    // Marks a cell as considered in this idle period; false if it already was (or the period has looked at as many cells as it may).
    boolean markFrontierSeen(long cellKey) {
        if (frontierSeen.size() >= FRONTIER_MAX_CELLS || frontierSeen.get(cellKey, -1) >= 0) {
            return false;
        }
        frontierSeen.put(cellKey, 0);
        return true;
    }

    // Undoes markFrontierSeen for a cell whose test was cut short, so it is tested again.
    void forgetFrontierSeen(long cellKey) {
        frontierSeen.put(cellKey, -1);
    }

    // Starts the ray pattern over from the current cursor.
    void restartPass() {
        rayProgress = 0;
    }

    // Adds a reached cell whose neighbours still have to be checked.
    void offerFrontierSeed(long cellKey, double priority) {
        if (markFrontierSeen(cellKey)) {
            frontier.add(new FrontierCell(cellKey, priority));
        }
    }

    // Adds a cell that was just reached (already marked as seen).
    void enqueueFrontier(long cellKey, double priority) {
        frontier.add(new FrontierCell(cellKey, priority));
    }

    boolean hasFrontier() {
        return !frontier.isEmpty() && frontierRaysLeft != 0;
    }

    // Takes the most important waiting cell. A cell that could not be finished goes back with enqueueFrontier.
    long pollFrontier() {
        return frontier.poll().key();
    }

    // How many targeted rays this idle period has used (self-check).
    int frontierRaysUsed() {
        return frontierBudget < 0 ? 0 : frontierBudget - frontierRaysLeft;
    }

    // Takes one targeted ray from the idle period's budget; false when it is used up.
    boolean takeFrontierRay() {
        if (frontierRaysLeft == 0) {
            return false;
        }
        if (frontierRaysLeft > 0) {
            frontierRaysLeft--;
        }
        return true;
    }

    void setPendingDeferredReveals(boolean pending) {
        pendingDeferredReveals = pending;
    }

    int rayCursor() {
        return rayCursor;
    }

    int rayProgress() {
        return rayProgress;
    }

    // Records how many rays this scan cast; the next scan continues after them.
    void advanceRays(int cast, int planSize) {
        if (planSize <= 0) {
            rayCursor = 0;
            return;
        }
        rayCursor = (rayCursor + cast) % planSize;
        rayProgress += cast;
    }

    List<CellPos> takeDeferredCells() {
        List<CellPos> result = deferredCells;
        deferredCells = new ArrayList<>();
        return result;
    }

    void setDeferredCells(List<CellPos> cells) {
        deferredCells = cells;
    }

    // Whether the cheap every-tick look-direction pass is worth running: it is skipped while the player neither moves nor turns.
    boolean shouldRunFocus(double x, double y, double z, float yaw, float pitch) {
        if (focusValid) {
            double dx = x - focusX;
            double dy = y - focusY;
            double dz = z - focusZ;
            if (dx * dx + dy * dy + dz * dz < 0.0225 && yawDelta(yaw, focusYaw) < 1.5F && Math.abs(pitch - focusPitch) < 1.0F) {
                return false;
            }
        }
        focusValid = true;
        focusX = x;
        focusY = y;
        focusZ = z;
        focusYaw = yaw;
        focusPitch = pitch;
        return true;
    }

    // Whether the player's body-sized safety bubble moved to another block.
    boolean bubbleMoved(int blockX, int blockY, int blockZ) {
        if (bubbleValid && bubbleX == blockX && bubbleY == blockY && bubbleZ == blockZ) {
            return false;
        }
        bubbleValid = true;
        bubbleX = blockX;
        bubbleY = blockY;
        bubbleZ = blockZ;
        return true;
    }

    // Forces the next focus pass and bubble to run, e.g. after a teleport.
    void invalidateFocus() {
        focusValid = false;
        bubbleValid = false;
        lastScanTick = Long.MIN_VALUE;
    }

    // Whether the hidden-entity scan is due.
    boolean shouldRefreshEntityScan(long intervalTicks) {
        long last = lastEntityScanTick;
        if (last != Long.MIN_VALUE && tickCounter - last < intervalTicks) {
            return false;
        }
        lastEntityScanTick = tickCounter;
        return true;
    }

    // Whether the permission lookup is due again (it is far more expensive than a game-mode read).
    boolean shouldRefreshPermission(long intervalTicks) {
        long last = lastPermissionTick;
        if (last != Long.MIN_VALUE && tickCounter - last < intervalTicks) {
            return false;
        }
        lastPermissionTick = tickCounter;
        return true;
    }

    private static float yawDelta(float a, float b) {
        float delta = Math.abs(a - b) % 360.0F;
        return delta > 180.0F ? 360.0F - delta : delta;
    }

    boolean bypassed() {
        return bypassed;
    }

    void setSpectator(boolean value) {
        spectator = value;
        bypassed = value || permissionBypass;
    }

    void setPermissionBypass(boolean value) {
        permissionBypass = value;
        bypassed = value || spectator;
    }

    void updateEye(double x, double y, double z) {
        eyeX = x;
        eyeY = y;
        eyeZ = z;
        eyeCached = true;
    }

    boolean hasEye() {
        return eyeCached;
    }

    int vehicleId() {
        return vehicleId;
    }

    // The viewer's own entity id, or -1 before the first tick.
    int viewerId() {
        return viewerId;
    }

    void setViewerId(int entityId) {
        viewerId = entityId;
    }

    void setVehicleId(int entityId) {
        vehicleId = entityId;
    }

    double eyeX() {
        return eyeX;
    }

    double eyeY() {
        return eyeY;
    }

    double eyeZ() {
        return eyeZ;
    }

    // Whether the client must not hear about this entity right now. Safe on any thread.
    boolean isEntityHidden(int entityId) {
        return hiddenIds.contains(entityId);
    }

    boolean hasHiddenEntities() {
        return !hiddenIds.isEmpty();
    }

    Collection<HiddenEntity> hiddenEntities() {
        return hiddenEntities.values();
    }

    // Starts withholding an entity.
    HiddenEntity hideEntity(int entityId, UUID entityUuid, Object spawnPacket) {
        HiddenEntity entry = new HiddenEntity(entityId, entityUuid, spawnPacket);
        // Either the client never got it, or it is being taken away right now.
        noteClientRemove(entityId);
        hiddenEntities.put(entityId, entry);
        hiddenIds.add(entityId);
        return entry;
    }

    // Stops withholding an entity; the caller then has to re-introduce it to the client.
    HiddenEntity unhideEntity(int entityId) {
        // The id leaves the fast set first, so packets queued after this point are not dropped.
        hiddenIds.remove(entityId);
        return hiddenEntities.remove(entityId);
    }

    // Records that a spawn for this entity went out to the client. Safe on any thread.
    void noteClientSpawn(int entityId, UUID uuid) {
        UUID previous = clientEntityUuids.put(entityId, uuid);
        if (previous != null && !previous.equals(uuid)) {
            clientEntities.remove(previous, entityId);
        }
        clientEntities.put(uuid, entityId);
    }

    // Records that the client no longer has this entity. Safe on any thread.
    void noteClientRemove(int entityId) {
        UUID uuid = clientEntityUuids.remove(entityId);
        if (uuid != null) {
            clientEntities.remove(uuid, entityId);
        }
    }

    // Whether the client currently has this entity, i.e. already knows where it is.
    boolean isOnClient(UUID uuid) {
        return clientEntities.containsKey(uuid);
    }

    // The client dropped every entity (respawn, world change, login).
    void forgetClientEntities() {
        clientEntityUuids.clear();
        clientEntities.clear();
    }

    // The current pass over nearby entities, or null between passes (tick thread only).
    List<org.bukkit.entity.Entity> entityPass() {
        return entityPass;
    }

    int entityPassId() {
        return entityPassId;
    }

    int entityPassIndex() {
        return entityPassIndex;
    }

    void startEntityPass(List<org.bukkit.entity.Entity> entities, int passId) {
        entityPass = entities;
        entityPassId = passId;
        entityPassIndex = 0;
    }

    void setEntityPassIndex(int index) {
        entityPassIndex = index;
    }

    void finishEntityPass() {
        entityPass = null;
    }

    boolean isWaypointConverted(UUID uuid) {
        return convertedWaypoints.contains(uuid);
    }

    void markWaypointConverted(UUID uuid) {
        convertedWaypoints.add(uuid);
    }

    void forgetConvertedWaypoint(UUID uuid) {
        convertedWaypoints.remove(uuid);
    }

    boolean hasKnownSpawns() {
        return !knownSpawns.isEmpty();
    }

    void rememberSpawn(int entityId, Object packet) {
        knownSpawns.put(entityId, packet);
    }

    Object takeSpawn(int entityId) {
        return knownSpawns.remove(entityId);
    }

    void forgetSpawn(int entityId) {
        knownSpawns.remove(entityId);
    }

    // Remembers that an entity was just introduced, so its fresh spawn passes unjudged.
    void markRevealed(int entityId) {
        revealedAt.put(entityId, entityScanId);
    }

    boolean recentlyRevealed(int entityId) {
        return !revealedAt.isEmpty() && revealedAt.containsKey(entityId);
    }

    void clearEntities() {
        revealedAt.clear();
        hiddenEntities.clear();
        hiddenIds.clear();
        convertedWaypoints.clear();
        knownSpawns.clear();
        entityChecks.clear();
        forgetClientEntities();
        entityPass = null;
    }

    int beginEntityScan() {
        int id = ++entityScanId;
        // A reveal is only exempt for about one scan; after that the normal verdict applies.
        revealedAt.values().removeIf(scan -> scan < id - 1);
        return id;
    }

    EntityCheck entityCheck(int entityId) {
        return entityChecks.get(entityId);
    }

    EntityCheck newEntityCheck(int entityId) {
        EntityCheck check = new EntityCheck();
        entityChecks.put(entityId, check);
        return check;
    }

    // Drops verdicts of entities that were not part of the scan that just ended.
    void endEntityScan(int scanId) {
        entityChecks.values().removeIf(check -> check.scanId != scanId);
    }

    // Drops cached entity verdicts, so the next scan re-traces them.
    void invalidateEntityChecks() {
        entityChecks.clear();
    }

    // Forgets everything: used when the player changes world.
    void clear() {
        clearVisibleCells();
        clearEntities();
        lastEntityScanTick = Long.MIN_VALUE;
        eyeCached = false;
    }
}
