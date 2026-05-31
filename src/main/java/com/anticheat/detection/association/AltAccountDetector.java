package com.anticheat.detection.association;

import com.anticheat.managers.ProfileManager;
import com.anticheat.profiles.PlayerProfile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AltAccountDetector {

    private static final double IP_MATCH_WEIGHT = 0.3;
    private static final double BEHAVIOR_SIMILARITY_WEIGHT = 0.4;
    private static final double DEVICE_FINGERPRINT_WEIGHT = 0.3;
    private static final double ALT_ACCOUNT_THRESHOLD = 0.6;

    private final ProfileManager profileManager;
    private final Map<UUID, DeviceFingerprint> deviceFingerprints;
    private final BehaviorSimilarityCalculator similarityCalculator;

    public AltAccountDetector(ProfileManager profileManager) {
        this.profileManager = profileManager;
        this.deviceFingerprints = new ConcurrentHashMap<>();
        this.similarityCalculator = new BehaviorSimilarityCalculator(profileManager);
    }

    public List<AltAccount> detectAltAccounts(UUID mainAccount) {
        List<AltAccount> altAccounts = new ArrayList<>();

        List<UUID> ipRelatedAccounts = findAccountsByIP(mainAccount);
        for (UUID candidate : ipRelatedAccounts) {
            double confidence = calculateAltConfidence(mainAccount, candidate);
            if (confidence >= ALT_ACCOUNT_THRESHOLD) {
                AltAccount alt = new AltAccount(
                    mainAccount,
                    candidate,
                    confidence,
                    determineAltReason(mainAccount, candidate)
                );
                altAccounts.add(alt);
            }
        }

        List<UUID> similarBehaviorAccounts = findSimilarBehavior(mainAccount);
        for (UUID candidate : similarBehaviorAccounts) {
            if (ipRelatedAccounts.contains(candidate)) {
                continue;
            }

            double confidence = calculateAltConfidence(mainAccount, candidate);
            if (confidence >= ALT_ACCOUNT_THRESHOLD) {
                AltAccount alt = new AltAccount(
                    mainAccount,
                    candidate,
                    confidence,
                    AltAccount.AltReason.BEHAVIOR_SIMILARITY
                );
                altAccounts.add(alt);
            }
        }

        return altAccounts;
    }

    public List<UUID> findAccountsByIP(String ip) {
        List<UUID> matchingAccounts = new ArrayList<>();

        if (ip == null || ip.isEmpty()) {
            return matchingAccounts;
        }

        for (Map.Entry<UUID, DeviceFingerprint> entry : deviceFingerprints.entrySet()) {
            DeviceFingerprint fingerprint = entry.getValue();
            if (fingerprint.getIpAddress() != null &&
                fingerprint.getIpAddress().equals(ip)) {
                matchingAccounts.add(entry.getKey());
            }
        }

        return matchingAccounts;
    }

    public List<UUID> findAccountsByIP(UUID playerUUID) {
        DeviceFingerprint fingerprint = deviceFingerprints.get(playerUUID);
        if (fingerprint == null || fingerprint.getIpAddress() == null) {
            return new ArrayList<>();
        }

        return findAccountsByIP(fingerprint.getIpAddress());
    }

    public List<UUID> findSimilarBehavior(UUID playerUUID) {
        Map<UUID, Double> similarPlayers = similarityCalculator.findSimilarPlayers(playerUUID, 10);
        List<UUID> similarAccounts = new ArrayList<>();

        for (Map.Entry<UUID, Double> entry : similarPlayers.entrySet()) {
            if (entry.getValue() >= 0.8) {
                similarAccounts.add(entry.getKey());
            }
        }

        return similarAccounts;
    }

    public boolean isAltAccount(UUID player1, UUID player2) {
        double confidence = calculateAltConfidence(player1, player2);
        return confidence >= ALT_ACCOUNT_THRESHOLD;
    }

    private double calculateAltConfidence(UUID player1, UUID player2) {
        double totalConfidence = 0.0;
        int factors = 0;

        double ipSimilarity = calculateIPSimilarity(player1, player2);
        if (ipSimilarity > 0) {
            totalConfidence += ipSimilarity * IP_MATCH_WEIGHT;
            factors++;
        }

        double behaviorSimilarity = calculateBehaviorSimilarity(player1, player2);
        if (behaviorSimilarity > 0) {
            totalConfidence += behaviorSimilarity * BEHAVIOR_SIMILARITY_WEIGHT;
            factors++;
        }

        double deviceSimilarity = calculateDeviceSimilarity(player1, player2);
        if (deviceSimilarity > 0) {
            totalConfidence += deviceSimilarity * DEVICE_FINGERPRINT_WEIGHT;
            factors++;
        }

        return factors > 0 ? totalConfidence / factors : 0.0;
    }

    private double calculateIPSimilarity(UUID player1, UUID player2) {
        DeviceFingerprint fp1 = deviceFingerprints.get(player1);
        DeviceFingerprint fp2 = deviceFingerprints.get(player2);

        if (fp1 == null || fp2 == null) {
            return 0.0;
        }

        if (fp1.getIpAddress() != null && fp1.getIpAddress().equals(fp2.getIpAddress())) {
            return 1.0;
        }

        if (fp1.getPreviousIPAddresses().contains(fp2.getIpAddress()) ||
            fp2.getPreviousIPAddresses().contains(fp1.getIpAddress())) {
            return 0.7;
        }

        String subnet1 = getSubnet(fp1.getIpAddress());
        String subnet2 = getSubnet(fp2.getIpAddress());

        if (subnet1 != null && subnet1.equals(subnet2)) {
            return 0.5;
        }

        return 0.0;
    }

    private String getSubnet(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        String[] parts = ip.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }

        return null;
    }

    private double calculateBehaviorSimilarity(UUID player1, UUID player2) {
        PlayerProfile profile1 = profileManager.getProfile(player1);
        PlayerProfile profile2 = profileManager.getProfile(player2);

        if (profile1 == null || profile2 == null) {
            return 0.0;
        }

        if (!profile1.hasEnoughSamples() || !profile2.hasEnoughSamples()) {
            return 0.0;
        }

        return similarityCalculator.calculateTwinScore(player1, player2);
    }

    private double calculateDeviceSimilarity(UUID player1, UUID player2) {
        DeviceFingerprint fp1 = deviceFingerprints.get(player1);
        DeviceFingerprint fp2 = deviceFingerprints.get(player2);

        if (fp1 == null || fp2 == null) {
            return 0.0;
        }

        return fp1.calculateSimilarity(fp2);
    }

    private AltAccount.AltReason determineAltReason(UUID player1, UUID player2) {
        double ipSim = calculateIPSimilarity(player1, player2);
        double behaviorSim = calculateBehaviorSimilarity(player1, player2);
        double deviceSim = calculateDeviceSimilarity(player1, player2);

        if (ipSim >= 1.0 && behaviorSim >= 0.8) {
            return AltAccount.AltReason.COMBINED;
        } else if (ipSim >= 1.0) {
            return AltAccount.AltReason.IP_MATCH;
        } else if (behaviorSim >= 0.8) {
            return AltAccount.AltReason.BEHAVIOR_SIMILARITY;
        } else if (deviceSim >= 0.7) {
            return AltAccount.AltReason.DEVICE_FINGERPRINT;
        }

        return AltAccount.AltReason.COMBINED;
    }

    public void registerDeviceFingerprint(UUID playerUUID, DeviceFingerprint fingerprint) {
        deviceFingerprints.put(playerUUID, fingerprint);
    }

    public DeviceFingerprint getDeviceFingerprint(UUID playerUUID) {
        return deviceFingerprints.get(playerUUID);
    }

    public List<AltAccount> scanAllAccounts() {
        List<AltAccount> allAlts = new ArrayList<>();
        Set<UUID> processedAccounts = new HashSet<>();

        Collection<PlayerProfile> allProfiles = profileManager.getCachedProfiles().values();
        List<UUID> allPlayerUUIDs = new ArrayList<>();
        for (PlayerProfile profile : allProfiles) {
            allPlayerUUIDs.add(profile.getPlayerUUID());
        }

        for (UUID mainAccount : allPlayerUUIDs) {
            if (processedAccounts.contains(mainAccount)) {
                continue;
            }

            List<AltAccount> alts = detectAltAccounts(mainAccount);
            for (AltAccount alt : alts) {
                if (!processedAccounts.contains(alt.getAltAccount())) {
                    allAlts.add(alt);
                }
            }

            processedAccounts.add(mainAccount);
            for (AltAccount alt : alts) {
                processedAccounts.add(alt.getAltAccount());
            }
        }

        return allAlts;
    }
}
