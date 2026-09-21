package com.anticheat.profiles;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ProfileManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BehaviorAnalysisEngine {

    private final AdvancedAntiCheat plugin;
    private final ProfileManager profileManager;
    private final Map<UUID, BehaviorMetrics> playerMetrics;
    private final Map<String, Double> globalBaselines;

    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final double ANOMALY_THRESHOLD = 0.6;
    private static final int MIN_SAMPLES = 30;
    private static final long ANALYSIS_INTERVAL_MS = 5000;

    // ==================== 全局基线自学习 ====================
    // 原实现把 cps_mean=8 / cps_std=3、turn_speed_mean=5 / std=2 写死，标准差过紧：
    // 任何 CPS>17 或转向幅度稍大的正常 PvP 玩家都会长期 z>3σ，异常分被钉死在 1.00。
    // 现改为从已加载的玩家画像估计总体分布（≥8 个画像才启用），并用更宽松的默认兜底。
    private static final int BASELINE_MIN_PROFILES = 8;
    private static final long BASELINE_REFRESH_INTERVAL_MS = 60_000L;
    private static final double DEFAULT_CPS_MEAN = 8.0;
    private static final double DEFAULT_CPS_STD = 6.0;
    private static final double DEFAULT_TURN_MEAN = 12.0;
    private static final double DEFAULT_TURN_STD = 8.0;
    private volatile long lastBaselineRefreshAt = 0L;

    // ==================== 异常控制台日志节流 ====================
    // analyzePlayer 被 100ms 的检测循环驱动，同一条异常每秒会打 ~10 行 INFO。
    private final Map<UUID, Long> lastAnomalyLogAt = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastAnomalyLogScore = new ConcurrentHashMap<>();
    private final Map<UUID, Long> anomalyClearSince = new ConcurrentHashMap<>();
    private static final long DEFAULT_ANOMALY_LOG_COOLDOWN_MS = 60_000L;
    private static final double ANOMALY_LOG_SCORE_STEP = 0.10;
    /** 连续低于阈值这么久后，才认为上一轮异常"结束"，下一次异常可立即打日志。 */
    private static final long ANOMALY_CLEAR_RESET_MS = 120_000L;

    private BukkitTask analysisTask;

    public BehaviorAnalysisEngine(AdvancedAntiCheat plugin, ProfileManager profileManager) {
        this.plugin = plugin;
        this.profileManager = profileManager;
        this.playerMetrics = new ConcurrentHashMap<>();
        this.globalBaselines = new ConcurrentHashMap<>();

        initializeGlobalBaselines();
        startAnalysisTask();
    }

    private void initializeGlobalBaselines() {
        globalBaselines.put("cps_mean", DEFAULT_CPS_MEAN);
        globalBaselines.put("cps_std", DEFAULT_CPS_STD);
        globalBaselines.put("turn_speed_mean", DEFAULT_TURN_MEAN);
        globalBaselines.put("turn_speed_std", DEFAULT_TURN_STD);
        globalBaselines.put("move_efficiency_mean", 0.7);
        globalBaselines.put("move_efficiency_std", 0.2);
        globalBaselines.put("block_place_rate_mean", 2.0);
        globalBaselines.put("block_place_rate_std", 1.0);
    }

    /**
     * 用已加载玩家画像的总体分布刷新全局基线（最多每分钟一次）。
     * 画像数不足时保留默认值，避免 1~2 人样本把 std 压成 0 导致 z 分爆炸。
     */
    private void refreshGlobalBaselinesIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastBaselineRefreshAt < BASELINE_REFRESH_INTERVAL_MS) {
            return;
        }
        lastBaselineRefreshAt = now;
        try {
            Map<UUID, PlayerProfile> profiles = profileManager.getCachedProfiles();
            if (profiles == null || profiles.isEmpty()) {
                return;
            }
            List<Double> cps = new ArrayList<>();
            List<Double> turn = new ArrayList<>();
            for (PlayerProfile p : profiles.values()) {
                if (p == null || p.getSampleCount() < MIN_SAMPLES) {
                    continue;
                }
                if (p.getCpsMean() > 0) cps.add(p.getCpsMean());
                if (p.getTurnSpeedMean() > 0) turn.add(p.getTurnSpeedMean());
            }
            if (cps.size() >= BASELINE_MIN_PROFILES) {
                applyBaseline("cps", cps);
            }
            if (turn.size() >= BASELINE_MIN_PROFILES) {
                applyBaseline("turn_speed", turn);
            }
        } catch (Throwable ignored) {
            // 基线刷新失败不应影响检测主流程
        }
    }

    private void applyBaseline(String metric, List<Double> values) {
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.size();

        double variance = 0.0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        variance /= values.size();

        // std 下限：样本过于集中时（同质化玩家群体）强制放宽，否则 z 分无意义地放大
        double std = Math.max(Math.sqrt(variance), Math.max(1.0, mean * 0.25));
        globalBaselines.put(metric + "_mean", mean);
        globalBaselines.put(metric + "_std", std);
    }

    private void startAnalysisTask() {
        analysisTask = new BukkitRunnable() {
            @Override
            public void run() {
                performPeriodicAnalysis();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 10, ANALYSIS_INTERVAL_MS / 50);
    }

    public void analyzePlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profileManager.getProfile(uuid);

        if (profile == null) {
            return;
        }

        BehaviorMetrics metrics = playerMetrics.computeIfAbsent(uuid, k -> new BehaviorMetrics());

        updateMetrics(player, profile, metrics);
        calculateAnomalyScore(player, profile, metrics);
    }

    private void updateMetrics(Player player, PlayerProfile profile, BehaviorMetrics metrics) {
        long now = System.currentTimeMillis();

        metrics.cpsSamples.put(now, profile.getCpsMean());
        metrics.turnSpeedSamples.put(now, profile.getTurnSpeedMean());
        metrics.jumpIntervalSamples.put(now, profile.getJumpIntervalMean());
        metrics.interfaceActionSamples.put(now, profile.getInterfaceActionMean());
        metrics.walkStayRatioSamples.put(now, profile.getWalkStayRatioMean());

        cleanupOldSamples(metrics);

        if (!metrics.cpsSamples.isEmpty()) {
            metrics.currentCPS = calculateAverage(metrics.cpsSamples);
        }

        if (!metrics.turnSpeedSamples.isEmpty()) {
            metrics.currentTurnSpeed = calculateAverage(metrics.turnSpeedSamples);
        }

        if (!metrics.walkStayRatioSamples.isEmpty()) {
            metrics.currentMoveEfficiency = calculateAverage(metrics.walkStayRatioSamples);
        }

        metrics.lastUpdate = now;
    }

    private void cleanupOldSamples(BehaviorMetrics metrics) {
        long cutoff = System.currentTimeMillis() - 60000;

        metrics.cpsSamples.entrySet().removeIf(e -> e.getKey() < cutoff);
        metrics.turnSpeedSamples.entrySet().removeIf(e -> e.getKey() < cutoff);
        metrics.jumpIntervalSamples.entrySet().removeIf(e -> e.getKey() < cutoff);
        metrics.interfaceActionSamples.entrySet().removeIf(e -> e.getKey() < cutoff);
        metrics.walkStayRatioSamples.entrySet().removeIf(e -> e.getKey() < cutoff);

        while (metrics.cpsSamples.size() > 100) {
            metrics.cpsSamples.remove(metrics.cpsSamples.firstKey());
        }
    }

    private double calculateAverage(Map<Long, Double> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Double value : samples.values()) {
            sum += value;
        }
        return sum / samples.size();
    }

    private void calculateAnomalyScore(Player player, PlayerProfile profile, BehaviorMetrics metrics) {
        double totalAnomalyScore = 0.0;
        int anomalyCount = 0;

        if (metrics.cpsSamples.size() >= MIN_SAMPLES) {
            double cpsZScore = calculateZScore(metrics.currentCPS,
                globalBaselines.get("cps_mean"),
                globalBaselines.get("cps_std"));
            if (Math.abs(cpsZScore) > Z_SCORE_THRESHOLD) {
                totalAnomalyScore += contribution(cpsZScore);
                anomalyCount++;
            }
        }

        if (metrics.turnSpeedSamples.size() >= MIN_SAMPLES) {
            double turnZScore = calculateZScore(metrics.currentTurnSpeed,
                globalBaselines.get("turn_speed_mean"),
                globalBaselines.get("turn_speed_std"));
            if (Math.abs(turnZScore) > Z_SCORE_THRESHOLD) {
                totalAnomalyScore += contribution(turnZScore);
                anomalyCount++;
            }
        }

        if (profile.detectBehaviorShift()) {
            totalAnomalyScore += 0.3;
            anomalyCount++;
        }

        // 已移除 walkStayRatio 的固定阈值(0.95/0.3)加分项：
        // 该比率由 walkTime/(walkTime+stayTime) 计算，活跃玩家的 walkTime 恒大于经过秒数，
        // 导致比率二值化为 1.0（动过）或 0.0（没动）—— 等于给"在走动"这个正常行为无条件 +0.2，
        // 是异常分长期偏高的直接原因之一。

        if (anomalyCount > 0) {
            double score = totalAnomalyScore / anomalyCount;
            if (anomalyCount < 2) {
                // 单一信号不足以判定异常：否则一个刚越过 3σ 的指标就能把分数拉满
                score *= 0.5;
            }
            metrics.anomalyScore = Math.min(1.0, score);
        } else {
            metrics.anomalyScore = 0.0;
        }

        if (metrics.anomalyScore >= ANOMALY_THRESHOLD) {
            handleAnomalyDetected(player, metrics);
        } else {
            trackAnomalyCleared(player.getUniqueId());
        }
    }

    /** 单项信号贡献封顶 1.0，避免单个超大 z 分独力把总分拉满。 */
    private double contribution(double zScore) {
        return Math.min(1.0, Math.abs(zScore) / Z_SCORE_THRESHOLD);
    }

    private double calculateZScore(double value, double mean, double stdDev) {
        if (stdDev < 0.01) {
            return 0.0;
        }
        return (value - mean) / stdDev;
    }

    private void handleAnomalyDetected(Player player, BehaviorMetrics metrics) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (!shouldLogAnomaly(uuid, metrics.anomalyScore, now)) {
            return;
        }

        plugin.getLogger().info("[BehaviorAnalysis] 检测到行为异常: " + player.getName() +
            " 异常分数: " + String.format("%.2f", metrics.anomalyScore));

        PlayerProfile profile = profileManager.getProfile(uuid);
        if (profile != null) {
            String report = profile.getAnomalyReport(
                metrics.currentCPS,
                metrics.currentTurnSpeed,
                0.0,
                0.0,
                metrics.currentMoveEfficiency
            );

            if (report != null) {
                plugin.getLogger().info("[BehaviorAnalysis] 异常报告:\n" + report);
            }
        }

        BehaviorTracker tracker = plugin.getBehaviorTracker();
        if (tracker != null) {
            String digest = tracker.getProfileDigest(uuid);
            if (digest != null) {
                plugin.getLogger().info("[BehaviorAnalysis] " + player.getName() + " " + digest);
            }
        }
    }

    /**
     * 控制台日志节流。分析以 10Hz 运行，同一条异常若不限制会每秒刷约 10 行 INFO。
     * 规则：首次异常立即输出；之后同一玩家最多每 {@code behavior.anomalyLogCooldownSecs}
     * 输出一次（配 0 表示只输出一次），除非分数比上次输出时又上升了
     * {@link #ANOMALY_LOG_SCORE_STEP} 以上。分数连续低于阈值一段时间后才重置状态，
     * 这样既不漏报新一轮异常，也不会因分数在阈值附近抖动而反复刷屏。
     */
    private boolean shouldLogAnomaly(UUID uuid, double score, long now) {
        anomalyClearSince.remove(uuid);

        long cooldown = anomalyLogCooldownMs();
        Long lastAt = lastAnomalyLogAt.get(uuid);
        if (lastAt == null) {
            lastAnomalyLogAt.put(uuid, now);
            lastAnomalyLogScore.put(uuid, score);
            return true;
        }

        Double lastScore = lastAnomalyLogScore.get(uuid);
        boolean risen = lastScore != null && (score - lastScore) >= ANOMALY_LOG_SCORE_STEP;
        if (!risen && (now - lastAt) < cooldown) {
            return false;
        }

        lastAnomalyLogAt.put(uuid, now);
        lastAnomalyLogScore.put(uuid, score);
        return true;
    }

    private void trackAnomalyCleared(UUID uuid) {
        Long since = anomalyClearSince.get(uuid);
        long now = System.currentTimeMillis();
        if (since == null) {
            anomalyClearSince.put(uuid, now);
            return;
        }
        if (now - since >= ANOMALY_CLEAR_RESET_MS) {
            anomalyClearSince.remove(uuid);
            lastAnomalyLogAt.remove(uuid);
            lastAnomalyLogScore.remove(uuid);
        }
    }

    private long anomalyLogCooldownMs() {
        if (plugin == null) {
            return DEFAULT_ANOMALY_LOG_COOLDOWN_MS;
        }
        long secs = plugin.getConfig().getLong("behavior.anomalyLogCooldownSecs",
                DEFAULT_ANOMALY_LOG_COOLDOWN_MS / 1000L);
        return secs <= 0 ? Long.MAX_VALUE : secs * 1000L;
    }

    private void performPeriodicAnalysis() {
        refreshGlobalBaselinesIfDue();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isOnline()) {
                analyzePlayer(player);
            }
        }
    }

    public double getAnomalyScore(Player player) {
        if (player == null) {
            return 0.0;
        }

        BehaviorMetrics metrics = playerMetrics.get(player.getUniqueId());
        return metrics != null ? metrics.anomalyScore : 0.0;
    }

    public BehaviorMetrics getPlayerMetrics(UUID uuid) {
        return playerMetrics.get(uuid);
    }

    public boolean isAnomalous(Player player) {
        return getAnomalyScore(player) >= ANOMALY_THRESHOLD;
    }

    public void clearPlayerData(UUID uuid) {
        playerMetrics.remove(uuid);
        lastAnomalyLogAt.remove(uuid);
        lastAnomalyLogScore.remove(uuid);
        anomalyClearSince.remove(uuid);
    }

    public void updateGlobalBaseline(String metric, double mean, double stdDev) {
        globalBaselines.put(metric + "_mean", mean);
        globalBaselines.put(metric + "_std", stdDev);
    }

    public Map<String, Double> getGlobalBaselines() {
        return new HashMap<>(globalBaselines);
    }

    public void shutdown() {
        if (analysisTask != null) {
            analysisTask.cancel();
        }
        playerMetrics.clear();
        lastAnomalyLogAt.clear();
        lastAnomalyLogScore.clear();
        anomalyClearSince.clear();
    }

    public static class BehaviorMetrics {
        final ConcurrentSkipListMap<Long, Double> cpsSamples = new ConcurrentSkipListMap<>();
        final ConcurrentSkipListMap<Long, Double> turnSpeedSamples = new ConcurrentSkipListMap<>();
        final ConcurrentSkipListMap<Long, Double> jumpIntervalSamples = new ConcurrentSkipListMap<>();
        final ConcurrentSkipListMap<Long, Double> interfaceActionSamples = new ConcurrentSkipListMap<>();
        final ConcurrentSkipListMap<Long, Double> walkStayRatioSamples = new ConcurrentSkipListMap<>();

        volatile double currentCPS = 0.0;
        volatile double currentTurnSpeed = 0.0;
        volatile double currentMoveEfficiency = 0.0;
        volatile double anomalyScore = 0.0;

        volatile long lastUpdate = System.currentTimeMillis();
    }
}
