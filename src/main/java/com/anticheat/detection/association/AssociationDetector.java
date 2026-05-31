package com.anticheat.detection.association;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ProfileManager;
import com.anticheat.profiles.PlayerProfile;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AssociationDetector {

    private final AdvancedAntiCheat plugin;
    private final ProfileManager profileManager;
    private final SocialGraph socialGraph;

    private final Map<UUID, AssociationData> playerAssociationData;
    private final Map<UUID, Set<UUID>> suspectedAltAccounts;

    private static final double ALT_ACCOUNT_SIMILARITY_THRESHOLD = 0.75;
    private static final double BEHAVIOR_SIMILARITY_THRESHOLD = 0.80;
    private static final int MIN_COMMON_PLAYERS = 3;
    private static final int MIN_INTERACTION_COUNT = 5;

    public AssociationDetector(AdvancedAntiCheat plugin, ProfileManager profileManager, SocialGraph socialGraph) {
        this.plugin = plugin;
        this.profileManager = profileManager;
        this.socialGraph = socialGraph;
        this.playerAssociationData = new ConcurrentHashMap<>();
        this.suspectedAltAccounts = new ConcurrentHashMap<>();
    }

    public void analyzePlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profileManager.getProfile(uuid);

        if (profile == null) {
            return;
        }

        AssociationData data = playerAssociationData.computeIfAbsent(uuid, k -> new AssociationData());

        updateAssociationData(player, profile, data);
        detectAltAccounts(player, data);
        analyzeSocialConnections(player, data);
        checkTeamCheating(player, data);
    }

    private void updateAssociationData(Player player, PlayerProfile profile, AssociationData data) {
        data.lastAnalysis = System.currentTimeMillis();
        data.interactionCount++;

        if (data.interactionCount > 1000) {
            data.interactionCount = 0;
        }
    }

    private void detectAltAccounts(Player player, AssociationData data) {
        PlayerProfile currentProfile = profileManager.getProfile(player.getUniqueId());
        if (currentProfile == null) {
            return;
        }

        for (Map.Entry<UUID, PlayerProfile> entry : profileManager.getCachedProfiles().entrySet()) {
            UUID otherUUID = entry.getKey();
            if (otherUUID.equals(player.getUniqueId())) {
                continue;
            }

            PlayerProfile otherProfile = entry.getValue();
            if (otherProfile == null) {
                continue;
            }

            double similarity = calculateAccountSimilarity(currentProfile, otherProfile);

            if (similarity >= ALT_ACCOUNT_SIMILARITY_THRESHOLD) {
                if (isSameIP(currentProfile, otherProfile) || 
                    hasSimilarBehaviorPatterns(currentProfile, otherProfile)) {
                    recordAltAccountConnection(player.getUniqueId(), otherUUID, similarity);
                }
            }
        }
    }

    private double calculateAccountSimilarity(PlayerProfile profile1, PlayerProfile profile2) {
        double similarity = 0.0;
        int comparisonCount = 0;

        if (profile1.getCpsMean() > 0 && profile2.getCpsMean() > 0) {
            double cpsDiff = Math.abs(profile1.getCpsMean() - profile2.getCpsMean());
            double cpsSimilarity = 1.0 - Math.min(1.0, cpsDiff / 5.0);
            similarity += cpsSimilarity;
            comparisonCount++;
        }

        if (profile1.getTurnSpeedMean() > 0 && profile2.getTurnSpeedMean() > 0) {
            double turnDiff = Math.abs(profile1.getTurnSpeedMean() - profile2.getTurnSpeedMean());
            double turnSimilarity = 1.0 - Math.min(1.0, turnDiff / 3.0);
            similarity += turnSimilarity;
            comparisonCount++;
        }

        if (profile1.getWalkStayRatioMean() > 0 && profile2.getWalkStayRatioMean() > 0) {
            double ratioDiff = Math.abs(profile1.getWalkStayRatioMean() - profile2.getWalkStayRatioMean());
            double ratioSimilarity = 1.0 - Math.min(1.0, ratioDiff);
            similarity += ratioSimilarity;
            comparisonCount++;
        }

        return comparisonCount > 0 ? similarity / comparisonCount : 0.0;
    }

    private boolean isSameIP(PlayerProfile profile1, PlayerProfile profile2) {
        String ip1 = profile1.getIdentity().getCurrentIP();
        String ip2 = profile2.getIdentity().getCurrentIP();

        if (ip1 == null || ip2 == null) {
            return false;
        }

        return ip1.equals(ip2);
    }

    private boolean hasSimilarBehaviorPatterns(PlayerProfile profile1, PlayerProfile profile2) {
        if (profile1.getCpsMean() == 0 || profile2.getCpsMean() == 0) {
            return false;
        }

        double cpsRatio = profile1.getCpsMean() / profile2.getCpsMean();
        if (cpsRatio < 0.9 || cpsRatio > 1.1) {
            return false;
        }

        if (profile1.getJumpIntervalMean() == 0 || profile2.getJumpIntervalMean() == 0) {
            return false;
        }

        double jumpRatio = profile1.getJumpIntervalMean() / profile2.getJumpIntervalMean();
        return jumpRatio >= 0.85 && jumpRatio <= 1.15;
    }

    private void recordAltAccountConnection(UUID uuid1, UUID uuid2, double similarity) {
        suspectedAltAccounts.computeIfAbsent(uuid1, k -> ConcurrentHashMap.newKeySet()).add(uuid2);
        suspectedAltAccounts.computeIfAbsent(uuid2, k -> ConcurrentHashMap.newKeySet()).add(uuid1);

        plugin.getLogger().info("[AssociationDetector] 检测到可能的同账号: " + 
            uuid1 + " <-> " + uuid2 + " 相似度: " + String.format("%.2f", similarity));
    }

    private void analyzeSocialConnections(Player player, AssociationData data) {
        List<SocialGraph.InteractionRecord> interactions = socialGraph.getInteractionHistory(player.getUniqueId());

        Set<UUID> frequentPartners = new HashSet<>();
        Map<UUID, Integer> partnerCounts = new HashMap<>();

        for (SocialGraph.InteractionRecord record : interactions) {
            UUID other = record.player1.equals(player.getUniqueId()) ? record.player2 : record.player1;
            partnerCounts.merge(other, 1, Integer::sum);
        }

        for (Map.Entry<UUID, Integer> entry : partnerCounts.entrySet()) {
            if (entry.getValue() >= MIN_INTERACTION_COUNT) {
                frequentPartners.add(entry.getKey());
            }
        }

        data.frequentPartners = frequentPartners;

        if (frequentPartners.size() >= MIN_COMMON_PLAYERS) {
            checkTeamBehavior(player, frequentPartners);
        }
    }

    private void checkTeamBehavior(Player player, Set<UUID> partners) {
        List<UUID> partnerList = new ArrayList<>(partners);
        int teamSize = partnerList.size();

        if (teamSize < 2) {
            return;
        }

        int suspiciousPairs = 0;
        int totalPairs = 0;

        for (int i = 0; i < partnerList.size(); i++) {
            for (int j = i + 1; j < partnerList.size(); j++) {
                UUID partner1 = partnerList.get(i);
                UUID partner2 = partnerList.get(j);

                totalPairs++;

                if (arePlayersConnected(partner1, partner2)) {
                    suspiciousPairs++;
                }
            }
        }

        if (totalPairs > 0) {
            double connectionRatio = (double) suspiciousPairs / totalPairs;
            if (connectionRatio > 0.5) {
                plugin.getLogger().warning("[AssociationDetector] 检测到可能的团队作弊: " + 
                    player.getName() + " 团队大小: " + teamSize +
                    " 连接率: " + String.format("%.2f", connectionRatio));
            }
        }
    }

    private boolean arePlayersConnected(UUID player1, UUID player2) {
        List<SocialGraph.InteractionRecord> interactions1 = socialGraph.getInteractionHistory(player1);
        List<SocialGraph.InteractionRecord> interactions2 = socialGraph.getInteractionHistory(player2);

        for (SocialGraph.InteractionRecord record1 : interactions1) {
            UUID other1 = record1.player1.equals(player1) ? record1.player2 : record1.player1;
            for (SocialGraph.InteractionRecord record2 : interactions2) {
                UUID other2 = record2.player1.equals(player2) ? record2.player2 : record2.player1;
                if (other1.equals(other2)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void checkTeamCheating(Player player, AssociationData data) {
        if (data.frequentPartners.size() < 2) {
            return;
        }

        PlayerProfile playerProfile = profileManager.getProfile(player.getUniqueId());
        if (playerProfile == null) {
            return;
        }

        double avgRiskScore = playerProfile.getRiskScore();

        for (UUID partner : data.frequentPartners) {
            PlayerProfile partnerProfile = profileManager.getProfile(partner);
            if (partnerProfile != null) {
                avgRiskScore += partnerProfile.getRiskScore();
            }
        }

        avgRiskScore /= (data.frequentPartners.size() + 1);

        if (avgRiskScore > 0.7) {
            plugin.getLogger().warning("[AssociationDetector] 团队风险分数异常: " + 
                player.getName() + " 平均风险: " + String.format("%.2f", avgRiskScore));
        }
    }

    public Set<UUID> getSuspectedAltAccounts(UUID playerUUID) {
        Set<UUID> alts = suspectedAltAccounts.get(playerUUID);
        return alts != null ? new HashSet<>(alts) : new HashSet<>();
    }

    public AssociationData getAssociationData(UUID playerUUID) {
        return playerAssociationData.get(playerUUID);
    }

    public void clearPlayerData(UUID uuid) {
        playerAssociationData.remove(uuid);
        suspectedAltAccounts.remove(uuid);
    }

    public static class AssociationData {
        volatile long lastAnalysis = 0;
        volatile int interactionCount = 0;
        Set<UUID> frequentPartners = new HashSet<>();
        double teamRiskScore = 0.0;
    }
}
