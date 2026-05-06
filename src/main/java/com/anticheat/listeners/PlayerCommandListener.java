package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class PlayerCommandListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerCommandListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        
        if (plugin.getBanManager().isBanned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c您已被封禁，无法执行命令！");
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (player.hasPermission("anticheat.bypass")) {
            return;
        }

        plugin.getDetectionManager().getDetection("killaura").check(player);
        plugin.getDetectionManager().getDetection("reach").check(player);

        ((com.anticheat.detection.KillAuraDetection) plugin.getDetectionManager().getDetection("killaura")).onAttack(event);
        ((com.anticheat.detection.ReachDetection) plugin.getDetectionManager().getDetection("reach")).onAttack(event);
    }
}