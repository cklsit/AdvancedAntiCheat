package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class PlayerMoveListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerMoveListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("anticheat.bypass")) {
            return;
        }

        plugin.getDetectionManager().getDetection("fly").check(player);
        plugin.getDetectionManager().getDetection("speed").check(player);
    }
}