package com.anticheat.detection.fusion;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class DecisionActionCenter {
    
    public enum ActionLevel {
        NORMAL(0.0, 0.5, "正常放行", 0),
        MONITOR(0.5, 0.75, "增加监控", 1),
        CAPTCHA(0.75, 0.95, "验证码审判", 2),
        TEMP_BAN(0.95, 0.995, "临时封禁", 3),
        PERM_BAN(0.995, 1.0, "永久封禁", 4);
        
        private final double minThreshold;
        private final double maxThreshold;
        private final String description;
        private final int severity;
        
        ActionLevel(double minThreshold, double maxThreshold, String description, int severity) {
            this.minThreshold = minThreshold;
            this.maxThreshold = maxThreshold;
            this.description = description;
            this.severity = severity;
        }
        
        public double getMinThreshold() {
            return minThreshold;
        }
        
        public double getMaxThreshold() {
            return maxThreshold;
        }
        
        public String getDescription() {
            return description;
        }
        
        public int getSeverity() {
            return severity;
        }
        
        public static ActionLevel fromRCP(double rcp) {
            for (ActionLevel level : values()) {
                if (rcp >= level.minThreshold && rcp < level.maxThreshold) {
                    return level;
                }
            }
            if (rcp >= 1.0) {
                return PERM_BAN;
            }
            return NORMAL;
        }
    }
    
    private final Map<UUID, ActionLevel> currentActions;
    private final Map<UUID, Long> actionTimestamps;
    private final Map<UUID, Integer> consecutiveActions;
    private final Map<UUID, Double> historicalRCP;
    
    private static final int MAX_CONSECUTIVE_CAPTCHA = 3;
    private static final long ACTION_COOLDOWN_MS = 60000;
    
    public DecisionActionCenter() {
        this.currentActions = new ConcurrentHashMap<>();
        this.actionTimestamps = new ConcurrentHashMap<>();
        this.consecutiveActions = new ConcurrentHashMap<>();
        this.historicalRCP = new ConcurrentHashMap<>();
    }
    
    public ActionLevel decide(UUID playerUUID, double rcp) {
        if (rcp < 0.0 || rcp > 1.0) {
            throw new IllegalArgumentException("RCP must be between 0.0 and 1.0");
        }
        
        ActionLevel previousAction = currentActions.get(playerUUID);
        ActionLevel newAction = ActionLevel.fromRCP(rcp);
        
        if (previousAction != null && previousAction.getSeverity() > newAction.getSeverity()) {
            Long lastTimestamp = actionTimestamps.get(playerUUID);
            if (lastTimestamp != null) {
                long timeSinceLastAction = System.currentTimeMillis() - lastTimestamp;
                if (timeSinceLastAction < ACTION_COOLDOWN_MS) {
                    return previousAction;
                }
            }
        }
        
        Integer consecutive = consecutiveActions.get(playerUUID);
        if (consecutive != null && consecutive >= MAX_CONSECUTIVE_CAPTCHA && newAction == ActionLevel.CAPTCHA) {
            return ActionLevel.TEMP_BAN;
        }
        
        currentActions.put(playerUUID, newAction);
        actionTimestamps.put(playerUUID, System.currentTimeMillis());
        
        updateHistoricalRCP(playerUUID, rcp);
        
        return newAction;
    }
    
    public void executeAction(UUID playerUUID, ActionLevel level) {
        Player player = Bukkit.getPlayer(playerUUID);
        
        if (player == null || !player.isOnline()) {
            logAction(playerUUID, level, "Player not online");
            return;
        }
        
        switch (level) {
            case NORMAL:
                handleNormalAction(player);
                break;
            case MONITOR:
                handleMonitorAction(player);
                break;
            case CAPTCHA:
                handleCaptchaAction(player);
                break;
            case TEMP_BAN:
                handleTempBanAction(player);
                break;
            case PERM_BAN:
                handlePermBanAction(player);
                break;
        }
        
        Integer consecutive = consecutiveActions.getOrDefault(playerUUID, 0);
        if (level == ActionLevel.CAPTCHA) {
            consecutiveActions.put(playerUUID, consecutive + 1);
        } else {
            consecutiveActions.put(playerUUID, 0);
        }
        
        logAction(playerUUID, level, "Action executed successfully");
    }
    
    private void handleNormalAction(Player player) {
        player.sendMessage("§a[AntiCheat] §f您的行为正常，继续保持良好游戏体验！");
    }
    
    private void handleMonitorAction(Player player) {
        player.sendMessage("§e[AntiCheat] §f我们注意到您的一些异常行为，将增加对您的监控。");
        startEnhancedMonitoring(player);
    }
    
    private void handleCaptchaAction(Player player) {
        player.sendMessage("§6[AntiCheat] §f为了确认您的身份，请完成验证码测试。");
        initiateCaptcha(player);
    }
    
    private void handleTempBanAction(Player player) {
        player.sendMessage("§c[AntiCheat] §f检测到严重的作弊行为，您将被临时封禁。");
        applyTempBan(player);
    }
    
    private void handlePermBanAction(Player player) {
        player.sendMessage("§4[AntiCheat] §f检测到持续或严重的作弊行为，您将被永久封禁。");
        applyPermBan(player);
    }
    
    private void startEnhancedMonitoring(Player player) {
        // Integration point with monitoring system
    }
    
    private void initiateCaptcha(Player player) {
        // Integration point with CaptchaManager
    }
    
    private void applyTempBan(Player player) {
        // Integration point with BanManager
        // Default: 1 hour temp ban
    }
    
    private void applyPermBan(Player player) {
        // Integration point with BanManager
    }
    
    public ActionLevel getCurrentAction(UUID playerUUID) {
        return currentActions.get(playerUUID);
    }
    
    public boolean shouldTakeAction(UUID playerUUID, double rcp) {
        ActionLevel action = ActionLevel.fromRCP(rcp);
        return action != ActionLevel.NORMAL;
    }
    
    public long getTimeSinceLastAction(UUID playerUUID) {
        Long timestamp = actionTimestamps.get(playerUUID);
        if (timestamp == null) {
            return -1;
        }
        return System.currentTimeMillis() - timestamp;
    }
    
    public boolean isOnCooldown(UUID playerUUID) {
        Long timestamp = actionTimestamps.get(playerUUID);
        if (timestamp == null) {
            return false;
        }
        return System.currentTimeMillis() - timestamp < ACTION_COOLDOWN_MS;
    }
    
    private void updateHistoricalRCP(UUID playerUUID, double rcp) {
        historicalRCP.put(playerUUID, rcp);
    }
    
    public double getLatestRCP(UUID playerUUID) {
        return historicalRCP.getOrDefault(playerUUID, 0.0);
    }
    
    public void clearPlayerAction(UUID playerUUID) {
        currentActions.remove(playerUUID);
        consecutiveActions.remove(playerUUID);
        historicalRCP.remove(playerUUID);
    }
    
    private void logAction(UUID playerUUID, ActionLevel level, String message) {
        // Logging implementation
    }
    
    public int getConsecutiveActionCount(UUID playerUUID) {
        return consecutiveActions.getOrDefault(playerUUID, 0);
    }
    
    public void resetConsecutiveActions(UUID playerUUID) {
        consecutiveActions.put(playerUUID, 0);
    }
    
    public Map<String, Object> getActionStatistics(UUID playerUUID) {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("currentAction", currentActions.get(playerUUID));
        stats.put("consecutiveCount", getConsecutiveActionCount(playerUUID));
        stats.put("latestRCP", getLatestRCP(playerUUID));
        stats.put("lastActionTime", actionTimestamps.get(playerUUID));
        return stats;
    }
    
    public boolean shouldEscalate(UUID playerUUID, double rcp) {
        ActionLevel current = getCurrentAction(playerUUID);
        ActionLevel potential = ActionLevel.fromRCP(rcp);
        
        if (current == null) {
            return potential != ActionLevel.NORMAL;
        }
        
        return potential.getSeverity() > current.getSeverity();
    }
}
