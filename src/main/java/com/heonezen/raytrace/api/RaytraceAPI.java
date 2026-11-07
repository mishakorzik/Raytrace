package com.heonezen.raytrace.api;

import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.engine.RaytraceEngine;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Public API for other plugins to query and control Raytrace's per-player visibility state.
 *
 * <p>Obtain the instance with {@link #get()} (or check {@link #isAvailable()} first). The
 * instance is created by {@code RaytracePlugin} on enable and cleared on disable.
 *
 * <p>Threading: the read-only checks ({@link #isBlockVisible}, {@link #isEntityHidden},
 * {@link #isBypassed}, {@link #isWorldProtected}) may be called from any thread. The actions
 * {@link #forceRevealBlock} and {@link #resetPlayer} send block updates to the client and must
 * be called on the thread that owns the player (in Folia: the player's region thread).
 */
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

    // Whether this viewer is exempt from the protection (spectator mode or the
    // {@code raytrace.bypass} permission, cached). Takes effect within a couple of
    // seconds of a permission change.
    public boolean isBypassed(Player player) {
        return engine.isBypassedCached(player);
    }

    // Whether the block at the given coordinates currently shows real data to this viewer,
    // i.e. its terrain cell has been earned by the viewer.
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

    // Reveals the terrain cell containing this block to this viewer immediately, for example
    // after the viewer edits the block. Call on the thread owning the player.
    public void forceRevealBlock(Player viewer, int x, int y, int z) {
        engine.forceRevealBlock(viewer, x, y, z);
    }

    // Re-evaluates everything for this viewer (same as Raytrace does on teleport/respawn).
    // Call on the thread owning the player.
    public void resetPlayer(Player player) {
        engine.resetPlayer(player);
    }
}
