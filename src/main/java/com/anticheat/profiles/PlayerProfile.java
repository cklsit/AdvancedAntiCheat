package com.anticheat.profiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    
    private final Map<String, NormalDistribution> baselines;
    private final Map<String, List<Double>> featureHistory;
    private static final double DEFAULT_Z_THRESHOLD = 3.0;

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
        this.baselines = new ConcurrentHashMap<>();
        this.featureHistory = new ConcurrentHashMap<>();
        
        this.identity.setCurrentName(playerName);
        this.identity.setFirstJoinTime(this.firstSeen);
    }

    public void updateName(String newName) {
        if (this.identity == null) {
            this.identity = new IdentityFingerprint();
            this.identity.setCurrentName(this.playerName);
        }
        if (!this.playerName.equals(newName)) {
            this.identity.addHistoricalName(this.playerName);
            this.playerName = newName;
            this.identity.setCurrentName(newName);
        }
    }

    public void addPlayTime(int seconds) {
        this.totalPlayTime += seconds;
        if (this.identity == null) {
            this.identity = new IdentityFingerprint();
        }
        this.identity.setTotalPlayTime(this.totalPlayTime);
    }

    public void addViolation(String rule, int severity, String penalty, boolean falsePositive, String executor) {
        ensureRiskHistoryInitialized();
        this.riskHistory.addViolation(rule, severity, penalty, falsePositive, executor);
    }

    public void addCaptchaTrial(String reason, boolean passed) {
        ensureRiskHistoryInitialized();
        this.riskHistory.addCaptchaTrial(reason, passed);
    }

    public void decayRiskScore() {
        ensureRiskHistoryInitialized();
        this.riskHistory.decayRiskScore();
    }

    public double getRiskScore() {
        ensureRiskHistoryInitialized();
        return this.riskHistory.getRiskScore();
    }

    private void ensureRiskHistoryInitialized() {
        if (this.riskHistory == null) {
            this.riskHistory = new RiskHistory();
        }
    }

    private void ensureAllInitialized() {
        if (this.identity == null) {
            this.identity = new IdentityFingerprint();
        }
        if (this.behavior == null) {
            this.behavior = new BehaviorFeatures();
        }
        if (this.riskHistory == null) {
            this.riskHistory = new RiskHistory();
        }
        if (this.associations == null) {
            this.associations = new AssociationGraph();
        }
        if (this.hourlySnapshots == null) {
            this.hourlySnapshots = new ArrayList<>();
        }
        if (this.keyEvents == null) {
            this.keyEvents = new ArrayList<>();
        }
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
    
    public void updateBaseline(String feature, double value) {
        NormalDistribution dist = baselines.computeIfAbsent(feature, k -> new NormalDistribution());
        dist.addSample(value);
        
        List<Double> history = featureHistory.computeIfAbsent(feature, k -> new ArrayList<>());
        synchronized (history) {
            if (history.size() >= MAX_HISTORY_SIZE) {
                history.remove(0);
            }
            history.add(value);
        }
    }
    
    public double calculateZScore(String feature, double currentValue) {
        NormalDistribution dist = baselines.get(feature);
        if (dist == null || !dist.hasEnoughSamples()) {
            return 0.0;
        }
        return dist.calculateZScore(currentValue);
    }
    
    public boolean isAnomaly(String feature, double value, double threshold) {
        NormalDistribution dist = baselines.get(feature);
        if (dist == null) {
            return false;
        }
        return dist.isOutlier(value, threshold);
    }
    
    public boolean isAnomaly(String feature, double value) {
        return isAnomaly(feature, value, DEFAULT_Z_THRESHOLD);
    }
    
    public NormalDistribution getBaseline(String feature) {
        return baselines.get(feature);
    }
    
    public void updateHorizontalSpeed(double speed) {
        updateBaseline("horizontalSpeed", speed);
    }
    
    public void updateVerticalSpeed(double speed) {
        updateBaseline("verticalSpeed", speed);
    }
    
    public void updateCPSBaseline(double cps) {
        updateBaseline("cps", cps);
    }
    
    public void updateJumpIntervalBaseline(double interval) {
        updateBaseline("jumpInterval", interval);
    }
    
    public void updateYawRate(double yawRate) {
        updateBaseline("yawRate", yawRate);
    }
    
    public void updatePitchRate(double pitchRate) {
        updateBaseline("pitchRate", pitchRate);
    }
    
    public void updateBlockBreakTime(long time) {
        updateBaseline("blockBreakTime", (double) time);
    }
    
    public void updateBlockPlaceTime(long time) {
        updateBaseline("blockPlaceTime", (double) time);
    }
    
    public void updateSwimFrequency(double frequency) {
        updateBaseline("swimFrequency", frequency);
    }
    
    public void updateFlyFrequency(double frequency) {
        updateBaseline("flyFrequency", frequency);
    }
    
    public void updateAttackDistance(double distance) {
        updateBaseline("attackDistance", distance);
    }
    
    public boolean isHorizontalSpeedAnomaly(double speed) {
        return isAnomaly("horizontalSpeed", speed);
    }
    
    public boolean isVerticalSpeedAnomaly(double speed) {
        return isAnomaly("verticalSpeed", speed);
    }
    
    public boolean isCPSBaselineAnomaly(double cps) {
        return isAnomaly("cps", cps);
    }
    
    public boolean isJumpIntervalBaselineAnomaly(double interval) {
        return isAnomaly("jumpInterval", interval);
    }
    
    public boolean isYawRateAnomaly(double yawRate) {
        return isAnomaly("yawRate", yawRate);
    }
    
    public boolean isPitchRateAnomaly(double pitchRate) {
        return isAnomaly("pitchRate", pitchRate);
    }
    
    public boolean isBlockBreakTimeAnomaly(long time) {
        return isAnomaly("blockBreakTime", (double) time);
    }
    
    public boolean isAttackDistanceAnomaly(double distance) {
        return isAnomaly("attackDistance", distance);
    }
    
    public Map<String, Double> getAllZScores(Map<String, Double> currentValues) {
        Map<String, Double> zScores = new HashMap<>();
        for (Map.Entry<String, Double> entry : currentValues.entrySet()) {
            double zScore = calculateZScore(entry.getKey(), entry.getValue());
            zScores.put(entry.getKey(), zScore);
        }
        return zScores;
    }
    
    public List<String> getAnomalousFeatures(Map<String, Double> currentValues, double threshold) {
        List<String> anomalies = new ArrayList<>();
        for (Map.Entry<String, Double> entry : currentValues.entrySet()) {
            if (isAnomaly(entry.getKey(), entry.getValue(), threshold)) {
                anomalies.add(entry.getKey());
            }
        }
        return anomalies;
    }
    
    public boolean hasBaseline(String feature) {
        NormalDistribution dist = baselines.get(feature);
        return dist != null && dist.hasEnoughSamples();
    }
    
    public int getBaselineSampleCount(String feature) {
        NormalDistribution dist = baselines.get(feature);
        return dist != null ? dist.getSampleCount() : 0;
    }
    
    public void clearBaselines() {
        baselines.clear();
        featureHistory.clear();
    }
    
    public Map<String, NormalDistribution> getBaselines() {
        return new HashMap<>(baselines);
    }

    public boolean hasEnoughData() {
        return cpsHistory.size() >= MIN_SAMPLES_FOR_COMPARISON;
    }

    public IdentityFingerprint getIdentity() { 
        if (this.identity == null) {
            this.identity = new IdentityFingerprint();
        }
        return identity; 
    }
    public BehaviorFeatures getBehavior() { 
        if (this.behavior == null) {
            this.behavior = new BehaviorFeatures();
        }
        return behavior; 
    }
    public RiskHistory getRiskHistory() { 
        ensureRiskHistoryInitialized();
        return riskHistory; 
    }
    public AssociationGraph getAssociations() { 
        if (this.associations == null) {
            this.associations = new AssociationGraph();
        }
        return associations; 
    }
    public List<HourlySnapshot> getHourlySnapshots() { 
        if (this.hourlySnapshots == null) {
            this.hourlySnapshots = new ArrayList<>();
        }
        return hourlySnapshots; 
    }
    public List<KeyEvent> getKeyEvents() { 
        if (this.keyEvents == null) {
            this.keyEvents = new ArrayList<>();
        }
        return keyEvents; 
    }

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

