package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.captcha.tasks.TypeA_DirectInteraction;
import com.anticheat.captcha.tasks.TypeB_PrecisionHit;
import com.anticheat.captcha.tasks.TypeC_SequenceReplay;
import com.anticheat.captcha.tasks.TypeD_BlockMaze;
import com.anticheat.captcha.tasks.TypeE_Puzzle;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

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

        CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
        if (session != null) {
            Object currentTask = session.getCurrentTask();
            if (currentTask instanceof TypeD_BlockMaze) {
                ((TypeD_BlockMaze) currentTask).onPlayerMove(player, event.getTo());
            }
        }
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
            if (currentTask instanceof TypeC_SequenceReplay && event.isSneaking()) {
                ((TypeC_SequenceReplay) currentTask).onPlayerAction(player, TypeC_SequenceReplay.Action.SNEAK);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
        if (session != null) {
            Object currentTask = session.getCurrentTask();
            if (currentTask instanceof TypeC_SequenceReplay && event.isSprinting()) {
                ((TypeC_SequenceReplay) currentTask).onPlayerAction(player, TypeC_SequenceReplay.Action.SPRINT);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
        if (session != null) {
            Object currentTask = session.getCurrentTask();
            
            if (currentTask instanceof TypeB_PrecisionHit) {
                return;
            }
            
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && currentTask instanceof TypeE_Puzzle) {
                ((TypeE_Puzzle) currentTask).onButtonPress(player, event.getClickedBlock().getLocation());
            }
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();

        if (!(projectile.getShooter() instanceof Player)) {
            return;
        }

        Player player = (Player) projectile.getShooter();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        if (event.getHitEntity() != null && projectile instanceof Snowball) {
            CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
            if (session != null) {
                Object currentTask = session.getCurrentTask();
                if (currentTask instanceof TypeB_PrecisionHit) {
                    ((TypeB_PrecisionHit) currentTask).onSnowballHit((Snowball) projectile, event.getHitEntity());
                }
            }
        }
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
