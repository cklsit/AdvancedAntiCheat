package com.anticheat.detection.fusion;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class DecisionActionCenter {

    // ==================== 玩家通知节流（防止聊天刷屏） ====================
    // notifyType -> (playerUUID -> lastSentEpochMs). 小容量 LRU + 线程安全。
    private static final Map<String, Map<UUID, Long>> NOTIFY_LAST_SENT = new ConcurrentHashMap<>();
    // 每种通知类型默认重复间隔（毫秒）。实际值优先读 config.yml 的 notify.*（秒）。
    // 语义：玩家"持续处于同一档位"时，同一句提示最多多久重复一次；级别升高（升级）时立即提示。
    // 0 表示"只提示一次，之后不再重复"——这是 NORMAL / MONITOR 的默认行为（这两档提示纯噪声）。
    private static final long DEFAULT_COOLDOWN_NORMAL_MS = 0L;
    private static final long DEFAULT_COOLDOWN_MONITOR_MS = 0L;
    private static final long DEFAULT_COOLDOWN_CAPTCHA_MS = 60_000L;    // 验证码提示：1min
    private static final long DEFAULT_COOLDOWN_TEMP_BAN_MS = 300_000L;  // 临时封禁：5min
    private static final long DEFAULT_COOLDOWN_PERM_BAN_MS = 600_000L;  // 永久封禁：10min
    // 兜底节流下限（毫秒）：任何情况下同类型消息的绝对最小间隔，读 notify.throttleMs。
    private static final long DEFAULT_THROTTLE_FLOOR_MS = 5000L;
    private static final int NOTIFY_LRU_CAP = 5000;

    /**
     * 是否允许向玩家发送该类通知（节流判断）。若允许则自动更新最后发送时间。
     * @return true 表示应该发送，false 表示冷却中跳过。
     */
    private static synchronized boolean shouldSendNotify(String notifyType, UUID playerUuid, long cooldownMs) {
        Map<UUID, Long> bucket = NOTIFY_LAST_SENT.computeIfAbsent(notifyType,
                k -> Collections_Synchronized_LRU(NOTIFY_LRU_CAP));
        long now = System.currentTimeMillis();
        Long last = bucket.get(playerUuid);
        if (last != null && (now - last) < cooldownMs) {
            return false;
        }
        bucket.put(playerUuid, now);
        return true;
    }

    private static <K,V> Map<K,V> Collections_Synchronized_LRU(final int cap) {
        // 小工具：避免额外依赖 Guava Cache 时仍能简单节流（LinkedHashMap accessOrder + removeEldest）
        LinkedHashMap<K,V> lru = new LinkedHashMap<K,V>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) { return size() > cap; }
            private static final long serialVersionUID = 1L;
        };
        return java.util.Collections.synchronizedMap(lru);
    }

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
    /** 上次向该玩家发出提示时所处的动作档位，用于"级别升级才立即再提示"。 */
    private final Map<UUID, ActionLevel> lastNotifiedLevel;

    private final AdvancedAntiCheat plugin;

    private static final int MAX_CONSECUTIVE_CAPTCHA = 3;
    private static final long ACTION_COOLDOWN_MS = 60000;
    
    public DecisionActionCenter() {
        this(null);
    }

    public DecisionActionCenter(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.currentActions = new ConcurrentHashMap<>();
        this.actionTimestamps = new ConcurrentHashMap<>();
        this.consecutiveActions = new ConcurrentHashMap<>();
        this.historicalRCP = new ConcurrentHashMap<>();
        this.lastNotifiedLevel = new ConcurrentHashMap<>();
    }

    // ==================== 通知门控（防刷屏的核心） ====================

    /** 读 config.yml 的 notify.throttleMs 作为所有提示的绝对最小间隔。 */
    private long throttleFloorMs() {
        ConfigManager cm = plugin != null ? plugin.getConfigManager() : null;
        if (cm == null) {
            return DEFAULT_THROTTLE_FLOOR_MS;
        }
        long v = cm.getGlobalNotifyThrottleMs(DEFAULT_THROTTLE_FLOOR_MS);
        return v < 0 ? DEFAULT_THROTTLE_FLOOR_MS : Math.max(1000L, v);
    }

    /** 某档位的重复提示间隔。config 里配 0/负数表示"只提示一次，之后不再重复"。 */
    private long repeatIntervalMs(ActionLevel level) {
        ConfigManager cm = plugin != null ? plugin.getConfigManager() : null;
        String key;
        long def;
        switch (level) {
            case NORMAL:   key = "normalRepeatSecs";   def = DEFAULT_COOLDOWN_NORMAL_MS;   break;
            case MONITOR:  key = "monitorRepeatSecs";  def = DEFAULT_COOLDOWN_MONITOR_MS;  break;
            case CAPTCHA:  key = "captchaRepeatSecs";  def = DEFAULT_COOLDOWN_CAPTCHA_MS;  break;
            case TEMP_BAN: key = "tempBanRepeatSecs";  def = DEFAULT_COOLDOWN_TEMP_BAN_MS; break;
            default:       key = "banRepeatSecs";      def = DEFAULT_COOLDOWN_PERM_BAN_MS; break;
        }
        if (cm == null) {
            return def <= 0L ? Long.MAX_VALUE : def;
        }
        return cm.getNotifyRepeatMs(key, def);
    }

    /**
     * 是否允许发提示。规则：
     * <ul>
     *   <li>档位比上次提示更严重（升级 / 首次）→ 过兜底节流即可立即提示；</li>
     *   <li>档位未变（持续处于同一档）→ 必须等够该档位的重复间隔。</li>
     * </ul>
     * 注意：本方法只决定"要不要发聊天消息"，绝不影响实际处罚动作。
     */
    private boolean shouldNotify(Player player, ActionLevel level) {
        UUID uuid = player.getUniqueId();
        ActionLevel last = lastNotifiedLevel.get(uuid);
        boolean escalated = (last == null) || level.getSeverity() > last.getSeverity();
        long interval = escalated
                ? throttleFloorMs()
                : Math.max(throttleFloorMs(), repeatIntervalMs(level));
        if (!shouldSendNotify(level.name(), uuid, interval)) {
            return false;
        }
        lastNotifiedLevel.put(uuid, level);
        return true;
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
        if (!shouldNotify(player, ActionLevel.NORMAL)) return;
        player.sendMessage("§a[AntiCheat] §f您的行为正常，继续保持良好游戏体验！");
    }

    private void handleMonitorAction(Player player) {
        // 提示是否发送与监控是否开启解耦：即使冷却中跳过消息，监控逻辑也照常执行
        boolean notify = shouldNotify(player, ActionLevel.MONITOR);
        startEnhancedMonitoring(player);
        if (notify) {
            player.sendMessage("§e[AntiCheat] §f我们注意到您的一些异常行为，将增加对您的监控。");
        }
    }

    private void handleCaptchaAction(Player player) {
        // 即使聊天提示跳过，仍然要启动验证码（否则可以通过快速违规来回避验证码）
        boolean notify = shouldNotify(player, ActionLevel.CAPTCHA);
        initiateCaptcha(player);
        if (notify) {
            player.sendMessage("§6[AntiCheat] §f为了确认您的身份，请完成验证码测试。");
        }
    }

    private void handleTempBanAction(Player player) {
        // 封禁动作即便消息冷却也要执行（防止重复刷屏但不阻止封禁）
        boolean notify = shouldNotify(player, ActionLevel.TEMP_BAN);
        applyTempBan(player);
        if (notify) {
            player.sendMessage("§c[AntiCheat] §f检测到严重的作弊行为，您将被临时封禁。");
        }
    }

    private void handlePermBanAction(Player player) {
        boolean notify = shouldNotify(player, ActionLevel.PERM_BAN);
        applyPermBan(player);
        if (notify) {
            player.sendMessage("§4[AntiCheat] §f检测到持续或严重的作弊行为，您将被永久封禁。");
        }
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
        actionTimestamps.remove(playerUUID);
        lastNotifiedLevel.remove(playerUUID);
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
