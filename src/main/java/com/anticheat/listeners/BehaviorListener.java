package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class BehaviorListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public BehaviorListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getBehaviorTracker().onPlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getBehaviorTracker().onPlayerQuit(event);
        plugin.onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.isCancelled()) return;
        plugin.getBehaviorTracker().onPlayerInteract(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (event.isCancelled()) return;
        plugin.getBehaviorTracker().onPlayerAnimation(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;
        plugin.getBehaviorTracker().onPlayerMove(event);

        if (event.getPlayer().hasPermission("anticheat.bypass")) return;

        if (plugin.getConfig().getBoolean("behavior-detection.enabled", true)) {
            plugin.getBehaviorTracker().checkWalkStayRatio(event.getPlayer().getUniqueId());
            plugin.getBehaviorTracker().checkInterfaceActions(event.getPlayer().getUniqueId());
        }
    }
}
