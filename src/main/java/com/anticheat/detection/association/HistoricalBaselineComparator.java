package com.anticheat.detection.association;

import com.anticheat.profiles.PlayerProfile;
import com.anticheat.managers.ProfileManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class HistoricalBaselineComparator {

    private static final double DEFAULT_CHANGE_THRESHOLD = 2.5;
    private static final int MIN_BASELINE_SAMPLES = 20;
    private static final long BASELINE_AGE_LIMIT = 30L * 24 * 60 * 60 * 1000;

    private final Map<UUID, List<BaselineSnapshot>> historicalBaselines;
    private final ProfileManager profileManager;

    public HistoricalBaselineComparator(ProfileManager profileManager) {
        this.profileManager = profileManager;
        this.historicalBaselines = new ConcurrentHashMap<>();
    }

    public double compareBaselines(UUID playerUUID) {
        PlayerProfile currentProfile = profileManager.getProfile(playerUUID);
        if (currentProfile == null) {
            return 0.0;
        }

        List<BaselineSnapshot> snapshots = historicalBaselines.get(playerUUID);
        if (snapshots == null || snapshots.isEmpty()) {
            saveCurrentBaseline(playerUUID, currentProfile);
            return 0.0;
        }

        BaselineSnapshot latestSnapshot = snapshots.get(snapshots.size() - 1);
        double currentSimilarity = calculateProfileSimilarity(currentProfile, latestSnapshot);

        return currentSimilarity;
    }

    public boolean isSuddenChange(UUID playerUUID, double changeThreshold) {
        PlayerProfile currentProfile = profileManager.getProfile(playerUUID);
        if (currentProfile == null || !currentProfile.hasEnoughSamples()) {
            return false;
        }

        List<BaselineSnapshot> snapshots = historicalBaselines.get(playerUUID);
        if (snapshots == null || snapshots.size() < 2) {
            return false;
        }

        BaselineSnapshot previousSnapshot = snapshots.get(snapshots.size() - 2);
        double changeScore = calculateChangeScore(currentProfile, previousSnapshot);

        if (changeScore > changeThreshold) {
            saveCurrentBaseline(playerUUID, currentProfile);
            return true;
        }

        return false;
    }

    public void triggerVerification(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            player.sendMessage("§c[反作弊] 检测到异常行为，请完成二次验证。");
            scheduleVerificationTask(playerUUID);
        }
    }

    private void scheduleVerificationTask(UUID playerUUID) {
        Bukkit.getScheduler().runTaskLater(profileManager.getPlugin(), () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                initiateVerificationChallenge(playerUUID);
            }
        }, 20L);
    }

    private void initiateVerificationChallenge(UUID playerUUID) {
        Random random = new Random();
        int challengeType = random.nextInt(3);

        switch (challengeType) {
            case 0:
                sendCaptchaChallenge(playerUUID);
                break;
            case 1:
                sendBehaviorQuestionnaire(playerUUID);
                break;
            case 2:
                requestManualVerification(playerUUID);
                break;
        }
    }

    private void sendCaptchaChallenge(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            player.sendMessage("§6[验证] 请完成验证码测试以继续游戏。");
        }
    }

    private void sendBehaviorQuestionnaire(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            player.sendMessage("§6[验证] 请回答几个问题以验证您的身份。");
        }
    }

    private void requestManualVerification(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            player.sendMessage("§6[验证] 管理员将在稍后联系您进行人工验证。");
        }
    }

    private void saveCurrentBaseline(UUID playerUUID, PlayerProfile profile) {
        BaselineSnapshot snapshot = createSnapshot(profile);

        List<BaselineSnapshot> snapshots = historicalBaselines.computeIfAbsent(
            playerUUID, k -> Collections.synchronizedList(new ArrayList<>())
        );

        synchronized (snapshots) {
            snapshots.add(snapshot);

            if (snapshots.size() > 100) {
                snapshots.remove(0);
            }

            cleanOldSnapshots(snapshots);
        }
    }

    private BaselineSnapshot createSnapshot(PlayerProfile profile) {
        Map<String, Double> baselineValues = new HashMap<>();

        baselineValues.put("cpsMean", profile.getCpsMean());
        baselineValues.put("cpsStdDev", profile.getCpsStdDev());
        baselineValues.put("turnSpeedMean", profile.getTurnSpeedMean());
        baselineValues.put("turnSpeedStdDev", profile.getTurnSpeedStdDev());
        baselineValues.put("jumpIntervalMean", profile.getJumpIntervalMean());
        baselineValues.put("jumpIntervalStdDev", profile.getJumpIntervalStdDev());
        baselineValues.put("interfaceActionMean", profile.getInterfaceActionMean());
        baselineValues.put("interfaceActionStdDev", profile.getInterfaceActionStdDev());
        baselineValues.put("walkStayRatioMean", profile.getWalkStayRatioMean());
        baselineValues.put("walkStayRatioStdDev", profile.getWalkStayRatioStdDev());

        return new BaselineSnapshot(System.currentTimeMillis(), baselineValues);
    }

    private double calculateProfileSimilarity(PlayerProfile profile, BaselineSnapshot snapshot) {
        double totalSimilarity = 0.0;
        int comparisonCount = 0;

        Map<String, Double> currentValues = new HashMap<>();
        currentValues.put("cpsMean", profile.getCpsMean());
        currentValues.put("cpsStdDev", profile.getCpsStdDev());
        currentValues.put("turnSpeedMean", profile.getTurnSpeedMean());
        currentValues.put("turnSpeedStdDev", profile.getTurnSpeedStdDev());
        currentValues.put("jumpIntervalMean", profile.getJumpIntervalMean());
        currentValues.put("jumpIntervalStdDev", profile.getJumpIntervalStdDev());
        currentValues.put("interfaceActionMean", profile.getInterfaceActionMean());
        currentValues.put("interfaceActionStdDev", profile.getInterfaceActionStdDev());
        currentValues.put("walkStayRatioMean", profile.getWalkStayRatioMean());
        currentValues.put("walkStayRatioStdDev", profile.getWalkStayRatioStdDev());

        for (Map.Entry<String, Double> entry : currentValues.entrySet()) {
            String feature = entry.getKey();
            Double currentValue = entry.getValue();
            Double baselineValue = snapshot.baselineValues.get(feature);

            if (baselineValue != null && baselineValue != 0.0) {
                double similarity = 1.0 - Math.abs(currentValue - baselineValue) / Math.abs(baselineValue);
                totalSimilarity += Math.max(0.0, similarity);
                comparisonCount++;
            }
        }

        return comparisonCount > 0 ? totalSimilarity / comparisonCount : 0.0;
    }

    private double calculateChangeScore(PlayerProfile profile, BaselineSnapshot previousSnapshot) {
        double maxChange = 0.0;

        Map<String, Double> currentValues = new HashMap<>();
        currentValues.put("cpsMean", profile.getCpsMean());
        currentValues.put("turnSpeedMean", profile.getTurnSpeedMean());
        currentValues.put("jumpIntervalMean", profile.getJumpIntervalMean());
        currentValues.put("interfaceActionMean", profile.getInterfaceActionMean());
        currentValues.put("walkStayRatioMean", profile.getWalkStayRatioMean());

        for (Map.Entry<String, Double> entry : currentValues.entrySet()) {
            String feature = entry.getKey();
            Double currentValue = entry.getValue();
            Double previousValue = previousSnapshot.baselineValues.get(feature);

            if (previousValue != null && previousValue != 0.0) {
                double change = Math.abs(currentValue - previousValue) / Math.abs(previousValue);
                maxChange = Math.max(maxChange, change);
            }
        }

        return maxChange;
    }

    private void cleanOldSnapshots(List<BaselineSnapshot> snapshots) {
        long currentTime = System.currentTimeMillis();
        snapshots.removeIf(snapshot -> (currentTime - snapshot.timestamp) > BASELINE_AGE_LIMIT);
    }

    public List<BaselineSnapshot> getHistoricalBaselines(UUID playerUUID) {
        return historicalBaselines.getOrDefault(playerUUID, new ArrayList<>());
    }

    public void clearHistory(UUID playerUUID) {
        historicalBaselines.remove(playerUUID);
    }

    public boolean hasEnoughHistory(UUID playerUUID) {
        List<BaselineSnapshot> snapshots = historicalBaselines.get(playerUUID);
        return snapshots != null && snapshots.size() >= 3;
    }

    public static class BaselineSnapshot {
        public final long timestamp;
        public final Map<String, Double> baselineValues;

        public BaselineSnapshot(long timestamp, Map<String, Double> baselineValues) {
            this.timestamp = timestamp;
            this.baselineValues = new HashMap<>(baselineValues);
        }
    }
}
