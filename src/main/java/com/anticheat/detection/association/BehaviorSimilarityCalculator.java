package com.anticheat.detection.association;

import com.anticheat.profiles.PlayerProfile;
import com.anticheat.managers.ProfileManager;

import java.util.*;

public class BehaviorSimilarityCalculator {

    private static final double MIN_SAMPLES_FOR_COMPARISON = 20;
    private static final double DEFAULT_TWIN_THRESHOLD = 0.85;

    private final ProfileManager profileManager;

    public BehaviorSimilarityCalculator(ProfileManager profileManager) {
        this.profileManager = profileManager;
    }

    public double calculateCosineSimilarity(UUID player1, UUID player2) {
        PlayerProfile profile1 = profileManager.getProfile(player1);
        PlayerProfile profile2 = profileManager.getProfile(player2);

        if (profile1 == null || profile2 == null) {
            return 0.0;
        }

        double[] vector1 = extractBehaviorVector(profile1);
        double[] vector2 = extractBehaviorVector(profile2);

        return cosineSimilarity(vector1, vector2);
    }

    private double[] extractBehaviorVector(PlayerProfile profile) {
        List<Double> features = new ArrayList<>();

        features.add(profile.getCpsMean());
        features.add(profile.getCpsStdDev());
        features.add(profile.getTurnSpeedMean());
        features.add(profile.getTurnSpeedStdDev());
        features.add(profile.getJumpIntervalMean());
        features.add(profile.getJumpIntervalStdDev());
        features.add(profile.getInterfaceActionMean());
        features.add(profile.getInterfaceActionStdDev());
        features.add(profile.getWalkStayRatioMean());
        features.add(profile.getWalkStayRatioStdDev());

        double[] vector = new double[features.size()];
        for (int i = 0; i < features.size(); i++) {
            Double value = features.get(i);
            vector[i] = (value != null) ? value : 0.0;
        }

        return vector;
    }

