package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.FlyDetection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerMoveListener implements Listener {

    private final AdvancedAntiCheat plugin;
    private final Map<String, Long> lastCheckTimes;
    
    private static final long CHECK_INTERVAL_MS = 50;
    private static final long FLY_CHECK_INTERVAL_MS = 100;
    private static final long SPEED_CHECK_INTERVAL_MS = 100;

    public PlayerMoveListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.lastCheckTimes = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("anticheat.bypass")) {
            return;
        }

        String playerId = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        
        Long lastCheck = lastCheckTimes.get(playerId);
        if (lastCheck != null && now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckTimes.put(playerId, now);

        if (now - lastCheckTimes.getOrDefault(playerId + "_fly", 0L) >= FLY_CHECK_INTERVAL_MS) {
            detectJump(event);
            plugin.getDetectionManager().getDetection("fly").check(player);
            lastCheckTimes.put(playerId + "_fly", now);
        }
        
        if (now - lastCheckTimes.getOrDefault(playerId + "_speed", 0L) >= SPEED_CHECK_INTERVAL_MS) {
            plugin.getDetectionManager().getDetection("speed").check(player);
            lastCheckTimes.put(playerId + "_speed", now);
        }
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
