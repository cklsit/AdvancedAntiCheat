package com.anticheat.detection.fusion;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.managers.AuditManager;
import com.anticheat.managers.BanManager;
import com.anticheat.managers.ConfigManager;
import com.anticheat.managers.ReplayRecorder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumMap;
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

    // ==================== 处罚动作幂等门控 ====================
    // makeDecision() 每玩家每 100ms 调用一次 executeAction()，而处罚动作全是"重"操作：
    // 验证码会传送玩家+清空背包、封禁会踢人+写库、取证标记会向回放缓冲追加条目。
    // 这里按 (玩家, 档位) 记录最后一次"真正执行"的时间，用冷却窗口保证同一档位不重复执行。
    // 注意：只压"同档重复"，不压"档位升级"——每个档位有各自独立的名额。
    private static final long ACTION_COOLDOWN_MONITOR_MS = 60_000L;      // 监控：1min 刷新一次
    private static final long ACTION_COOLDOWN_CAPTCHA_MS = 120_000L;     // 验证码：2min 内不重复发起
    private static final long ACTION_COOLDOWN_TEMP_BAN_MS = 300_000L;    // 临时封禁：5min
    /** 永久封禁：同一玩家只执行一次（另有 BanManager.isBanned 兜底）。 */
    private static final long ACTION_COOLDOWN_PERM_BAN_MS = Long.MAX_VALUE;
    /** 门控表容量上限（LRU 淘汰），防止玩家长期累积导致内存增长。 */
    private static final int ACTION_SLOT_LRU_CAP = 5000;

    /** TEMP_BAN 档自动封禁时长兜底值（配置缺失时使用）。 */
    private static final String DEFAULT_AUTO_TEMP_BAN_TIME = "1h";

    /** (玩家, 档位) -> 最后一次真正执行处罚动作的时间戳。 */
    private final Map<UUID, Map<ActionLevel, Long>> lastActionExecutedAt;

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
        this.lastActionExecutedAt = Collections_Synchronized_LRU(ACTION_SLOT_LRU_CAP);
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

        // 非 CAPTCHA 档表示行为已回落，重置"连续验证码"计数。
        // CAPTCHA 档的累加改由 handleCaptchaAction 在"确实发起了一次验证码"时完成——
        // 本方法由 makeDecision 以 10Hz 调用，若在此无条件累加，玩家会在 300ms 内
        // 撞上 MAX_CONSECUTIVE_CAPTCHA 而被误升级为临时封禁。
        if (level != ActionLevel.CAPTCHA) {
            consecutiveActions.put(playerUUID, 0);
        }

        // 日志由各处罚动作在"确实执行"时自行记录（见 logAction）：
        // 此处统一记录会随 10Hz 检查循环刷屏。
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
        if (initiateCaptcha(player)) {
            // 只有"确实发起了一次验证码"才计入连续次数：发起受 acquireActionSlot 节流，
            // 所以 N 次意味着玩家在 N × 冷却期内持续处于 CAPTCHA 档仍未纠正行为。
            noteCaptchaIssued(player.getUniqueId());
        }
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
    
    /**
     * 是否为本档位保留一次"真正执行"的名额（幂等门控）。
     *
     * <p>纯逻辑、不触碰任何 Bukkit API，便于单元测试直接锁定幂等语义。
     * {@code nowMs} 由调用方注入，避免测试依赖真实时钟。</p>
     *
     * @return true 表示本次应真正执行该档位的处罚动作
     */
    boolean acquireActionSlot(UUID uuid, ActionLevel level, long nowMs) {
        Map<ActionLevel, Long> perLevel = lastActionExecutedAt.computeIfAbsent(
                uuid, k -> new EnumMap<>(ActionLevel.class));
        synchronized (perLevel) {
            Long last = perLevel.get(level);
            if (last != null && nowMs - last < actionCooldownMs(level)) {
                return false;
            }
            perLevel.put(level, nowMs);
            return true;
        }
    }

    /** 各档位处罚动作的执行冷却（毫秒）。 */
    static long actionCooldownMs(ActionLevel level) {
        switch (level) {
            case MONITOR:  return ACTION_COOLDOWN_MONITOR_MS;
            case CAPTCHA:  return ACTION_COOLDOWN_CAPTCHA_MS;
            case TEMP_BAN: return ACTION_COOLDOWN_TEMP_BAN_MS;
            case PERM_BAN: return ACTION_COOLDOWN_PERM_BAN_MS;
            default:       return Long.MAX_VALUE;
        }
    }

    /**
     * 把处罚动作调度到主线程执行。传送/踢人/广播等 Bukkit API 只能在主线程调用，
     * 而 executeAction 的调用方 {@code makeDecision} 跑在异步线程池里。
     *
     * <p>plugin 为 null（单元测试）或插件已卸载时直接放弃，不做任何 Bukkit 调用。</p>
     */
    private void runOnMainThread(Runnable action) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /** TEMP_BAN 档的自动封禁时长，读 ban.autoTempBanTime。 */
    private String getAutoTempBanTime() {
        ConfigManager cm = plugin != null ? plugin.getConfigManager() : null;
        if (cm == null) {
            return DEFAULT_AUTO_TEMP_BAN_TIME;
        }
        return cm.getAutoTempBanTime(DEFAULT_AUTO_TEMP_BAN_TIME);
    }

    /**
     * 增强监控：把融合判决作为一次违规事件交给回放系统取证，并留审计痕迹。
     *
     * <p>{@link ReplayRecorder#recordViolation} 内部自行调度主线程（切过肩机位取证 +
     * 回放队列插队），对"未被录制"的玩家是 no-op。每次调用都会向回放缓冲追加一个违规标记，
     * 因此必须由门控保证不被 10Hz 的检查循环重复触发。</p>
     */
    private void startEnhancedMonitoring(Player player) {
        if (plugin == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!acquireActionSlot(uuid, ActionLevel.MONITOR, System.currentTimeMillis())) {
            return;
        }

        ReplayRecorder recorder = plugin.getReplayRecorder();
        if (recorder != null) {
            try {
                recorder.recordViolation(uuid, "RCP_MONITOR", "MEDIUM");
            } catch (Throwable t) {
                plugin.getLogger().warning("[DecisionActionCenter] 回放取证标记失败: " + t.getMessage());
            }
        }

        logAction(uuid, ActionLevel.MONITOR,
                "已标记违规并提升监控（RCP=" + String.format("%.3f", getLatestRCP(uuid)) + "）");
    }

    /**
     * 验证码审判：接入 CaptchaManager；已在验证码流程中则不重复发起。
     *
     * @return true 表示本次占用了验证码执行名额（已提交主线程发起）
     */
    private boolean initiateCaptcha(Player player) {
        if (plugin == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!acquireActionSlot(uuid, ActionLevel.CAPTCHA, System.currentTimeMillis())) {
            return false;
        }

        runOnMainThread(() -> {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                return;
            }
            CaptchaManager captchaManager = plugin.getCaptchaManager();
            if (captchaManager == null) {
                plugin.getLogger().warning("[DecisionActionCenter] CaptchaManager 不可用，跳过验证码审判");
                return;
            }
            // startCaptcha 内部已保证"已有 session / 正在人工查端 / 已被封禁"三种情况不重复发起
            captchaManager.startCaptcha(target, CaptchaManager.Initiator.AUTO_DETECTION);
            logAction(uuid, ActionLevel.CAPTCHA, "已发起验证码审判");
        });
        return true;
    }

    /** 临时封禁：接入 BanManager，时长取 ban.autoTempBanTime（默认 1h）。 */
    private void applyTempBan(Player player) {
        if (plugin == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!acquireActionSlot(uuid, ActionLevel.TEMP_BAN, System.currentTimeMillis())) {
            return;
        }

        final String duration = getAutoTempBanTime();
        final String reason = "融合决策判定作弊（临时封禁）";

        runOnMainThread(() -> {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                return;
            }
            BanManager banManager = plugin.getBanManager();
            if (banManager == null) {
                plugin.getLogger().warning("[DecisionActionCenter] BanManager 不可用，跳过临时封禁");
                return;
            }
            if (banManager.isBanned(uuid)) {
                return; // 已有生效中的封禁：不重复写库、不重复踢人
            }
            banManager.banPlayer(uuid, target.getName(), duration, reason);
            logAction(uuid, ActionLevel.TEMP_BAN, "已执行临时封禁 " + duration);
        });
    }

    /** 永久封禁：接入 BanManager。 */
    private void applyPermBan(Player player) {
        if (plugin == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!acquireActionSlot(uuid, ActionLevel.PERM_BAN, System.currentTimeMillis())) {
            return;
        }

        final String reason = "融合决策判定作弊（永久封禁）";

        runOnMainThread(() -> {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                return;
            }
            BanManager banManager = plugin.getBanManager();
            if (banManager == null) {
                plugin.getLogger().warning("[DecisionActionCenter] BanManager 不可用，跳过永久封禁");
                return;
            }
            if (banManager.isBanned(uuid)) {
                return;
            }
            banManager.banPlayer(uuid, target.getName(), "permanent", reason);
            logAction(uuid, ActionLevel.PERM_BAN, "已执行永久封禁");
        });
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
        // 注意：不清 lastActionExecutedAt。处罚动作的执行冷却（尤其 PERM_BAN 的"只执行一次"）
        // 必须跨"退出-重进"保持，否则玩家重登即可重置门控、被重复封禁。该表有 LRU 上限，不会泄漏。
    }
    
    /**
     * 处罚动作的统一日志出口。
     *
     * <p>只由"确实执行了处罚动作"的调用点触发（各动作内部受 acquireActionSlot 节流），
     * 因此不会随 10Hz 检查循环刷屏。INFO 级日志用于运维追溯"谁在什么时候被如何处置"，
     * 同时写一条审计记录，供 Web 面板按 {@code anticheat_action} 类型查询。</p>
     */
    private void logAction(UUID playerUUID, ActionLevel level, String message) {
        Player player = Bukkit.getPlayer(playerUUID);
        String name = player != null ? player.getName() : String.valueOf(playerUUID);
        plugin.getLogger().info("[DecisionAction] " + level.getDescription() + " -> " + name + "：" + message);

        AuditManager audit = plugin.getAuditManager();
        if (audit != null) {
            audit.log("AntiCheat", 0, "anticheat_action", name, null, "warning",
                    level.getDescription() + "：" + message);
        }
    }
    
    public int getConsecutiveActionCount(UUID playerUUID) {
        return consecutiveActions.getOrDefault(playerUUID, 0);
    }

    /**
     * 记录一次"确实发起了验证码"，用于连续次数累加。
     *
     * <p>累加必须发生在真实发起点，不能放在 {@link #executeAction} 里：后者由
     * {@code makeDecision} 以 10Hz 调用，无条件累加会让玩家在 300ms 内撞上
     * {@link #MAX_CONSECUTIVE_CAPTCHA} 被误升级为临时封禁。
     * 包级可见，供单元测试锁定该语义。</p>
     */
    void noteCaptchaIssued(UUID playerUUID) {
        consecutiveActions.merge(playerUUID, 1, Integer::sum);
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
