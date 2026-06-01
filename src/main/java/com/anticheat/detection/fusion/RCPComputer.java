package com.anticheat.detection.fusion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RCPComputer {
    
    private final ProbabilityFusionEngine fusionEngine;
    private final AdaptiveLearningSystem learningSystem;
    
    private final Map<UUID, List<Double>> playerRCPHistory;
    private final Map<UUID, Double> playerPriorProbabilities;
    private final Map<UUID, Long> playerLastUpdate;
    
    private static final int HISTORY_SIZE = 20;
    private static final long UPDATE_INTERVAL_MS = 1000;
    private static final double TREND_THRESHOLD = 0.1;
    private static final double PRIOR_WEIGHT = 0.2;
    private static final double NETWORK_WEIGHT = 0.15;
    private static final double TREND_WEIGHT = 0.1;
    
    private static final double LOW_LATENCY_THRESHOLD = 50.0;
    private static final double MEDIUM_LATENCY_THRESHOLD = 150.0;
    private static final double HIGH_LATENCY_THRESHOLD = 300.0;
    
    public RCPComputer(ProbabilityFusionEngine fusionEngine, AdaptiveLearningSystem learningSystem) {
        this.fusionEngine = fusionEngine;
        this.learningSystem = learningSystem;
        this.playerRCPHistory = new ConcurrentHashMap<>();
        this.playerPriorProbabilities = new ConcurrentHashMap<>();
        this.playerLastUpdate = new ConcurrentHashMap<>();
        initializeDefaultPriors();
    }
    
    private void initializeDefaultPriors() {
        // ConcurrentHashMap不允许null key，使用getOrDefault处理默认值
    }
    
    public double computeRCP(UUID playerUUID) {
        Long lastUpdate = playerLastUpdate.get(playerUUID);
        if (lastUpdate != null) {
            long timeSinceUpdate = System.currentTimeMillis() - lastUpdate;
            if (timeSinceUpdate < UPDATE_INTERVAL_MS) {
                Double cached = getLatestRCPFromHistory(playerUUID);
                if (cached != null) {
                    return cached;
                }
            }
        }
        
        double baseRCP = fusionEngine.getRCP(playerUUID);
        
        double rcpWithPrior = applyPriorProbability(baseRCP, playerUUID);
        
        double rcpWithNetwork = rcpWithPrior;
        
        double finalRCP = applyTrendAnalysis(rcpWithNetwork, playerUUID);
        
        addToHistory(playerUUID, finalRCP);
        
        playerLastUpdate.put(playerUUID, System.currentTimeMillis());
        
        return clampRCP(finalRCP);
    }
    
    private Double getLatestRCPFromHistory(UUID playerUUID) {
        List<Double> history = playerRCPHistory.get(playerUUID);
        if (history == null || history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1);
    }
    
    public double applyPriorProbability(double baseRCP, UUID playerUUID) {
        double prior = playerPriorProbabilities.getOrDefault(playerUUID, 0.05);
        
        int feedbackCount = learningSystem.getPlayerFeedbackCount(playerUUID);
        if (feedbackCount > 0) {
            List<AdaptiveLearningSystem.FeedbackRecord> feedback = learningSystem.getPlayerFeedback(playerUUID);
            
            int confirmedCount = 0;
            for (AdaptiveLearningSystem.FeedbackRecord record : feedback) {
                if (record.confirmed) {
                    confirmedCount++;
                }
            }
            
            double confirmedRatio = (double) confirmedCount / feedbackCount;
            prior = Math.max(0.05, Math.min(0.5, confirmedRatio * 2));
            
            playerPriorProbabilities.put(playerUUID, prior);
        }
        
        double adjustedRCP = baseRCP * (1 - PRIOR_WEIGHT) + prior * PRIOR_WEIGHT;
        
        return clampRCP(adjustedRCP);
    }
    
    public double applyNetworkConditions(double baseRCP, double latency) {
        if (latency < 0) {
            return baseRCP;
        }
        
        double networkModifier = 1.0;
        
        if (latency < LOW_LATENCY_THRESHOLD) {
            networkModifier = 1.0;
        } else if (latency < MEDIUM_LATENCY_THRESHOLD) {
            double factor = (latency - LOW_LATENCY_THRESHOLD) / (MEDIUM_LATENCY_THRESHOLD - LOW_LATENCY_THRESHOLD);
            networkModifier = 1.0 + factor * 0.05;
        } else if (latency < HIGH_LATENCY_THRESHOLD) {
            double factor = (latency - MEDIUM_LATENCY_THRESHOLD) / (HIGH_LATENCY_THRESHOLD - MEDIUM_LATENCY_THRESHOLD);
            networkModifier = 1.05 + factor * 0.1;
        } else {
            networkModifier = 1.15;
        }
        
        double adjustedRCP = baseRCP * networkModifier * (1 - NETWORK_WEIGHT) + baseRCP * NETWORK_WEIGHT;
        
        return clampRCP(adjustedRCP);
    }
    
    private double applyTrendAnalysis(double currentRCP, UUID playerUUID) {
        if (!isIncreasingTrend(playerUUID)) {
            return currentRCP;
        }
        
        List<Double> history = playerRCPHistory.get(playerUUID);
        if (history == null || history.size() < 3) {
            return currentRCP;
        }
        
        double trendStrength = calculateTrendStrength(history);
        
        if (trendStrength > TREND_THRESHOLD) {
            double boost = trendStrength * TREND_WEIGHT * currentRCP;
            return clampRCP(currentRCP + boost);
        }
        
        return currentRCP;
    }
    
    public boolean isIncreasingTrend(UUID playerUUID) {
        List<Double> history = playerRCPHistory.get(playerUUID);
        
        if (history == null || history.size() < 5) {
            return false;
        }
        
        int recentWindow = Math.min(5, history.size());
        double recentSum = 0.0;
        double olderSum = 0.0;
        
        for (int i = 0; i < recentWindow; i++) {
            recentSum += history.get(history.size() - 1 - i);
        }
        recentSum /= recentWindow;
        
        int olderWindow = Math.min(5, history.size() - recentWindow);
        if (olderWindow > 0) {
            for (int i = 0; i < olderWindow; i++) {
                olderSum += history.get(history.size() - recentWindow - 1 - i);
            }
            olderSum /= olderWindow;
        } else {
            olderSum = recentSum;
        }
        
        return (recentSum - olderSum) > TREND_THRESHOLD;
    }
    
    private double calculateTrendStrength(List<Double> history) {
        if (history.size() < 3) {
            return 0.0;
        }
        
        int recentWindow = Math.min(5, history.size());
        double recentSum = 0.0;
        
        for (int i = 0; i < recentWindow; i++) {
            recentSum += history.get(history.size() - 1 - i);
        }
        recentSum /= recentWindow;
        
        double olderSum = 0.0;
        int olderWindow = Math.min(5, history.size() - recentWindow);
        
        if (olderWindow > 0) {
            for (int i = 0; i < olderWindow; i++) {
                olderSum += history.get(history.size() - recentWindow - 1 - i);
            }
            olderSum /= olderWindow;
        } else {
            olderSum = recentSum;
        }
        
        if (olderSum == 0) {
            return 0.0;
        }
        
        return (recentSum - olderSum) / olderSum;
    }
    
    private void addToHistory(UUID playerUUID, double rcp) {
        List<Double> history = playerRCPHistory.computeIfAbsent(playerUUID, k -> new ArrayList<>());
        
        synchronized (history) {
            if (history.size() >= HISTORY_SIZE) {
                history.remove(0);
            }
            history.add(rcp);
        }
    }
    
    public List<Double> getRCPHistory(UUID playerUUID) {
        List<Double> history = playerRCPHistory.get(playerUUID);
        return history != null ? new ArrayList<>(history) : new ArrayList<>();
    }
    
    public double getAverageRCP(UUID playerUUID) {
        List<Double> history = playerRCPHistory.get(playerUUID);
        if (history == null || history.isEmpty()) {
            return 0.0;
        }
        
        double sum = 0.0;
        for (Double rcp : history) {
            sum += rcp;
        }
        
        return sum / history.size();
    }
    
    public double getMaxRCP(UUID playerUUID) {
        List<Double> history = playerRCPHistory.get(playerUUID);
        if (history == null || history.isEmpty()) {
            return 0.0;
        }
        
        double max = 0.0;
        for (Double rcp : history) {
            if (rcp > max) {
                max = rcp;
            }
        }
        
        return max;
    }
    
    public void updatePriorProbability(UUID playerUUID, double prior) {
        playerPriorProbabilities.put(playerUUID, clampRCP(prior));
    }
    
    public void clearPlayerData(UUID playerUUID) {
        playerRCPHistory.remove(playerUUID);
        playerPriorProbabilities.remove(playerUUID);
        playerLastUpdate.remove(playerUUID);
    }
    
    public void clearAllData() {
        playerRCPHistory.clear();
        playerPriorProbabilities.clear();
        playerLastUpdate.clear();
    }
    
    private double clampRCP(double rcp) {
        return Math.max(0.0, Math.min(1.0, rcp));
    }
    
    public Map<String, Object> getRCPStatistics(UUID playerUUID) {
        Map<String, Object> stats = new HashMap<>();
        
        List<Double> history = getRCPHistory(playerUUID);
        stats.put("historySize", history.size());
        stats.put("average", getAverageRCP(playerUUID));
        stats.put("maximum", getMaxRCP(playerUUID));
        stats.put("current", history.isEmpty() ? 0.0 : history.get(history.size() - 1));
        stats.put("increasingTrend", isIncreasingTrend(playerUUID));
        stats.put("priorProbability", playerPriorProbabilities.getOrDefault(playerUUID, 0.05));
        
        return stats;
    }
    
    public ProbabilityFusionEngine getFusionEngine() {
        return fusionEngine;
    }
    
    public AdaptiveLearningSystem getLearningSystem() {
        return learningSystem;
    }
}
