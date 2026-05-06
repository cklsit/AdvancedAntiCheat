package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ReachDetection extends Detection {

    private static final double MAX_REACH = 4.5;

    public ReachDetection(DetectionManager manager) {
        super(manager);
    }

    @Override
    public void check(Player player) {
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("reach")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.reach")) {
            return;
        }
    }

    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("reach")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.reach")) {
            return;
        }

        Entity target = event.getEntity();
        double distance = player.getLocation().distance(target.getLocation());

        if (distance > MAX_REACH) {
            getManager().addViolation(player, "reach");
        }
    }
}