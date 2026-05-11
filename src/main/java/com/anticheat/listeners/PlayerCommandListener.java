package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class PlayerCommandListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerCommandListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();

        if (player.hasPermission("anticheat.bypass")) {
            return;
        }

        plugin.getDetectionManager().getDetection("killaura").check(player);
        plugin.getDetectionManager().getDetection("reach").check(player);
    }
}
