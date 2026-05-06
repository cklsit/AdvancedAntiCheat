package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.*;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DetectionManager {

    private final AdvancedAntiCheat plugin;
    private final Map<String, Detection> detections = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> violations = new HashMap<>();

    public DetectionManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        initializeDetections();
    }

    private void initializeDetections() {
        detections.put("fly", new FlyDetection(this));
        detections.put("speed", new SpeedDetection(this));
        detections.put("esp", new EspDetection(this));
        detections.put("killaura", new KillAuraDetection(this));
        detections.put("reach", new ReachDetection(this));
    }

    public Detection getDetection(String type) {
        return detections.get(type.toLowerCase());
    }

    public void addViolation(Player player, String type) {
        UUID uuid = player.getUniqueId();
        violations.computeIfAbsent(uuid, k -> new HashMap<>());

        Map<String, Integer> playerViolations = violations.get(uuid);
        int current = playerViolations.getOrDefault(type, 0) + 1;
        playerViolations.put(type, current);

        int maxViolations = plugin.getConfigManager().getMaxViolations(type);
        if (current >= maxViolations) {
            String banTime = plugin.getConfigManager().getBanTime(type);
            String reason = "检测到作弊: " + getDetectionName(type);
            plugin.getBanManager().banPlayer(uuid, player.getName(), banTime, reason);
            violations.remove(uuid);
        }
    }

    public int getViolations(Player player, String type) {
        UUID uuid = player.getUniqueId();
        return violations.getOrDefault(uuid, new HashMap<>()).getOrDefault(type, 0);
    }

    public void clearViolations(UUID uuid) {
        violations.remove(uuid);
    }

    private String getDetectionName(String type) {
        return switch (type.toLowerCase()) {
            case "fly" -> "飞行作弊";
            case "speed" -> "速度作弊";
            case "esp" -> "透视作弊";
            case "killaura" -> "杀戮光环";
            case "reach" -> "攻击距离作弊";
            default -> type;
        };
    }

    public AdvancedAntiCheat getPlugin() {
        return plugin;
    }
}