package com.anticheat.profiles;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TimerDetection implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final Map<UUID, List<Long>> playerActions;
    private final Map<UUID, Double> playerIntervalStdDev;
    
    private static final int MIN_ACTIONS_FOR_ANALYSIS = 10;
    private static final double STD_DEV_THRESHOLD = 0.15;
    private static final double LOW_VARIANCE_THRESHOLD = 0.05;
    private static final int MAX_ACTION_HISTORY = 100;
    
    public TimerDetection() {
        this.playerActions = new ConcurrentHashMap<>();
        this.playerIntervalStdDev = new ConcurrentHashMap<>();
    }
    
    public void recordAction(UUID playerUUID, long timestamp) {
        List<Long> actions = playerActions.computeIfAbsent(playerUUID, k -> new ArrayList<>());
        
        synchronized (actions) {
            if (actions.size() >= MAX_ACTION_HISTORY) {
                actions.remove(0);
            }
            actions.add(timestamp);
        }
        
        double stdDev = calculateIntervalStdDev(playerUUID);
        playerIntervalStdDev.put(playerUUID, stdDev);
    }
    
    public double calculateIntervalStdDev(UUID playerUUID) {
        List<Long> actions = playerActions.get(playerUUID);
        if (actions == null || actions.size() < MIN_ACTIONS_FOR_ANALYSIS) {
            return 0.0;
        }
        
        List<Long> recentActions;
        synchronized (actions) {
            int windowSize = Math.min(30, actions.size());
            recentActions = actions.subList(actions.size() - windowSize, actions.size());
        }
        
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < recentActions.size(); i++) {
            long interval = recentActions.get(i) - recentActions.get(i - 1);
            intervals.add((double) interval);
        }
        
        if (intervals.isEmpty()) {
            return 0.0;
        }
        
        double mean = 0.0;
        for (double interval : intervals) {
            mean += interval;
        }
        mean /= intervals.size();
        
        double variance = 0.0;
        for (double interval : intervals) {
            variance += Math.pow(interval - mean, 2);
        }
        variance /= intervals.size();
        
        double stdDev = Math.sqrt(variance);
        
        double normalizedStdDev = stdDev / mean;
        return normalizedStdDev;
    }
    
    public boolean isTimerAnomaly(double stdDev) {
        return stdDev < STD_DEV_THRESHOLD;
    }
    
    public boolean isTimerAnomaly(UUID playerUUID) {
        double stdDev = playerIntervalStdDev.getOrDefault(playerUUID, 0.0);
        return isTimerAnomaly(stdDev);
    }
    
    public boolean isConstantInterval(UUID playerUUID) {
        double stdDev = playerIntervalStdDev.getOrDefault(playerUUID, 0.0);
        return stdDev < LOW_VARIANCE_THRESHOLD;
    }
    
    public double getIntervalMean(UUID playerUUID) {
        List<Long> actions = playerActions.get(playerUUID);
        if (actions == null || actions.size() < 2) {
            return 0.0;
        }
        
        List<Long> recentActions;
        synchronized (actions) {
            int windowSize = Math.min(30, actions.size());
            recentActions = actions.subList(actions.size() - windowSize, actions.size());
        }
        
        double totalInterval = 0.0;
        int count = 0;
        
        for (int i = 1; i < recentActions.size(); i++) {
            totalInterval += recentActions.get(i) - recentActions.get(i - 1);
            count++;
        }
        
        return count > 0 ? totalInterval / count : 0.0;
    }
    
    public int getActionCount(UUID playerUUID) {
        List<Long> actions = playerActions.get(playerUUID);
        return actions != null ? actions.size() : 0;
    }
    
    public double getNormalizedStdDev(UUID playerUUID) {
        return playerIntervalStdDev.getOrDefault(playerUUID, 0.0);
    }
    
    public void clearPlayerData(UUID playerUUID) {
        playerActions.remove(playerUUID);
        playerIntervalStdDev.remove(playerUUID);
    }
    
    public boolean hasEnoughData(UUID playerUUID) {
        List<Long> actions = playerActions.get(playerUUID);
        return actions != null && actions.size() >= MIN_ACTIONS_FOR_ANALYSIS;
    }
    
    public List<Double> getRecentIntervals(UUID playerUUID, int count) {
        List<Long> actions = playerActions.get(playerUUID);
        if (actions == null || actions.size() < count + 1) {
            return Collections.emptyList();
        }
        
        List<Double> intervals = new ArrayList<>();
        synchronized (actions) {
            int start = Math.max(0, actions.size() - count - 1);
            for (int i = start + 1; i < actions.size(); i++) {
                intervals.add((double) (actions.get(i) - actions.get(i - 1)));
            }
        }
        
        return intervals;
    }
}
