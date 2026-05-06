package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpeedDetection extends Detection {

    private final Map<UUID, Location> previousLocation = new HashMap<>();
    private final Map<UUID, Long> previousTime = new HashMap<>();
    private static final long MIN_TIME_INTERVAL = 500;
    private static final double WALK_SPEED = 4.3;
    private static final double SPRINT_SPEED = 6.0;
    private static final double FLY_SPEED = 10.0;
    private static final double WATER_SPEED = 2.2;
    private static final double SNEAK_SPEED = 1.3;

    public SpeedDetection(DetectionManager manager) {
        super(manager);
    }

    @Override
    public void check(Player player) {
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("speed")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.speed")) {
            return;
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || 
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        if (player.isDead()) {
            return;
        }

        if (player.isSleeping()) {
            return;
        }

        if (player.isInsideVehicle()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Location currentLoc = player.getLocation();
        long currentTime = System.currentTimeMillis();

        if (previousLocation.containsKey(uuid) && previousTime.containsKey(uuid)) {
            Location prevLoc = previousLocation.get(uuid);
            long prevTime = previousTime.get(uuid);

            long timeDiff = currentTime - prevTime;

            if (timeDiff < MIN_TIME_INTERVAL) {
                return;
            }

            double distance = currentLoc.distance(prevLoc);

            if (timeDiff > 0) {
                double speed = distance / (timeDiff / 1000.0);

                double maxSpeed = getMaxSpeed(player);
                double tolerance = getTolerance(player);

                if (speed > maxSpeed + tolerance) {
                    getManager().addViolation(player, "speed");
                }
            }
        }

        previousLocation.put(uuid, currentLoc);
        previousTime.put(uuid, currentTime);
    }

    private double getMaxSpeed(Player player) {
        if (player.isFlying()) {
            return FLY_SPEED;
        }

        if (player.isSprinting()) {
            return SPRINT_SPEED;
        }

        if (player.isSneaking()) {
            return SNEAK_SPEED;
        }

        if (player.isInWater()) {
            return WATER_SPEED;
        }

        return WALK_SPEED;
    }

    private double getTolerance(Player player) {
        if (player.isFlying()) {
            return 2.0;
        }

        if (player.isSprinting()) {
            return 1.5;
        }

        return 1.0;
    }
}