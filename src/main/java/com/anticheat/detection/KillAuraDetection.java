package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class KillAuraDetection extends Detection {

    private final Map<UUID, Long> lastAttack = new HashMap<>();
    private final Map<UUID, Integer> attackCount = new HashMap<>();

    public KillAuraDetection(DetectionManager manager) {
        super(manager);
    }

    @Override
    public void check(Player player) {
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("killaura")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.killaura")) {
            return;
        }
    }

    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("killaura")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.killaura")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        if (lastAttack.containsKey(uuid)) {
            long timeSinceLastAttack = currentTime - lastAttack.get(uuid);

            if (timeSinceLastAttack < 100) {
                int count = attackCount.getOrDefault(uuid, 0) + 1;
                attackCount.put(uuid, count);

                if (count >= 15) {
                    getManager().addViolation(player, "killaura");
                    attackCount.put(uuid, 0);
                }
            } else {
                attackCount.put(uuid, 1);
            }
        }

        lastAttack.put(uuid, currentTime);
    }
}