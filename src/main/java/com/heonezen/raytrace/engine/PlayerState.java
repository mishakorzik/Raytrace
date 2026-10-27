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

/**
 * Per-player state. The Netty threads read only the concurrent members (earned cells, hidden
 * entity ids, the cached eye, world and bypass flag); everything marked "tick thread" is touched
 * only by the player's own region thread.
 */
final class PlayerState {

    /** An entity withheld from this client: it has no spawn on the client right now. */
    static final class HiddenEntity {
        final int id;
        final UUID uuid;
        /** The spawn packet to replay if the tracker cannot re-introduce the entity. May be null. */
        final Object spawnPacket;
        /** False until the periodic scan has confirmed what the spawn-time check decided. */
        volatile boolean confirmed;

        HiddenEntity(int id, UUID uuid, Object spawnPacket, boolean confirmed) {
            this.id = id;
            this.uuid = uuid;
            this.spawnPacket = spawnPacket;
            this.confirmed = confirmed;
        }
    }

    /** The last visibility verdict for one entity, so an unchanged situation is not re-ray-traced. */
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
    }

    final UUID uuid;

    private final Set<CellPos> visibleCells = ConcurrentHashMap.newKeySet();
    /** Earned cells grouped by the chunk column of their minimum corner. */
    private final ConcurrentHashMap<Long, Set<CellPos>> columns = new ConcurrentHashMap<>();
    private final AtomicInteger cellVersion = new AtomicInteger();

    private final ConcurrentHashMap<Integer, HiddenEntity> hiddenEntities = new ConcurrentHashMap<>();
    private final ConcurrentIntSet hiddenIds = new ConcurrentIntSet();
    /** Spawn packets of entities the client currently knows and that could be hidden later. */
    private final ConcurrentHashMap<Integer, Object> knownSpawns = new ConcurrentHashMap<>();
    /** Entities this plugin just introduced itself; their spawn must not be re-judged at once. */
    private final ConcurrentHashMap<Integer, Integer> revealedAt = new ConcurrentHashMap<>();

    private volatile Object connection;
    private volatile Channel channel;
    private volatile World world;
    private volatile boolean online = true;

    private volatile boolean bypassed;
    private volatile boolean spectator;
    private volatile boolean permissionBypass;
    private volatile boolean eyeCached;
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
    private List<CellPos> deferredCells = new ArrayList<>();

    private final Map<Integer, EntityCheck> entityChecks = new HashMap<>();
    private volatile int entityScanId;

    final LongIntMap focusTouched = new LongIntMap(512);
    final LongIntMap scanTouched = new LongIntMap(2048);

    PlayerState(UUID uuid) {
        this.uuid = uuid;
    }

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

    boolean online() {
        return online;
    }

    void markOffline() {
        online = false;
    }

    boolean isCellVisible(CellPos cell) {
        return visibleCells.contains(cell);
    }

    boolean hasVisibleCells() {
        return !visibleCells.isEmpty();
    }

    /** Bumped whenever the set of earned cells changes; entity verdicts depend on it. */
    int cellVersion() {
        return cellVersion.get();
    }

    void markCellVisible(CellPos cell) {
        if (visibleCells.add(cell)) {
            columns.computeIfAbsent(columnKey(cell.chunkX(), cell.chunkZ()),
                    ignored -> ConcurrentHashMap.newKeySet()).add(cell);
            cellVersion.incrementAndGet();
        }
    }

    /**
     * Whether any earned cell can touch this chunk. A cell is at most 16 blocks wide, so it
     * reaches into a chunk from the column of its minimum corner or the one before it.
     */
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

    /** Forgets the cells whose minimum corner is in this chunk column. */
    void forgetColumn(int chunkX, int chunkZ) {
        Set<CellPos> removed = columns.remove(columnKey(chunkX, chunkZ));
        if (removed != null && !removed.isEmpty()) {
            visibleCells.removeAll(removed);
            cellVersion.incrementAndGet();
        }
    }

    /**
     * Forgets cells outside the radius and returns only the first ring for re-masking; cells
     * further out are already unloaded by the client. Works on whole columns, so the cost is the
     * number of columns, not the number of earned cells.
     */
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

    /** Forgets the terrain the viewer had earned, without touching the hidden entities. */
    void clearVisibleCells() {
        visibleCells.clear();
        columns.clear();
        cellVersion.incrementAndGet();
        lastScanTick = Long.MIN_VALUE;
        lastScanWorld = null;
        pendingDeferredReveals = false;
        rayCursor = 0;
        rayProgress = 0;
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

    /**
     * Whether a terrain scan is due. A scan that ran out of budget keeps the next one coming
     * even when the player stands still, otherwise cells deferred for lack of budget would wait
     * for the player to move.
     */
    boolean shouldRefreshScan(String worldName, double x, double y, double z, float yaw, float pitch,
                              GridSettings.BlockGrid settings) {
        long last = lastScanTick;
        if (last != Long.MIN_VALUE && tickCounter - last < settings.scanTicks()) {
            return false;
        }
        boolean sameWorld = worldName.equals(lastScanWorld);
        if (sameWorld && !pendingDeferredReveals) {
            double dx = x - lastScanX;
            double dy = y - lastScanY;
            double dz = z - lastScanZ;
            double moveThresholdSq = settings.moveThresholdBlocks() * settings.moveThresholdBlocks();
            boolean barelyMoved = dx * dx + dy * dy + dz * dz < moveThresholdSq;
            boolean barelyTurned = yawDelta(yaw, lastScanYaw) < settings.yawThresholdDegrees()
                    && Math.abs(pitch - lastScanPitch) < settings.pitchThresholdDegrees();
            if (barelyMoved && barelyTurned) {
                return false;
            }
        }
        if (!pendingDeferredReveals || !sameWorld) {
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

    void setPendingDeferredReveals(boolean pending) {
        pendingDeferredReveals = pending;
    }

    int rayCursor() {
        return rayCursor;
    }

    int rayProgress() {
        return rayProgress;
    }

    /** Records how many rays this scan cast; the next scan continues after them. */
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

    /**
     * Whether the cheap every-tick look-direction pass is worth running: it is skipped while the
     * player neither moves nor turns.
     */
    boolean shouldRunFocus(double x, double y, double z, float yaw, float pitch) {
        if (focusValid) {
            double dx = x - focusX;
            double dy = y - focusY;
            double dz = z - focusZ;
            if (dx * dx + dy * dy + dz * dz < 0.0225 && yawDelta(yaw, focusYaw) < 1.5F
                    && Math.abs(pitch - focusPitch) < 1.0F) {
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

    /** Whether the player's body-sized safety bubble moved to another block. */
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

    /** Forces the next focus pass and bubble to run, e.g. after a teleport. */
    void invalidateFocus() {
        focusValid = false;
        bubbleValid = false;
        lastScanTick = Long.MIN_VALUE;
    }

    /**
     * Whether the hidden-entity scan is due. The {@code Long.MIN_VALUE} sentinel is checked
     * explicitly because subtracting it would overflow.
     */
    boolean shouldRefreshEntityScan(long intervalTicks) {
        long last = lastEntityScanTick;
        if (last != Long.MIN_VALUE && tickCounter - last < intervalTicks) {
            return false;
        }
        lastEntityScanTick = tickCounter;
        return true;
    }

    /** Whether the permission lookup is due again (it is far more expensive than a game-mode read). */
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

    void setBypassed(boolean value) {
        bypassed = value;
    }

    boolean spectator() {
        return spectator;
    }

    void setSpectator(boolean value) {
        spectator = value;
        bypassed = value || permissionBypass;
    }

    boolean permissionBypass() {
        return permissionBypass;
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

    double eyeX() {
        return eyeX;
    }

    double eyeY() {
        return eyeY;
    }

    double eyeZ() {
        return eyeZ;
    }

    /** Whether the client must not hear about this entity right now. Safe on any thread. */
    boolean isEntityHidden(int entityId) {
        return hiddenIds.contains(entityId);
    }

    boolean hasHiddenEntities() {
        return !hiddenIds.isEmpty();
    }

    HiddenEntity hiddenEntity(int entityId) {
        return hiddenEntities.get(entityId);
    }

    Collection<HiddenEntity> hiddenEntities() {
        return hiddenEntities.values();
    }

    /** Starts withholding an entity. */
    HiddenEntity hideEntity(int entityId, UUID entityUuid, Object spawnPacket, boolean confirmed) {
        HiddenEntity entry = new HiddenEntity(entityId, entityUuid, spawnPacket, confirmed);
        hiddenEntities.put(entityId, entry);
        hiddenIds.add(entityId);
        return entry;
    }

    /** Stops withholding an entity; the caller then has to re-introduce it to the client. */
    HiddenEntity unhideEntity(int entityId) {
        // The id leaves the fast set first, so packets queued after this point are not dropped.
        hiddenIds.remove(entityId);
        return hiddenEntities.remove(entityId);
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

    /** Remembers that an entity was just introduced, so its fresh spawn passes unjudged. */
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
        knownSpawns.clear();
        entityChecks.clear();
    }

    // ---- entity verdict cache (tick thread)

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

    /** Drops verdicts of entities that were not part of the scan that just ended. */
    void endEntityScan(int scanId) {
        entityChecks.values().removeIf(check -> check.scanId != scanId);
    }

    /** Forgets everything: used when the player changes world. */
    void clear() {
        clearVisibleCells();
        clearEntities();
        lastEntityScanTick = Long.MIN_VALUE;
        eyeCached = false;
    }
}
