package com.anticheat.detection.combat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AimbotHardLockDetector {

    private static final int DETECTION_WINDOW_TICKS = 10;
    private static final double MAX_ALLOWED_ERROR_DEGREES = 2.0;
    private static final double TRIGGER_THRESHOLD_PERCENTAGE = 0.9;

    private final Map<UUID, AimData> playerAimData;

    public AimbotHardLockDetector() {
        this.playerAimData = new ConcurrentHashMap<>();
    }

    public void recordLookDirection(Player player, Location eyeLocation, Vector direction) {
        UUID uuid = player.getUniqueId();
        AimData data = playerAimData.computeIfAbsent(uuid, k -> new AimData());
        data.recordLookDirection(eyeLocation, direction, System.currentTimeMillis());
    }

    public void recordTargetPosition(Player player, Entity target, Location targetLocation) {
        UUID uuid = player.getUniqueId();
        AimData data = playerAimData.computeIfAbsent(uuid, k -> new AimData());
        data.recordTargetPosition(target.getUniqueId(), targetLocation, System.currentTimeMillis());
    }

    public boolean isAimbotHardLock(UUID playerUUID, Entity target) {
        AimData data = playerAimData.get(playerUUID);
        if (data == null) return false;

        return data.isHardLock(target.getUniqueId());
    }

    public double getAimAccuracy(UUID playerUUID, Entity target) {
        AimData data = playerAimData.get(playerUUID);
        if (data == null) return 0.0;
        return data.getAimAccuracy(target.getUniqueId());
    }

    public double getAverageError(UUID playerUUID, Entity target) {
        AimData data = playerAimData.get(playerUUID);
        if (data == null) return 0.0;
        return data.getAverageError(target.getUniqueId());
    }

    public boolean hasRecentHardLock(UUID playerUUID) {
        AimData data = playerAimData.get(playerUUID);
        if (data == null) return false;
        return data.hasRecentHardLock();
    }

    public void cleanup(UUID playerUUID) {
        playerAimData.remove(playerUUID);
    }

    private double calculateAngleBetweenVectors(Vector v1, Vector v2) {
        double dot = v1.dot(v2);
        double magnitude1 = v1.length();
        double magnitude2 = v2.length();

        if (magnitude1 == 0 || magnitude2 == 0) return 0;

        double cosAngle = Math.max(-1, Math.min(1, dot / (magnitude1 * magnitude2)));
        return Math.toDegrees(Math.acos(cosAngle));
    }

    private double calculateErrorAngle(Vector lookDirection, Location playerEye, Location targetCenter) {
        Vector toTarget = targetCenter.toVector().subtract(playerEye.toVector()).normalize();
        return calculateAngleBetweenVectors(lookDirection, toTarget);
    }

    public static int getDetectionWindowTicks() {
        return DETECTION_WINDOW_TICKS;
    }

    public static double getMaxAllowedErrorDegrees() {
        return MAX_ALLOWED_ERROR_DEGREES;
    }

    public static double getTriggerThresholdPercentage() {
        return TRIGGER_THRESHOLD_PERCENTAGE;
    }

    private class AimData {
        private static final int MAX_RECORDS = 20;
        private final Deque<LookRecord> lookRecords;
        private final Map<UUID, Deque<TargetRecord>> targetRecords;
        private final Map<UUID, Boolean> hardLockDetected;
        private long lastHardLockTime;

        AimData() {
            this.lookRecords = new ConcurrentLinkedDeque<>();
            this.targetRecords = new ConcurrentHashMap<>();
            this.hardLockDetected = new ConcurrentHashMap<>();
            this.lastHardLockTime = 0;
        }

        void recordLookDirection(Location eyeLocation, Vector direction, long timestamp) {
            lookRecords.addLast(new LookRecord(eyeLocation.clone(), direction.clone(), timestamp));
            cleanupOldRecords();
            checkForHardLock();
        }

        void recordTargetPosition(UUID targetUUID, Location targetLocation, long timestamp) {
            targetRecords.computeIfAbsent(targetUUID, k -> new ConcurrentLinkedDeque<>())
                .addLast(new TargetRecord(targetLocation.clone(), timestamp));
            cleanupTargetRecords(targetUUID);
        }

        private void cleanupOldRecords() {
            long currentTime = System.currentTimeMillis();
            while (lookRecords.size() > MAX_RECORDS) {
                lookRecords.removeFirst();
            }
            lookRecords.removeIf(record -> currentTime - record.timestamp > 60000);
        }

        private void cleanupTargetRecords(UUID targetUUID) {
            Deque<TargetRecord> records = targetRecords.get(targetUUID);
            if (records == null) return;

            long currentTime = System.currentTimeMillis();
            while (records.size() > MAX_RECORDS) {
                records.removeFirst();
            }
            records.removeIf(record -> currentTime - record.timestamp > 60000);
        }

        private void checkForHardLock() {
            for (Map.Entry<UUID, Deque<TargetRecord>> entry : targetRecords.entrySet()) {
                UUID targetUUID = entry.getKey();
                Deque<TargetRecord> targetPosRecords = entry.getValue();

                if (lookRecords.size() < DETECTION_WINDOW_TICKS || targetPosRecords.size() < 2) {
                    continue;
                }

                int lowErrorCount = 0;
                double totalError = 0;
                List<LookRecord> recentLooks = new ArrayList<>(lookRecords);
                List<TargetRecord> recentTargets = new ArrayList<>(targetPosRecords);

                for (int i = 0; i < Math.min(recentLooks.size(), recentTargets.size()); i++) {
                    LookRecord look = recentLooks.get(recentLooks.size() - 1 - i);
                    TargetRecord target = recentTargets.get(recentTargets.size() - 1 - i);

                    double error = calculateErrorAngle(look.direction, look.eyeLocation, target.location);
                    totalError += error;

                    if (error < MAX_ALLOWED_ERROR_DEGREES) {
                        lowErrorCount++;
                    }
                }

                int windowSize = Math.min(recentLooks.size(), recentTargets.size());
                double accuracyPercentage = windowSize > 0 ? (double) lowErrorCount / windowSize : 0;
                double averageError = windowSize > 0 ? totalError / windowSize : 0;

                if (accuracyPercentage >= TRIGGER_THRESHOLD_PERCENTAGE) {
                    hardLockDetected.put(targetUUID, true);
                    lastHardLockTime = System.currentTimeMillis();
                } else {
                    hardLockDetected.put(targetUUID, false);
                }
            }
        }

        boolean isHardLock(UUID targetUUID) {
            Boolean detected = hardLockDetected.get(targetUUID);
            return detected != null && detected;
        }

        double getAimAccuracy(UUID targetUUID) {
            Boolean detected = hardLockDetected.get(targetUUID);
            return detected != null && detected ? TRIGGER_THRESHOLD_PERCENTAGE : 0.0;
        }

        double getAverageError(UUID targetUUID) {
            Deque<TargetRecord> records = targetRecords.get(targetUUID);
            if (records == null || lookRecords.isEmpty() || records.size() < 2) {
                return 0.0;
            }

            double totalError = 0;
            int count = 0;
            List<LookRecord> recentLooks = new ArrayList<>(lookRecords);
            List<TargetRecord> recentTargets = new ArrayList<>(records);

            for (int i = 0; i < Math.min(recentLooks.size(), recentTargets.size()); i++) {
                LookRecord look = recentLooks.get(recentLooks.size() - 1 - i);
                TargetRecord target = recentTargets.get(recentTargets.size() - 1 - i);
                totalError += calculateErrorAngle(look.direction, look.eyeLocation, target.location);
                count++;
            }

            return count > 0 ? totalError / count : 0.0;
        }

        boolean hasRecentHardLock() {
            return System.currentTimeMillis() - lastHardLockTime < 5000;
        }

        private class LookRecord {
            final Location eyeLocation;
            final Vector direction;
            final long timestamp;

            LookRecord(Location eyeLocation, Vector direction, long timestamp) {
                this.eyeLocation = eyeLocation;
                this.direction = direction;
                this.timestamp = timestamp;
            }
        }

        private class TargetRecord {
            final Location location;
            final long timestamp;

            TargetRecord(Location location, long timestamp) {
                this.location = location;
                this.timestamp = timestamp;
            }
        }
    }
}
