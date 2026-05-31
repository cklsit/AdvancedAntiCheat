package com.anticheat.profiles;

import org.bukkit.entity.Player;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AimAnalysis implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final Map<UUID, List<LookData>> playerLooks;
    private final Map<UUID, Double> playerSmoothness;
    private final Map<UUID, Integer> playerHitCount;
    
    private static final int MAX_LOOK_HISTORY = 100;
    private static final double SMOOTHNESS_THRESHOLD = 0.85;
    private static final double MIN_VARIANCE_THRESHOLD = 0.1;
    
    public AimAnalysis() {
        this.playerLooks = new ConcurrentHashMap<>();
        this.playerSmoothness = new ConcurrentHashMap<>();
        this.playerHitCount = new ConcurrentHashMap<>();
    }
    
    public void recordLook(Player player, float fromYaw, float toYaw, 
                          float fromPitch, float toPitch, long timestamp) {
        UUID uuid = player.getUniqueId();
        
        List<LookData> looks = playerLooks.computeIfAbsent(uuid, k -> new ArrayList<>());
        
        float yawDiff = Math.abs(toYaw - fromYaw);
        if (yawDiff > 180) yawDiff = 360 - yawDiff;
        
        float pitchDiff = Math.abs(toPitch - fromPitch);
        
        synchronized (looks) {
            looks.add(new LookData(yawDiff, pitchDiff, timestamp));
            
            if (looks.size() > MAX_LOOK_HISTORY) {
                looks.remove(0);
            }
        }
    }
    
    public double calculateSmoothness(UUID playerUUID) {
        List<LookData> looks = playerLooks.get(playerUUID);
        if (looks == null || looks.size() < 10) {
            return 1.0;
        }
        
        List<LookData> recentLooks;
        synchronized (looks) {
            int windowSize = Math.min(20, looks.size());
            recentLooks = looks.subList(looks.size() - windowSize, looks.size());
        }
        
        double totalConsistency = 0.0;
        int count = 0;
        
        for (int i = 1; i < recentLooks.size(); i++) {
            LookData current = recentLooks.get(i);
            LookData previous = recentLooks.get(i - 1);
            
            double yawDiff = Math.abs(current.yawDiff - previous.yawDiff);
            double pitchDiff = Math.abs(current.pitchDiff - previous.pitchDiff);
            
            double consistency = 1.0 / (1.0 + yawDiff + pitchDiff);
            totalConsistency += consistency;
            count++;
        }
        
        if (count == 0) {
            return 1.0;
        }
        
        double avgConsistency = totalConsistency / count;
        double smoothness = 1.0 - avgConsistency;
        
        playerSmoothness.put(playerUUID, smoothness);
        return smoothness;
    }
    
    public boolean isAimbot(double smoothness) {
        return smoothness < SMOOTHNESS_THRESHOLD;
    }
    
    public boolean isAimbot(UUID playerUUID) {
        double smoothness = calculateSmoothness(playerUUID);
        return isAimbot(smoothness);
    }
    
    public double calculateVariance(UUID playerUUID) {
        List<LookData> looks = playerLooks.get(playerUUID);
        if (looks == null || looks.size() < 2) {
            return 0.0;
        }
        
        List<LookData> recentLooks;
        synchronized (looks) {
            int windowSize = Math.min(20, looks.size());
            recentLooks = looks.subList(looks.size() - windowSize, looks.size());
        }
        
        double mean = 0.0;
        for (LookData look : recentLooks) {
            mean += look.yawDiff;
        }
        mean /= recentLooks.size();
        
        double variance = 0.0;
        for (LookData look : recentLooks) {
            variance += Math.pow(look.yawDiff - mean, 2);
        }
        variance /= recentLooks.size();
        
        return variance;
    }
    
    public boolean hasLowVariance(UUID playerUUID) {
        double variance = calculateVariance(playerUUID);
        return variance < MIN_VARIANCE_THRESHOLD;
    }
    
    public void recordHit(UUID playerUUID) {
        playerHitCount.merge(playerUUID, 1, Integer::sum);
    }
    
    public int getHitCount(UUID playerUUID) {
        return playerHitCount.getOrDefault(playerUUID, 0);
    }
    
    public void clearPlayerData(UUID playerUUID) {
        playerLooks.remove(playerUUID);
        playerSmoothness.remove(playerUUID);
        playerHitCount.remove(playerUUID);
    }
    
    public double getSmoothness(UUID playerUUID) {
        return playerSmoothness.getOrDefault(playerUUID, 1.0);
    }
    
    public int getLookDataCount(UUID playerUUID) {
        List<LookData> looks = playerLooks.get(playerUUID);
        return looks != null ? looks.size() : 0;
    }
    
    public static class LookData implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final float yawDiff;
        public final float pitchDiff;
        public final long timestamp;
        
        public LookData(float yawDiff, float pitchDiff, long timestamp) {
            this.yawDiff = yawDiff;
            this.pitchDiff = pitchDiff;
            this.timestamp = timestamp;
        }
    }
}
