package com.anticheat.detection.combat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ReachValidator {

    private static final double NORMAL_REACH = 3.0;
    private static final double MAX_REACH_WITH_LAG_COMPENSATION = 3.5;
    private static final double ABSOLUTE_MAX_REACH = 4.0;

    private final Map<UUID, ReachData> playerReachData;

    public ReachValidator() {
        this.playerReachData = new ConcurrentHashMap<>();
    }

    public double calculateReach(Player attacker, Entity victim) {
        Location attackerEye = attacker.getLocation().add(0, attacker.getEyeHeight(), 0);
        Location victimCenter = calculateVictimCenter(victim);
        return attackerEye.distance(victimCenter);
    }

    private Location calculateVictimCenter(Entity entity) {
        Location loc = entity.getLocation();
        if (entity instanceof LivingEntity) {
            double eyeHeight = ((LivingEntity) entity).getEyeHeight();
            return loc.add(0, eyeHeight * 0.5, 0);
        }
        return loc.add(0, 0.5, 0);
    }

    public ReachViolationLevel checkReach(Player attacker, Entity victim) {
        double reach = calculateReach(attacker, victim);
        UUID uuid = attacker.getUniqueId();

        ReachData data = playerReachData.computeIfAbsent(uuid, k -> new ReachData());
        data.addSample(reach);

        if (reach > ABSOLUTE_MAX_REACH) {
            return ReachViolationLevel.CRITICAL;
        } else if (reach > MAX_REACH_WITH_LAG_COMPENSATION) {
            return ReachViolationLevel.SUSPICIOUS;
        } else if (reach > NORMAL_REACH) {
            return ReachViolationLevel.MINOR;
        }

        return ReachViolationLevel.NONE;
    }

    public boolean isReachAnomaly(Player attacker, Entity victim) {
        ReachViolationLevel level = checkReach(attacker, victim);
        return level != ReachViolationLevel.NONE;
    }

    public double getAverageReach(UUID playerUUID) {
        ReachData data = playerReachData.get(playerUUID);
        return data != null ? data.getAverage() : 0.0;
    }

    public boolean hasConsistentAbnormalReach(UUID playerUUID) {
        ReachData data = playerReachData.get(playerUUID);
        if (data == null || data.getSampleCount() < 5) {
            return false;
        }
        return data.getAverage() > NORMAL_REACH && data.getVariance() < 0.01;
    }

    public void recordViolation(UUID playerUUID, double reach) {
        ReachData data = playerReachData.computeIfAbsent(playerUUID, k -> new ReachData());
        data.addViolation();
    }

    public int getViolationCount(UUID playerUUID) {
        ReachData data = playerReachData.get(playerUUID);
        return data != null ? data.getViolationCount() : 0;
    }

    public void cleanup(UUID playerUUID) {
        playerReachData.remove(playerUUID);
    }

    public static double getNormalReach() {
        return NORMAL_REACH;
    }

    public static double getMaxReachWithLagCompensation() {
        return MAX_REACH_WITH_LAG_COMPENSATION;
    }

    public static double getAbsoluteMaxReach() {
        return ABSOLUTE_MAX_REACH;
    }

    public enum ReachViolationLevel {
        NONE(0),
        MINOR(1),
        SUSPICIOUS(2),
        CRITICAL(3);

        private final int severity;

        ReachViolationLevel(int severity) {
            this.severity = severity;
        }

        public int getSeverity() {
            return severity;
        }
    }

    private static class ReachData {
        private static final int MAX_SAMPLES = 20;
        private final double[] samples = new double[MAX_SAMPLES];
        private int sampleCount = 0;
        private double sum = 0;
        private double sumSquares = 0;
        private int violationCount = 0;

        void addSample(double reach) {
            if (sampleCount < MAX_SAMPLES) {
                samples[sampleCount++] = reach;
                sum += reach;
                sumSquares += reach * reach;
            } else {
                double oldSample = samples[0];
                System.arraycopy(samples, 1, samples, 0, MAX_SAMPLES - 1);
                samples[MAX_SAMPLES - 1] = reach;
                sum = sum - oldSample + reach;
                sumSquares = sumSquares - oldSample * oldSample + reach * reach;
            }
        }

        double getAverage() {
            return sampleCount > 0 ? sum / sampleCount : 0;
        }

        double getVariance() {
            if (sampleCount < 2) return 0;
            double mean = getAverage();
            return (sumSquares / sampleCount) - (mean * mean);
        }

        int getSampleCount() {
            return sampleCount;
        }

        void addViolation() {
            violationCount++;
        }

        int getViolationCount() {
            return violationCount;
        }
    }
}
