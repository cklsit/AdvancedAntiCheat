package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import com.anticheat.utils.VersionUtil;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FlyDetection extends Detection {

    private final Map<UUID, Double> previousY = new HashMap<>();
    private final Map<UUID, Integer> flyTicks = new HashMap<>();

    public FlyDetection(DetectionManager manager) {
        super(manager);
    }

    @Override
    public void check(Player player) {
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("fly")) {
            return;
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || 
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR ||
            player.isFlying() || 
            player.hasPermission("anticheat.bypass.fly")) {
            return;
        }

        if (getManager().getPlugin().getCheckClientManager().isBeingChecked(player.getUniqueId())) {
            return;
        }

        UUID uuid = player.getUniqueId();
        double currentY = player.getLocation().getY();
        Vector velocity = player.getVelocity();

        Integer ticks = flyTicks.getOrDefault(uuid, 0);

        if (!player.isOnGround() && !isInWater(player) && !isInLava(player)) {
            if (velocity.getY() > 0.05 || (previousY.containsKey(uuid) && currentY > previousY.get(uuid) + 0.1)) {
                ticks++;
            }
        } else {
            ticks = Math.max(0, ticks - 1);
        }

        flyTicks.put(uuid, ticks);
        previousY.put(uuid, currentY);

        if (ticks >= 20) {
            getManager().addViolation(player, "fly");
            flyTicks.put(uuid, 0);
        }
    }

    private boolean isInWater(Player player) {
        if (VersionUtil.isHighVersion()) {
            return player.isInWater();
        } else {
            Location loc = player.getLocation();
            Material material = loc.getBlock().getType();
            return material == Material.WATER || isStationaryWater(material);
        }
    }

    private boolean isStationaryWater(Material material) {
        try {
            Material stationaryWater = (Material) Material.class.getDeclaredField("STATIONARY_WATER").get(null);
            return material == stationaryWater;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInLava(Player player) {
        if (VersionUtil.isHighVersion()) {
            return player.isInLava();
        } else {
            Location loc = player.getLocation();
            Material material = loc.getBlock().getType();
            return material == Material.LAVA || isStationaryLava(material);
        }
    }

    private boolean isStationaryLava(Material material) {
        try {
            Material stationaryLava = (Material) Material.class.getDeclaredField("STATIONARY_LAVA").get(null);
            return material == stationaryLava;
        } catch (Exception e) {
            return false;
        }
    }
}
