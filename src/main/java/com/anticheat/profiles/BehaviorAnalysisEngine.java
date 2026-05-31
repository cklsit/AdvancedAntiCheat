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
        globalBaselines.put("cps_mean", 8.0);
        globalBaselines.put("cps_std", 3.0);
        globalBaselines.put("turn_speed_mean", 5.0);
        globalBaselines.put("turn_speed_std", 2.0);
        globalBaselines.put("move_efficiency_mean", 0.7);
        globalBaselines.put("move_efficiency_std", 0.2);
        globalBaselines.put("block_place_rate_mean", 2.0);
        globalBaselines.put("block_place_rate_std", 1.0);
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
                totalAnomalyScore += Math.abs(cpsZScore) / Z_SCORE_THRESHOLD;
                anomalyCount++;
            }
        }

        if (metrics.turnSpeedSamples.size() >= MIN_SAMPLES) {
            double turnZScore = calculateZScore(metrics.currentTurnSpeed,
                globalBaselines.get("turn_speed_mean"),
                globalBaselines.get("turn_speed_std"));
            if (Math.abs(turnZScore) > Z_SCORE_THRESHOLD) {
                totalAnomalyScore += Math.abs(turnZScore) / Z_SCORE_THRESHOLD;
                anomalyCount++;
            }
        }

        if (profile.detectBehaviorShift()) {
            totalAnomalyScore += 0.3;
            anomalyCount++;
        }

        if (metrics.currentMoveEfficiency > 0.95) {
            totalAnomalyScore += 0.2;
            anomalyCount++;
        }

        if (metrics.currentMoveEfficiency < 0.3) {
            totalAnomalyScore += 0.2;
            anomalyCount++;
        }

        if (anomalyCount > 0) {
            metrics.anomalyScore = Math.min(1.0, totalAnomalyScore / anomalyCount);
        } else {
            metrics.anomalyScore = 0.0;
        }

        if (metrics.anomalyScore >= ANOMALY_THRESHOLD) {
            handleAnomalyDetected(player, metrics);
        }
    }

    private double calculateZScore(double value, double mean, double stdDev) {
        if (stdDev < 0.01) {
            return 0.0;
        }
        return (value - mean) / stdDev;
    }

    private void handleAnomalyDetected(Player player, BehaviorMetrics metrics) {
        plugin.getLogger().info("[BehaviorAnalysis] 检测到行为异常: " + player.getName() +
            " 异常分数: " + String.format("%.2f", metrics.anomalyScore));

        PlayerProfile profile = profileManager.getProfile(player.getUniqueId());
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
    }

    private void performPeriodicAnalysis() {
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
