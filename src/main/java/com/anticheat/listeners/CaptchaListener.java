package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.captcha.tasks.TypeA_DirectInteraction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public class CaptchaListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public CaptchaListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (plugin.getCaptchaManager().isNewPlayerCaptchaEnabled()) {
            plugin.getCaptchaManager().startCaptcha(player, CaptchaManager.Initiator.NEW_PLAYER);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getCaptchaManager().onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.2f);
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
        if (session != null) {
            Object currentTask = session.getCurrentTask();
            if (currentTask instanceof TypeA_DirectInteraction) {
                ((TypeA_DirectInteraction) currentTask).onPlayerSneak(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (plugin.getCaptchaManager().isInCaptcha(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();

            if (plugin.getCaptchaManager().isInCaptcha(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        if (plugin.getCaptchaManager().isInCaptcha(player)) {
            event.setCancelled(true);
            player.setFlying(false);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            if (plugin.getCaptchaManager().isInCaptcha(player)) {
                event.setCancelled(true);
            }
        }
    }
}
