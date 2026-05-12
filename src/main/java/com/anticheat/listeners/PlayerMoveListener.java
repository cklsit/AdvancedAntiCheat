package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.FlyDetection;
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

        detectJump(event);

        plugin.getDetectionManager().getDetection("fly").check(player);
        plugin.getDetectionManager().getDetection("speed").check(player);
    }

    private void detectJump(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        boolean wasOnGround = event.getFrom().getBlock().getType().isSolid();
        boolean isOnGround = event.getTo().getBlock().getType().isSolid();
        
        double fromY = event.getFrom().getY();
        double toY = event.getTo().getY();
        
        if (wasOnGround && !isOnGround && toY > fromY + 0.3) {
            FlyDetection flyDetection = (FlyDetection) plugin.getDetectionManager().getDetection("fly");
            flyDetection.onPlayerJump(player);
        }
    }
}
