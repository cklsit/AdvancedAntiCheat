package com.anticheat.profiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerProfile implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UUID playerUUID;
    private String playerName;

    private long firstSeen;
    private long lastSeen;
    private int totalPlayTime;

    private final List<Double> cpsHistory;
    private final List<Double> turnSpeedHistory;
    private final List<Double> jumpIntervalHistory;
    private final List<Double> interfaceActionHistory;
    private final List<Double> walkStayRatioHistory;

    private double cpsMean;
    private double cpsVariance;
    private double cpsStdDev;

    private double turnSpeedMean;
    private double turnSpeedVariance;
    private double turnSpeedStdDev;

    private double jumpIntervalMean;
    private double jumpIntervalVariance;
    private double jumpIntervalStdDev;

    private double interfaceActionMean;
    private double interfaceActionVariance;
    private double interfaceActionStdDev;

    private double walkStayRatioMean;
    private double walkStayRatioVariance;
    private double walkStayRatioStdDev;

    private int sampleCount;
    private static final int MAX_HISTORY_SIZE = 1000;
    private static final int MIN_SAMPLES_FOR_COMPARISON = 30;

    private IdentityFingerprint identity;
    private BehaviorFeatures behavior;
    private RiskHistory riskHistory;
    private AssociationGraph associations;
    private List<HourlySnapshot> hourlySnapshots;
    private List<KeyEvent> keyEvents;

    public PlayerProfile(UUID playerUUID, String playerName) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.firstSeen = System.currentTimeMillis();
        this.lastSeen = System.currentTimeMillis();
        this.totalPlayTime = 0;

        this.cpsHistory = new ArrayList<>();
        this.turnSpeedHistory = new ArrayList<>();
        this.jumpIntervalHistory = new ArrayList<>();
        this.interfaceActionHistory = new ArrayList<>();
        this.walkStayRatioHistory = new ArrayList<>();

        this.sampleCount = 0;

        this.identity = new IdentityFingerprint();
        this.behavior = new BehaviorFeatures();
        this.riskHistory = new RiskHistory();
        this.associations = new AssociationGraph();
        this.hourlySnapshots = new ArrayList<>();
        this.keyEvents = new ArrayList<>();
        
        this.identity.setCurrentName(playerName);
        this.identity.setFirstJoinTime(this.firstSeen);
    }

    public void updateName(String newName) {
        if (!this.playerName.equals(newName)) {
            this.identity.addHistoricalName(this.playerName);
            this.playerName = newName;
            this.identity.setCurrentName(newName);
        }
    }

    public void addPlayTime(int seconds) {
        this.totalPlayTime += seconds;
        this.identity.setTotalPlayTime(this.totalPlayTime);
    }

    public void addViolation(String rule, int severity, String penalty, boolean falsePositive, String executor) {
        this.riskHistory.addViolation(rule, severity, penalty, falsePositive, executor);
    }

    public void addCaptchaTrial(String reason, boolean passed) {
        this.riskHistory.addCaptchaTrial(reason, passed);
    }

    public void decayRiskScore() {
        this.riskHistory.decayRiskScore();
    }

    public double getRiskScore() {
        return this.riskHistory.getRiskScore();
    }

    public void updateCPS(double cps) {
        addToHistory(cpsHistory, cps);
        updateStatistics(cpsHistory, v -> {
            this.cpsMean = v[0];
            this.cpsVariance = v[1];
            this.cpsStdDev = v[2];
        });
    }

    public void updateTurnSpeed(double turnSpeed) {
        addToHistory(turnSpeedHistory, turnSpeed);
        updateStatistics(turnSpeedHistory, v -> {
            this.turnSpeedMean = v[0];
            this.turnSpeedVariance = v[1];
            this.turnSpeedStdDev = v[2];
        });
    }

    public void updateJumpInterval(double interval) {
        addToHistory(jumpIntervalHistory, interval);
        updateStatistics(jumpIntervalHistory, v -> {
            this.jumpIntervalMean = v[0];
            this.jumpIntervalVariance = v[1];
            this.jumpIntervalStdDev = v[2];
        });
    }

    public void updateInterfaceAction(double actionsPerMinute) {
        addToHistory(interfaceActionHistory, actionsPerMinute);
        updateStatistics(interfaceActionHistory, v -> {
            this.interfaceActionMean = v[0];
            this.interfaceActionVariance = v[1];
            this.interfaceActionStdDev = v[2];
        });
    }

    public void updateWalkStayRatio(double ratio) {
        addToHistory(walkStayRatioHistory, ratio);
        updateStatistics(walkStayRatioHistory, v -> {
            this.walkStayRatioMean = v[0];
            this.walkStayRatioVariance = v[1];
            this.walkStayRatioStdDev = v[2];
        });
    }

    private void addToHistory(List<Double> history, double value) {
        synchronized (history) {
            if (history.size() >= MAX_HISTORY_SIZE) {
                history.remove(0);
            }
            history.add(value);
        }
    }

    private void updateStatistics(List<Double> history, StatisticsUpdater updater) {
        synchronized (history) {
            if (history.size() < 2) return;

            int n = history.size();
            double mean = 0;
            for (double v : history) {
                mean += v;
            }
            mean /= n;

            double variance = 0;
            for (double v : history) {
                variance += (v - mean) * (v - mean);
            }
            variance /= n;

            double stdDev = Math.sqrt(variance);

            updater.update(new double[]{mean, variance, stdDev});
            sampleCount++;
        }
    }

    public boolean hasEnoughSamples() {
        return cpsHistory.size() >= MIN_SAMPLES_FOR_COMPARISON;
    }

    public boolean isCPSAnomaly(double currentCPS) {
        if (!hasEnoughSamples() || cpsStdDev < 0.01) return false;
        double zScore = Math.abs(currentCPS - cpsMean) / cpsStdDev;
        return zScore > 3.0;
    }

    public boolean isTurnSpeedAnomaly(double currentTurnSpeed) {
        if (!hasEnoughSamples() || turnSpeedStdDev < 0.01) return false;
        double zScore = Math.abs(currentTurnSpeed - turnSpeedMean) / turnSpeedStdDev;
        return zScore > 3.0;
    }

    public boolean isJumpIntervalAnomaly(double currentInterval) {
        if (!hasEnoughSamples() || jumpIntervalStdDev < 0.01) return false;
        double zScore = Math.abs(currentInterval - jumpIntervalMean) / jumpIntervalStdDev;
        return zScore > 3.0;
    }

    public boolean isInterfaceActionAnomaly(double currentActions) {
        if (!hasEnoughSamples() || interfaceActionStdDev < 0.01) return false;
        double zScore = Math.abs(currentActions - interfaceActionMean) / interfaceActionStdDev;
        return zScore > 3.0;
    }

    public boolean isWalkStayRatioAnomaly(double currentRatio) {
        if (!hasEnoughSamples() || walkStayRatioStdDev < 0.01) return false;
        double zScore = Math.abs(currentRatio - walkStayRatioMean) / walkStayRatioStdDev;
        return zScore > 3.0;
    }

    public boolean detectBehaviorShift() {
        if (!hasEnoughSamples()) return false;

        int recentWindow = Math.min(50, cpsHistory.size() / 4);
        if (recentWindow < 10) return false;

        return isRecentBehaviorShift(cpsHistory, cpsMean, cpsStdDev) ||
               isRecentBehaviorShift(turnSpeedHistory, turnSpeedMean, turnSpeedStdDev) ||
               isRecentBehaviorShift(jumpIntervalHistory, jumpIntervalMean, jumpIntervalStdDev) ||
               isRecentBehaviorShift(interfaceActionHistory, interfaceActionMean, interfaceActionStdDev) ||
               isRecentBehaviorShift(walkStayRatioHistory, walkStayRatioMean, walkStayRatioStdDev);
    }

    private boolean isRecentBehaviorShift(List<Double> history, double overallMean, double overallStdDev) {
        if (overallStdDev < 0.01 || history.size() < 30) return false;

        int recentWindow = Math.min(50, history.size() / 4);
        double recentMean = 0;
        for (int i = history.size() - recentWindow; i < history.size(); i++) {
            recentMean += history.get(i);
        }
        recentMean /= recentWindow;

        double zScore = Math.abs(recentMean - overallMean) / overallStdDev;
        return zScore > 2.0;
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    public String getAnomalyReport(double currentCPS, double currentTurnSpeed, 
                                    double currentJumpInterval, double currentInterfaceActions,
                                    double currentWalkStayRatio) {
        StringBuilder report = new StringBuilder();
        report.append("§6§l=== 行为异常分析报告 ===\n");

        if (isCPSAnomaly(currentCPS)) {
            report.append("§c[CPS异常] ").append(String.format("当前: %.2f, 历史均值: %.2f, 标准差: %.2f\n", 
                currentCPS, cpsMean, cpsStdDev));
        }

        if (isTurnSpeedAnomaly(currentTurnSpeed)) {
            report.append("§c[转向速度异常] ").append(String.format("当前: %.2f, 历史均值: %.2f, 标准差: %.2f\n", 
                currentTurnSpeed, turnSpeedMean, turnSpeedStdDev));
        }

        if (isJumpIntervalAnomaly(currentJumpInterval)) {
            report.append("§c[跳跃间隔异常] ").append(String.format("当前: %.2f, 历史均值: %.2f, 标准差: %.2f\n", 
                currentJumpInterval, jumpIntervalMean, jumpIntervalStdDev));
        }

        if (isInterfaceActionAnomaly(currentInterfaceActions)) {
            report.append("§c[操作频率异常] ").append(String.format("当前: %.2f, 历史均值: %.2f, 标准差: %.2f\n", 
                currentInterfaceActions, interfaceActionMean, interfaceActionStdDev));
        }

        if (isWalkStayRatioAnomaly(currentWalkStayRatio)) {
            report.append("§c[移动习惯异常] ").append(String.format("当前: %.2f, 历史均值: %.2f, 标准差: %.2f\n", 
                currentWalkStayRatio, walkStayRatioMean, walkStayRatioStdDev));
        }

        if (detectBehaviorShift()) {
            report.append("§e[行为模式迁移] 检测到玩家行为发生统计显著性变化！\n");
        }

        if (report.length() == 0) {
            return null;
        }

        return report.toString();
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public int getTotalPlayTime() {
        return totalPlayTime;
    }

    public double getCpsMean() {
        return cpsMean;
    }

    public double getCpsStdDev() {
        return cpsStdDev;
    }

    public double getTurnSpeedMean() {
        return turnSpeedMean;
    }

    public double getTurnSpeedStdDev() {
        return turnSpeedStdDev;
    }

    public double getJumpIntervalMean() {
        return jumpIntervalMean;
    }

    public double getJumpIntervalStdDev() {
        return jumpIntervalStdDev;
    }

    public double getInterfaceActionMean() {
        return interfaceActionMean;
    }

    public double getInterfaceActionStdDev() {
        return interfaceActionStdDev;
    }

    public double getWalkStayRatioMean() {
        return walkStayRatioMean;
    }

    public double getWalkStayRatioStdDev() {
        return walkStayRatioStdDev;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public boolean hasEnoughData() {
        return cpsHistory.size() >= MIN_SAMPLES_FOR_COMPARISON;
    }

    public IdentityFingerprint getIdentity() { return identity; }
    public BehaviorFeatures getBehavior() { return behavior; }
    public RiskHistory getRiskHistory() { return riskHistory; }
    public AssociationGraph getAssociations() { return associations; }
    public List<HourlySnapshot> getHourlySnapshots() { return hourlySnapshots; }
    public List<KeyEvent> getKeyEvents() { return keyEvents; }

    public static class HourlySnapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        public long timestamp;
        public double riskPeak;
        public int violationCount;
        public String ruleStats;

        public HourlySnapshot(long timestamp, double riskPeak, int violationCount, String ruleStats) {
            this.timestamp = timestamp;
            this.riskPeak = riskPeak;
            this.violationCount = violationCount;
            this.ruleStats = ruleStats;
        }
    }

    public static class KeyEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        public long timestamp;
        public String eventType;
        public String data;

        public KeyEvent(long timestamp, String eventType, String data) {
            this.timestamp = timestamp;
            this.eventType = eventType;
            this.data = data;
        }
    }

    private interface StatisticsUpdater {
        void update(double[] values);
    }
}

