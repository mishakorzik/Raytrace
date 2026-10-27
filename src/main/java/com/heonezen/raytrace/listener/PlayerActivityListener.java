package com.heonezen.raytrace.listener;

import com.heonezen.raytrace.config.RaytraceConfig;
import com.heonezen.raytrace.config.WorldSettings;
import com.heonezen.raytrace.engine.RaytraceEngine;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public final class PlayerActivityListener implements Listener {

    private final RaytraceConfig config;
    private final RaytraceEngine engine;

    public PlayerActivityListener(RaytraceConfig config, RaytraceEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        engine.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        engine.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        World fromWorld = event.getFrom().getWorld();
        if (to == null || to.getWorld() == null || !to.getWorld().equals(fromWorld)) {
            // Cross-world teleports are handled by PlayerChangedWorldEvent instead.
            return;
        }
        // The event fires before the move: hand over the destination so the surroundings there
        // are revealed, not the ones the player is leaving.
        engine.resetPlayer(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onChangedWorld(PlayerChangedWorldEvent event) {
        engine.resetPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onRespawn(PlayerRespawnEvent event) {
        engine.resetPlayer(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler
    void onChunkUnload(ChunkUnloadEvent event) {
        engine.onChunkUnload(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockBreak(BlockBreakEvent event) {
        revealIfMasked(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockPlace(BlockPlaceEvent event) {
        revealIfMasked(event.getPlayer(), event.getBlock());
    }

    /**
     * A player breaking or placing a block in hidden terrain must always see the true result of
     * their own action, so that exact block is force-revealed immediately rather than waiting
     * for the next reveal-scan (which may not trigger at all if they are standing still).
     */
    private void revealIfMasked(Player player, Block block) {
        World world = block.getWorld();
        WorldSettings worldSettings = config.worldSettings(world);
        if (!worldSettings.isMaskedY(block.getY(), world.getMinHeight())) {
            return;
        }
        engine.forceRevealBlock(player, block.getX(), block.getY(), block.getZ());
    }
}
