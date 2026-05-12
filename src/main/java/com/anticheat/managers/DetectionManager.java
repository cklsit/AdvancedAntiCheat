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
    private ViolationManager violationManager;

    public DetectionManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.violationManager = new ViolationManager(plugin);
        initializeDetections();
    }

    private void initializeDetections() {
        detections.put("fly", new FlyDetection(this));
        detections.put("speed", new SpeedDetection(this));
        detections.put("esp", new EspDetection(this));
        detections.put("killaura", new KillAuraDetection(this));
        detections.put("reach", new ReachDetection(this));
        detections.put("scaffold", new ScaffoldDetection(this));
        detections.put("fastbreak", new FastBreakDetection(this));
        detections.put("noslow", new NoSlowDetection(this));
    }

    public Detection getDetection(String type) {
        return detections.get(type.toLowerCase());
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public void addViolation(Player player, String type) {
        UUID uuid = player.getUniqueId();
        violations.computeIfAbsent(uuid, k -> new HashMap<String, Integer>());

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
        return violations.getOrDefault(uuid, new HashMap<String, Integer>()).getOrDefault(type, 0);
    }

    public void clearViolations(UUID uuid) {
        violations.remove(uuid);
    }

    public KillAuraDetection getKillAuraDetection() {
        return (KillAuraDetection) detections.get("killaura");
    }

    public ReachDetection getReachDetection() {
        return (ReachDetection) detections.get("reach");
    }

    public ScaffoldDetection getScaffoldDetection() {
        return (ScaffoldDetection) detections.get("scaffold");
    }

    public FastBreakDetection getFastBreakDetection() {
        return (FastBreakDetection) detections.get("fastbreak");
    }

    public NoSlowDetection getNoSlowDetection() {
        return (NoSlowDetection) detections.get("noslow");
    }

    private String getDetectionName(String type) {
        String lowerType = type.toLowerCase();
        switch (lowerType) {
            case "fly":
                return "飞行作弊";
            case "speed":
                return "速度作弊";
            case "esp":
                return "透视作弊";
            case "killaura":
                return "杀戮光环";
            case "reach":
                return "攻击距离作弊";
            case "scaffold":
                return "脚手架作弊";
            case "fastbreak":
                return "快速破坏";
            case "noslow":
                return "无减速挖掘";
            default:
                return type;
        }
    }

    public AdvancedAntiCheat getPlugin() {
        return plugin;
    }
}
