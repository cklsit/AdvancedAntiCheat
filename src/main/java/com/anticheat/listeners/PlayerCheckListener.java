package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.CheckClientManager;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;

public class PlayerCheckListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerCheckListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getEntity();

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(victim.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player attacker = (Player) event.getDamager();

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(attacker.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (checkManager.isBeingChecked(player.getUniqueId())) {
            String ip = player.getAddress().getAddress().getHostAddress();
            checkManager.banOnQuit(player.getUniqueId(), player.getName(), ip);
        }
    }
}