    private double cosineSimilarity(double[] vector1, double[] vector2) {
        if (vector1.length != vector2.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double magnitude1 = 0.0;
        double magnitude2 = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            magnitude1 += vector1[i] * vector1[i];
            magnitude2 += vector2[i] * vector2[i];
        }

        magnitude1 = Math.sqrt(magnitude1);
        magnitude2 = Math.sqrt(magnitude2);

        if (magnitude1 == 0.0 || magnitude2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (magnitude1 * magnitude2);
    }

    public double calculateTwinScore(UUID player1, UUID player2) {
        double cosineSim = calculateCosineSimilarity(player1, player2);

        PlayerProfile profile1 = profileManager.getProfile(player1);
        PlayerProfile profile2 = profileManager.getProfile(player2);

        if (profile1 == null || profile2 == null) {
            return cosineSim;
        }

        double movementScore = calculateMovementSimilarity(profile1, profile2);
        double combatScore = calculateCombatSimilarity(profile1, profile2);
        double timingScore = calculateTimingSimilarity(profile1, profile2);

        double weightedScore = (cosineSim * 0.3) + (movementScore * 0.25) +
                              (combatScore * 0.25) + (timingScore * 0.2);

        return weightedScore;
    }

    private double calculateMovementSimilarity(PlayerProfile p1, PlayerProfile p2) {
        List<Double> movement1 = new ArrayList<>();
        movement1.add(p1.getCpsMean());
        movement1.add(p1.getTurnSpeedMean());
        movement1.add(p1.getWalkStayRatioMean());

        List<Double> movement2 = new ArrayList<>();
        movement2.add(p2.getCpsMean());
        movement2.add(p2.getTurnSpeedMean());
        movement2.add(p2.getWalkStayRatioMean());

        return calculateVectorCorrelation(movement1, movement2);
    }

    private double calculateCombatSimilarity(PlayerProfile p1, PlayerProfile p2) {
        List<Double> combat1 = new ArrayList<>();
        combat1.add(p1.getCpsMean());
        combat1.add(p1.getCpsStdDev());
        combat1.add(p1.getTurnSpeedMean());
        combat1.add(p1.getTurnSpeedStdDev());

        List<Double> combat2 = new ArrayList<>();
        combat2.add(p2.getCpsMean());
        combat2.add(p2.getCpsStdDev());
        combat2.add(p2.getTurnSpeedMean());
        combat2.add(p2.getTurnSpeedStdDev());

        return calculateVectorCorrelation(combat1, combat2);
    }

    private double calculateTimingSimilarity(PlayerProfile p1, PlayerProfile p2) {
        List<Double> timing1 = new ArrayList<>();
        timing1.add(p1.getJumpIntervalMean());
        timing1.add(p1.getJumpIntervalStdDev());
        timing1.add(p1.getInterfaceActionMean());
        timing1.add(p1.getInterfaceActionStdDev());

        List<Double> timing2 = new ArrayList<>();
        timing2.add(p2.getJumpIntervalMean());
        timing2.add(p2.getJumpIntervalStdDev());
        timing2.add(p2.getInterfaceActionMean());
        timing2.add(p2.getInterfaceActionStdDev());

        return calculateVectorCorrelation(timing1, timing2);
    }

    private double calculateVectorCorrelation(List<Double> v1, List<Double> v2) {
        if (v1.size() != v2.size() || v1.isEmpty()) {
            return 0.0;
        }

        double mean1 = v1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double mean2 = v2.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double covariance = 0.0;
        double variance1 = 0.0;
        double variance2 = 0.0;

        for (int i = 0; i < v1.size(); i++) {
            double diff1 = v1.get(i) - mean1;
            double diff2 = v2.get(i) - mean2;

            covariance += diff1 * diff2;
            variance1 += diff1 * diff1;
            variance2 += diff2 * diff2;
        }

        if (variance1 == 0.0 || variance2 == 0.0) {
            return 0.0;
        }

        return covariance / (Math.sqrt(variance1) * Math.sqrt(variance2));
    }

    public boolean isBehaviorTwin(UUID player1, UUID player2, double threshold) {
        PlayerProfile profile1 = profileManager.getProfile(player1);
        PlayerProfile profile2 = profileManager.getProfile(player2);

        if (profile1 == null || profile2 == null) {
            return false;
        }

        if (!profile1.hasEnoughSamples() || !profile2.hasEnoughSamples()) {
            return false;
        }

        double twinScore = calculateTwinScore(player1, player2);
        return twinScore >= threshold;
    }

    public boolean isBehaviorTwin(UUID player1, UUID player2) {
        return isBehaviorTwin(player1, player2, DEFAULT_TWIN_THRESHOLD);
    }

    public Map<UUID, Double> findSimilarPlayers(UUID playerUUID, int topN) {
        Map<UUID, Double> similarities = new HashMap<>();

        Collection<PlayerProfile> allProfiles = profileManager.getCachedProfiles().values();
        for (PlayerProfile profile : allProfiles) {
            UUID otherUUID = profile.getPlayerUUID();
            if (!otherUUID.equals(playerUUID)) {
                double similarity = calculateTwinScore(playerUUID, otherUUID);
                similarities.put(otherUUID, similarity);
            }
        }

        List<Map.Entry<UUID, Double>> sortedEntries = new ArrayList<>(similarities.entrySet());
        sortedEntries.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

        Map<UUID, Double> topSimilar = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<UUID, Double> entry : sortedEntries) {
            if (count >= topN) break;
            topSimilar.put(entry.getKey(), entry.getValue());
            count++;
        }

        return topSimilar;
    }

    public List<Set<UUID>> findBehaviorTwinGroups(double threshold) {
        List<Set<UUID>> groups = new ArrayList<>();
        Set<UUID> processedPlayers = new HashSet<>();

        Collection<PlayerProfile> allProfiles = profileManager.getCachedProfiles().values();
        List<UUID> allPlayerUUIDs = new ArrayList<>();
        for (PlayerProfile profile : allProfiles) {
            allPlayerUUIDs.add(profile.getPlayerUUID());
        }

        for (UUID player1 : allPlayerUUIDs) {
            if (processedPlayers.contains(player1)) {
                continue;
            }

            Set<UUID> group = new HashSet<>();
            group.add(player1);

            for (UUID player2 : allPlayerUUIDs) {
                if (player1.equals(player2) || processedPlayers.contains(player2)) {
                    continue;
                }

                if (isBehaviorTwin(player1, player2, threshold)) {
                    group.add(player2);
                }
            }

            if (group.size() > 1) {
                groups.add(group);
                processedPlayers.addAll(group);
            }
        }

        return groups;
    }
}
