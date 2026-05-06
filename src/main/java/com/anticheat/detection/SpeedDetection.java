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

        UUID uuid = player.getUniqueId();
        Location currentLoc = player.getLocation();
        long currentTime = System.currentTimeMillis();

        if (previousLocation.containsKey(uuid) && previousTime.containsKey(uuid)) {
            Location prevLoc = previousLocation.get(uuid);
            long prevTime = previousTime.get(uuid);

            double distance = currentLoc.distance(prevLoc);
            long timeDiff = currentTime - prevTime;

            if (timeDiff > 0) {
                double speed = distance / (timeDiff / 1000.0);

                double maxSpeed = player.isSprinting() ? 6.0 : 4.3;
                if (player.isFlying()) {
                    maxSpeed = 10.0;
                }

                if (speed > maxSpeed * 1.5) {
                    getManager().addViolation(player, "speed");
                }
            }
        }

        previousLocation.put(uuid, currentLoc);
        previousTime.put(uuid, currentTime);
    }
}