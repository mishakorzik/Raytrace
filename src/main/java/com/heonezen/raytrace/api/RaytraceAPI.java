package com.heonezen.raytrace.api;

import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.engine.RaytraceEngine;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

// Public API for other plugins. Reads are thread-safe; forceRevealBlock and resetPlayer must run on the player's thread.
public final class RaytraceAPI {

    private static volatile RaytraceAPI instance;

    private final RaytraceEngine engine;
    private final RaytraceConfig config;

    // Created by RaytracePlugin. Do not construct your own instance.
    public RaytraceAPI(RaytraceEngine engine, RaytraceConfig config) {
        this.engine = engine;
        this.config = config;
    }

    // Called by RaytracePlugin on enable/disable. Not intended for other plugins.
    public static void setInstance(RaytraceAPI api) {
        instance = api;
    }

    // The current API instance, or null if Raytrace is not enabled.
    public static RaytraceAPI get() {
        return instance;
    }

    // Whether Raytrace is enabled and this API can be used.
    public static boolean isAvailable() {
        return get() != null;
    }

    // Whether this viewer is exempt (spectator or raytrace.bypass; the permission is re-read every 2 s).
    public boolean isBypassed(Player player) {
        return engine.isBypassedCached(player);
    }

    // Whether the block at the given coordinates currently shows real data to this viewer, i.e. its terrain cell has been earned by the viewer.
    public boolean isBlockVisible(Player viewer, int x, int y, int z) {
        return engine.isBlockVisible(viewer, x, y, z);
    }

    // Whether the block at the given coordinates is currently masked for this viewer.
    public boolean isBlockHidden(Player viewer, int x, int y, int z) {
        return !isBlockVisible(viewer, x, y, z);
    }

    // Whether this entity is currently withheld from this viewer.
    public boolean isEntityHidden(Player viewer, Entity entity) {
        return entity != null && engine.isEntityIdHidden(viewer, entity.getEntityId());
    }

    // Whether this entity is currently visible to this viewer.
    public boolean isEntityVisible(Player viewer, Entity entity) {
        return entity != null && !isEntityHidden(viewer, entity);
    }

    // Whether the protection is enabled in this world.
    public boolean isWorldProtected(World world) {
        return world != null && config.worldSettings(world).enabled();
    }

    // Reveals the cell containing this block to this viewer now. Call on the player's thread.
    public void forceRevealBlock(Player viewer, int x, int y, int z) {
        engine.forceRevealBlock(viewer, x, y, z);
    }

    // Re-evaluates everything for this viewer, as on teleport. Call on the player's thread.
    public void resetPlayer(Player player) {
        engine.resetPlayer(player);
    }
}
