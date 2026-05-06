package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EspDetection extends Detection {

    private final Map<UUID, Integer> suspiciousClicks = new HashMap<>();

    public EspDetection(DetectionManager manager) {
        super(manager);
    }

    @Override
    public void check(Player player) {
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("esp")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.esp")) {
            return;
        }
    }

    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("esp")) {
            return;
        }

        if (player.hasPermission("anticheat.bypass.esp")) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (event.getClickedBlock() != null && event.getClickedBlock().getType().name().contains("CHEST")) {
                UUID uuid = player.getUniqueId();
                int clicks = suspiciousClicks.getOrDefault(uuid, 0) + 1;
                suspiciousClicks.put(uuid, clicks);

                if (clicks >= 10) {
                    getManager().addViolation(player, "esp");
                    suspiciousClicks.put(uuid, 0);
                }
            }
        }
    }
}