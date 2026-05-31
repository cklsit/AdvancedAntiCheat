package com.anticheat.detection.combat;

import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class CPSLimiter {

    private static final double NORMAL_MIN_CPS = 8.0;
    private static final double NORMAL_MAX_CPS = 12.0;
    private static final double TOP_PLAYER_CPS = 15.0;
    private static final double CHEAT_THRESHOLD_CPS = 18.0;
    private static final long TIME_WINDOW_MS = 1000;
    private static final long MIN_CLICK_INTERVAL_MS = 50;

    private final Map<UUID, ClickData> playerClickData;

    public CPSLimiter() {
        this.playerClickData = new ConcurrentHashMap<>();
    }

    public void recordClick(Player player) {
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        ClickData data = playerClickData.computeIfAbsent(uuid, k -> new ClickData());
        data.addClick(currentTime);

        cleanupOldClicks(uuid, currentTime);
    }

    private void cleanupOldClicks(UUID uuid, long currentTime) {
        ClickData data = playerClickData.get(uuid);
        if (data == null) return;

        data.getClickTimes().removeIf(timestamp ->
            currentTime - timestamp > TIME_WINDOW_MS
        );
    }

    public double getCurrentCPS(UUID playerUUID) {
        ClickData data = playerClickData.get(playerUUID);
        if (data == null) return 0.0;

        cleanupOldClicks(playerUUID, System.currentTimeMillis());
        return data.getClickTimes().size();
    }

    public boolean isCPSAnomaly(UUID playerUUID) {
        double cps = getCurrentCPS(playerUUID);
        return cps > CHEAT_THRESHOLD_CPS;
    }

    public boolean isCPSSuspicious(UUID playerUUID) {
        double cps = getCurrentCPS(playerUUID);
        return cps > TOP_PLAYER_CPS && cps <= CHEAT_THRESHOLD_CPS;
    }

    public CPSViolationLevel getCPSViolationLevel(UUID playerUUID) {
        double cps = getCurrentCPS(playerUUID);

        if (cps > CHEAT_THRESHOLD_CPS) {
            return CPSViolationLevel.CHEATING;
        } else if (cps > TOP_PLAYER_CPS) {
            return CPSViolationLevel.SUSPICIOUS;
        } else if (cps > NORMAL_MAX_CPS) {
            return CPSViolationLevel.ELEVATED;
        }
        return CPSViolationLevel.NORMAL;
    }

    public boolean shouldBlockAction(UUID playerUUID) {
        double cps = getCurrentCPS(playerUUID);
        return cps > CHEAT_THRESHOLD_CPS;
    }

    public void recordSuspiciousClick(UUID playerUUID) {
        ClickData data = playerClickData.computeIfAbsent(playerUUID, k -> new ClickData());
        data.incrementSuspiciousCount();
    }

    public int getSuspiciousCount(UUID playerUUID) {
        ClickData data = playerClickData.get(playerUUID);
        return data != null ? data.getSuspiciousCount() : 0;
    }

    public double getAverageCPS(UUID playerUUID) {
        ClickData data = playerClickData.get(playerUUID);
        if (data == null) return 0.0;
        return data.getAverageCPS();
    }

    public double getCPSVariance(UUID playerUUID) {
        ClickData data = playerClickData.get(playerUUID);
        if (data == null) return 0.0;
        return data.getCPSVariance();
    }

    public boolean hasAbnormalCPSPattern(UUID playerUUID) {
        ClickData data = playerClickData.get(playerUUID);
        if (data == null) return false;

        if (data.getClickTimes().size() < 10) {
            return false;
        }

        double variance = data.getCPSVariance();
        double average = data.getAverageCPS();

        return variance < 0.1 && average > NORMAL_MAX_CPS;
    }

    public void cleanup(UUID playerUUID) {
        playerClickData.remove(playerUUID);
    }

    public static double getNormalMinCPS() {
        return NORMAL_MIN_CPS;
    }

    public static double getNormalMaxCPS() {
        return NORMAL_MAX_CPS;
    }

    public static double getTopPlayerCPS() {
        return TOP_PLAYER_CPS;
    }

    public static double getCheatThresholdCPS() {
        return CHEAT_THRESHOLD_CPS;
    }

    public enum CPSViolationLevel {
        NORMAL(0),
        ELEVATED(1),
        SUSPICIOUS(2),
        CHEATING(3);

        private final int severity;

        CPSViolationLevel(int severity) {
            this.severity = severity;
        }

        public int getSeverity() {
            return severity;
        }
    }

    private static class ClickData {
        private final Deque<Long> clickTimes;
        private final List<Double> cpsHistory;
        private int suspiciousCount;

        ClickData() {
            this.clickTimes = new ConcurrentLinkedDeque<>();
            this.cpsHistory = new ArrayList<>();
            this.suspiciousCount = 0;
        }

        void addClick(long timestamp) {
            clickTimes.addLast(timestamp);
        }

        Deque<Long> getClickTimes() {
            return clickTimes;
        }

        void incrementSuspiciousCount() {
            suspiciousCount++;
        }

        int getSuspiciousCount() {
            return suspiciousCount;
        }

        double getAverageCPS() {
            if (cpsHistory.isEmpty()) {
                return clickTimes.size();
            }
            double sum = 0;
            for (double cps : cpsHistory) {
                sum += cps;
            }
            return sum / cpsHistory.size();
        }

        double getCPSVariance() {
            if (cpsHistory.size() < 2) {
                return clickTimes.size() > 1 ? calculateCurrentVariance() : 0;
            }

            double mean = getAverageCPS();
            double sumSquares = 0;
            for (double cps : cpsHistory) {
                sumSquares += (cps - mean) * (cps - mean);
            }
            return sumSquares / cpsHistory.size();
        }

        private double calculateCurrentVariance() {
            int size = clickTimes.size();
            if (size < 2) return 0;

            List<Long> times = new ArrayList<>(clickTimes);
            double mean = times.size();
            double sumSquares = 0;
            for (int i = 0; i < size; i++) {
                double diff = (i == 0 ? 1 : times.get(i) - times.get(i - 1)) / 50.0;
                sumSquares += (diff - mean) * (diff - mean);
            }
            return sumSquares / size;
        }

        void addCPSSample(double cps) {
            if (cpsHistory.size() >= 20) {
                cpsHistory.remove(0);
            }
            cpsHistory.add(cps);
        }
    }
}
