package com.anticheat.detection.fusion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AdaptiveLearningSystem {
    
    private final Map<UUID, List<FeedbackRecord>> feedbackHistory;
    private final Map<String, Double> moduleAccuracy;
    private final Map<String, Double> moduleWeights;
    private final Map<String, List<String>> detectedPatterns;
    private final Map<String, Integer> falsePositiveCounts;
    private final Map<String, Integer> truePositiveCounts;
    
    private static final double LEARNING_RATE = 0.1;
    private static final double DECAY_FACTOR = 0.95;
    private static final int MIN_SAMPLES_FOR_UPDATE = 10;
    private static final double ACCURACY_IMPROVEMENT_THRESHOLD = 0.05;
    
    public AdaptiveLearningSystem() {
        this.feedbackHistory = new ConcurrentHashMap<>();
        this.moduleAccuracy = new ConcurrentHashMap<>();
        this.moduleWeights = new ConcurrentHashMap<>();
        this.detectedPatterns = new ConcurrentHashMap<>();
        this.falsePositiveCounts = new ConcurrentHashMap<>();
        this.truePositiveCounts = new ConcurrentHashMap<>();
        initializeDefaultAccuracy();
    }
    
    private void initializeDefaultAccuracy() {
        String[] modules = {"fly", "speed", "reach", "aimbot", "killaura", "autoclicker", "scaffold", "noslow"};
        for (String module : modules) {
            moduleAccuracy.put(module, 0.8);
            moduleWeights.put(module, 1.0);
        }
    }
    
    public void collectFeedback(UUID playerUUID, boolean confirmed, String cheatType) {
        FeedbackRecord feedback = new FeedbackRecord(
            System.currentTimeMillis(),
            confirmed,
            cheatType
        );
        
        feedbackHistory
            .computeIfAbsent(playerUUID, k -> new ArrayList<>())
            .add(feedback);
        
        updateAccuracy(cheatType, confirmed);
        
        if (confirmed) {
            recordTruePositive(cheatType);
        } else {
            recordFalsePositive(cheatType);
        }
    }
    
    private void recordTruePositive(String cheatType) {
        int count = truePositiveCounts.getOrDefault(cheatType, 0);
        truePositiveCounts.put(cheatType, count + 1);
    }
    
    private void recordFalsePositive(String cheatType) {
        int count = falsePositiveCounts.getOrDefault(cheatType, 0);
        falsePositiveCounts.put(cheatType, count + 1);
    }
    
    private void updateAccuracy(String cheatType, boolean confirmed) {
        int truePositives = truePositiveCounts.getOrDefault(cheatType, 0);
        int falsePositives = falsePositiveCounts.getOrDefault(cheatType, 0);
        int total = truePositives + falsePositives;
        
        if (total < MIN_SAMPLES_FOR_UPDATE) {
            return;
        }
        
        double newAccuracy = (double) truePositives / total;
        
        double currentAccuracy = moduleAccuracy.getOrDefault(cheatType, 0.8);
        double updatedAccuracy = currentAccuracy * DECAY_FACTOR + newAccuracy * (1 - DECAY_FACTOR);
        
        moduleAccuracy.put(cheatType, updatedAccuracy);
    }
    
    public void updateModel() {
        for (Map.Entry<String, Double> entry : moduleAccuracy.entrySet()) {
            String module = entry.getKey();
            double accuracy = entry.getValue();
            
            double newWeight = calculateWeightFromAccuracy(accuracy);
            
            double currentWeight = moduleWeights.getOrDefault(module, 1.0);
            double updatedWeight = currentWeight + LEARNING_RATE * (newWeight - currentWeight);
            
            moduleWeights.put(module, clampWeight(updatedWeight));
        }
        
        analyzePatterns();
    }
    
    private double calculateWeightFromAccuracy(double accuracy) {
        if (accuracy >= 0.95) {
            return 1.5;
        } else if (accuracy >= 0.90) {
            return 1.3;
        } else if (accuracy >= 0.85) {
            return 1.1;
        } else if (accuracy >= 0.80) {
            return 1.0;
        } else if (accuracy >= 0.70) {
            return 0.8;
        } else {
            return 0.5;
        }
    }
    
    private void analyzePatterns() {
        for (Map.Entry<UUID, List<FeedbackRecord>> entry : feedbackHistory.entrySet()) {
            UUID playerUUID = entry.getKey();
            List<FeedbackRecord> records = entry.getValue();
            
            if (records.size() < 5) {
                continue;
            }
            
            Map<String, Integer> cheatTypeCounts = new HashMap<>();
            for (FeedbackRecord record : records) {
                if (record.confirmed) {
                    int count = cheatTypeCounts.getOrDefault(record.cheatType, 0);
                    cheatTypeCounts.put(record.cheatType, count + 1);
                }
            }
            
            for (Map.Entry<String, Integer> cheatEntry : cheatTypeCounts.entrySet()) {
                if (cheatEntry.getValue() >= 3) {
                    String pattern = cheatEntry.getKey();
                    detectedPatterns
                        .computeIfAbsent(pattern, k -> new ArrayList<>())
                        .add(playerUUID.toString());
                }
            }
        }
    }
    
    public List<String> recommendNewRules() {
        List<String> recommendations = new ArrayList<>();
        
        for (Map.Entry<String, Double> entry : moduleAccuracy.entrySet()) {
            String module = entry.getKey();
            double accuracy = entry.getValue();
            
            if (accuracy < 0.70) {
                recommendations.add("降低 " + module + " 模块权重，当前准确率: " + String.format("%.2f", accuracy * 100) + "%");
                recommendations.add("建议审查 " + module + " 模块的检测逻辑");
            }
            
            if (accuracy > 0.95) {
                recommendations.add("考虑为 " + module + " 模块添加更多检测特征");
            }
        }
        
        for (Map.Entry<String, Integer> entry : falsePositiveCounts.entrySet()) {
            String module = entry.getKey();
            int falsePositives = entry.getValue();
            
            if (falsePositives > 50) {
                recommendations.add(module + " 模块误报率较高，建议优化参数");
            }
        }
        
        for (Map.Entry<String, Double> entry : moduleWeights.entrySet()) {
            String module = entry.getKey();
            double weight = entry.getValue();
            
            if (weight < 0.5) {
                recommendations.add("警告: " + module + " 模块权重过低 (" + String.format("%.2f", weight) + ")，可能需要重新训练");
            }
        }
        
        return recommendations;
    }
    
    public double evaluateModelAccuracy() {
        if (moduleAccuracy.isEmpty()) {
            return 0.0;
        }
        
        double totalAccuracy = 0.0;
        int count = 0;
        
        for (Double accuracy : moduleAccuracy.values()) {
            totalAccuracy += accuracy;
            count++;
        }
        
        return count > 0 ? totalAccuracy / count : 0.0;
    }
    
    public double getModuleAccuracy(String module) {
        return moduleAccuracy.getOrDefault(module, 0.0);
    }
    
    public double getModuleWeight(String module) {
        return moduleWeights.getOrDefault(module, 1.0);
    }
    
    public List<FeedbackRecord> getPlayerFeedback(UUID playerUUID) {
        List<FeedbackRecord> records = feedbackHistory.get(playerUUID);
        return records != null ? new ArrayList<>(records) : new ArrayList<>();
    }
    
    public int getPlayerFeedbackCount(UUID playerUUID) {
        List<FeedbackRecord> records = feedbackHistory.get(playerUUID);
        return records != null ? records.size() : 0;
    }
    
    public void clearPlayerFeedback(UUID playerUUID) {
        feedbackHistory.remove(playerUUID);
    }
    
    public Map<String, Object> getModelStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPlayers", feedbackHistory.size());
        
        int totalFeedback = 0;
        for (List<FeedbackRecord> records : feedbackHistory.values()) {
            totalFeedback += records.size();
        }
        stats.put("totalFeedback", totalFeedback);
        stats.put("averageAccuracy", evaluateModelAccuracy());
        stats.put("moduleCount", moduleAccuracy.size());
        
        return stats;
    }
    
    public double calculateFalsePositiveRate(String module) {
        int falsePositives = falsePositiveCounts.getOrDefault(module, 0);
        int truePositives = truePositiveCounts.getOrDefault(module, 0);
        int total = falsePositives + truePositives;
        
        if (total == 0) {
            return 0.0;
        }
        
        return (double) falsePositives / total;
    }
    
    public double calculateTruePositiveRate(String module) {
        int truePositives = truePositiveCounts.getOrDefault(module, 0);
        int falsePositives = falsePositiveCounts.getOrDefault(module, 0);
        int total = truePositives + falsePositives;
        
        if (total == 0) {
            return 0.0;
        }
        
        return (double) truePositives / total;
    }
    
    private double clampWeight(double weight) {
        return Math.max(0.1, Math.min(2.0, weight));
    }
    
    public Map<String, Integer> getCheatTypeCounts(boolean confirmed) {
        Map<String, Integer> counts = new HashMap<>();
        
        for (List<FeedbackRecord> records : feedbackHistory.values()) {
            for (FeedbackRecord record : records) {
                if (record.confirmed == confirmed) {
                    int count = counts.getOrDefault(record.cheatType, 0);
                    counts.put(record.cheatType, count + 1);
                }
            }
        }
        
        return counts;
    }
    
    public static class FeedbackRecord {
        public final long timestamp;
        public final boolean confirmed;
        public final String cheatType;
        
        public FeedbackRecord(long timestamp, boolean confirmed, String cheatType) {
            this.timestamp = timestamp;
            this.confirmed = confirmed;
            this.cheatType = cheatType;
        }
    }
}
