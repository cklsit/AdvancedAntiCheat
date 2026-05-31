package com.anticheat.profiles;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MiningPatternAnalyzer implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final Map<UUID, List<Long>> playerBreakTimes;
    private final Map<UUID, Double> playerBreakTimeStdDev;
    private final Map<UUID, Integer> blockBreakCount;
    
    private static final int MIN_BREAKS_FOR_ANALYSIS = 15;
    private static final double STD_DEV_THRESHOLD = 0.1;
    private static final double VERY_LOW_STD_DEV = 0.05;
    private static final int MAX_BREAK_HISTORY = 150;
    
    private static final Map<String, Double> BLOCK_BASE_TIMES;
    
    static {
        BLOCK_BASE_TIMES = new HashMap<>();
        BLOCK_BASE_TIMES.put("STONE", 1500.0);
        BLOCK_BASE_TIMES.put("DIRT", 900.0);
        BLOCK_BASE_TIMES.put("COBBLESTONE", 1500.0);
        BLOCK_BASE_TIMES.put("WOOD", 3000.0);
        BLOCK_BASE_TIMES.put("LOG", 3000.0);
        BLOCK_BASE_TIMES.put("LEAVES", 300.0);
        BLOCK_BASE_TIMES.put("GLASS", 400.0);
        BLOCK_BASE_TIMES.put("COAL_ORE", 1500.0);
        BLOCK_BASE_TIMES.put("IRON_ORE", 1500.0);
        BLOCK_BASE_TIMES.put("GOLD_ORE", 1500.0);
        BLOCK_BASE_TIMES.put("DIAMOND_ORE", 1500.0);
        BLOCK_BASE_TIMES.put("EMERALD_ORE", 1500.0);
        BLOCK_BASE_TIMES.put("OBSIDIAN", 10000.0);
    }
    
    public MiningPatternAnalyzer() {
        this.playerBreakTimes = new ConcurrentHashMap<>();
        this.playerBreakTimeStdDev = new ConcurrentHashMap<>();
        this.blockBreakCount = new ConcurrentHashMap<>();
    }
    
    public void recordBreakTime(UUID playerUUID, long time) {
        recordBreakTime(playerUUID, time, "STONE");
    }
    
    public void recordBreakTime(UUID playerUUID, long time, String blockType) {
        List<Long> breakTimes = playerBreakTimes.computeIfAbsent(
            playerUUID, k -> new ArrayList<>());
        
        synchronized (breakTimes) {
            breakTimes.add(time);
            
            if (breakTimes.size() > MAX_BREAK_HISTORY) {
                breakTimes.remove(0);
            }
        }
        
        blockBreakCount.merge(playerUUID, 1, Integer::sum);
        
        double stdDev = calculateBreakTimeStdDev(playerUUID);
        playerBreakTimeStdDev.put(playerUUID, stdDev);
    }
    
    public double calculateBreakTimeStdDev(UUID playerUUID) {
        List<Long> breakTimes = playerBreakTimes.get(playerUUID);
        if (breakTimes == null || breakTimes.size() < MIN_BREAKS_FOR_ANALYSIS) {
            return 0.0;
        }
        
        List<Long> recentBreaks;
        synchronized (breakTimes) {
            int windowSize = Math.min(30, breakTimes.size());
            recentBreaks = breakTimes.subList(breakTimes.size() - windowSize, breakTimes.size());
        }
        
        if (recentBreaks.isEmpty()) {
            return 0.0;
        }
        
        double mean = 0.0;
        for (long time : recentBreaks) {
            mean += time;
        }
        mean /= recentBreaks.size();
        
        double variance = 0.0;
        for (long time : recentBreaks) {
            variance += Math.pow(time - mean, 2);
        }
        variance /= recentBreaks.size();
        
        double stdDev = Math.sqrt(variance);
        double normalizedStdDev = stdDev / mean;
        
        return normalizedStdDev;
    }
    
    public boolean isAutoMiner(double stdDev) {
        return stdDev < STD_DEV_THRESHOLD;
    }
    
    public boolean isAutoMiner(UUID playerUUID) {
        double stdDev = playerBreakTimeStdDev.getOrDefault(playerUUID, 0.0);
        return isAutoMiner(stdDev);
    }
    
    public boolean isConstantMiner(UUID playerUUID) {
        double stdDev = playerBreakTimeStdDev.getOrDefault(playerUUID, 0.0);
        return stdDev < VERY_LOW_STD_DEV;
    }
    
    public double calculateMeanBreakTime(UUID playerUUID) {
        List<Long> breakTimes = playerBreakTimes.get(playerUUID);
        if (breakTimes == null || breakTimes.isEmpty()) {
            return 0.0;
        }
        
        List<Long> recentBreaks;
        synchronized (breakTimes) {
            int windowSize = Math.min(30, breakTimes.size());
            recentBreaks = breakTimes.subList(breakTimes.size() - windowSize, breakTimes.size());
        }
        
        double total = 0.0;
        for (long time : recentBreaks) {
            total += time;
        }
        
        return total / recentBreaks.size();
    }
    
    public double calculateVariance(UUID playerUUID) {
        List<Long> breakTimes = playerBreakTimes.get(playerUUID);
        if (breakTimes == null || breakTimes.size() < 2) {
            return 0.0;
        }
        
        List<Long> recentBreaks;
        synchronized (breakTimes) {
            int windowSize = Math.min(30, breakTimes.size());
            recentBreaks = breakTimes.subList(breakTimes.size() - windowSize, breakTimes.size());
        }
        
        double mean = calculateMeanBreakTime(playerUUID);
        
        double variance = 0.0;
        for (long time : recentBreaks) {
            variance += Math.pow(time - mean, 2);
        }
        variance /= recentBreaks.size();
        
        return variance;
    }
    
    public boolean hasConsistentBreakTime(UUID playerUUID, String blockType) {
        double mean = calculateMeanBreakTime(playerUUID);
        Double baseTime = BLOCK_BASE_TIMES.get(blockType);
        
        if (baseTime == null) {
            return false;
        }
        
        double difference = Math.abs(mean - baseTime);
        double threshold = baseTime * 0.3;
        
        return difference < threshold;
    }
    
    public double getBlocksPerMinute(UUID playerUUID) {
        List<Long> breakTimes = playerBreakTimes.get(playerUUID);
        if (breakTimes == null || breakTimes.size() < 2) {
            return 0.0;
        }
        
        long firstTime, lastTime;
        synchronized (breakTimes) {
            firstTime = breakTimes.get(0);
            lastTime = breakTimes.get(breakTimes.size() - 1);
        }
        
        long duration = lastTime - firstTime;
        if (duration <= 0) {
            return 0.0;
        }
        
        double count = blockBreakCount.getOrDefault(playerUUID, 0);
        double blocksPerMinute = (count * 60000.0) / duration;
        
        return blocksPerMinute;
    }
    
    public int getBlockBreakCount(UUID playerUUID) {
        return blockBreakCount.getOrDefault(playerUUID, 0);
    }
    
    public double getNormalizedStdDev(UUID playerUUID) {
        return playerBreakTimeStdDev.getOrDefault(playerUUID, 0.0);
    }
    
    public void clearPlayerData(UUID playerUUID) {
        playerBreakTimes.remove(playerUUID);
        playerBreakTimeStdDev.remove(playerUUID);
        blockBreakCount.remove(playerUUID);
    }
    
    public boolean hasEnoughData(UUID playerUUID) {
        List<Long> breakTimes = playerBreakTimes.get(playerUUID);
        return breakTimes != null && breakTimes.size() >= MIN_BREAKS_FOR_ANALYSIS;
    }
    
    public List<Long> getRecentBreakTimes(UUID playerUUID, int count) {
        List<Long> breakTimes = playerBreakTimes.get(playerUUID);
        if (breakTimes == null || breakTimes.isEmpty()) {
            return Collections.emptyList();
        }
        
        synchronized (breakTimes) {
            int start = Math.max(0, breakTimes.size() - count);
            return new ArrayList<>(breakTimes.subList(start, breakTimes.size()));
        }
    }
    
    public static double getBaseBreakTime(String blockType) {
        return BLOCK_BASE_TIMES.getOrDefault(blockType, 1500.0);
    }
}
