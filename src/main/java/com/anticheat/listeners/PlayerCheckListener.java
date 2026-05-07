package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.CheckClientManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

public class PlayerCheckListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerCheckListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            event.setTo(from);
        }

        if (from.getY() != to.getY()) {
            Location newLoc = from.clone();
            newLoc.setY(to.getY());
            event.setTo(newLoc);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(Component.text("§c您正在被管理员查端，无法传送！", NamedTextColor.RED));
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
        player.sendMessage(Component.text("§c您正在被管理员查端，无法进行此操作！", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(victim.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        victim.sendMessage(Component.text("§c您正在被管理员查端，受到保护！", NamedTextColor.YELLOW));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(attacker.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        attacker.setVelocity(new Vector(0, 0, 0));
        attacker.sendMessage(Component.text("§c您正在被管理员查端，无法进行攻击！", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (checkManager.isBeingChecked(player.getUniqueId())) {
            checkManager.forceUnfreeze(player);
        }
    }
}
