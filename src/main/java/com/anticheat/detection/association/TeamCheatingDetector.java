package com.anticheat.detection.association;

import com.anticheat.managers.ProfileManager;
import com.anticheat.profiles.PlayerProfile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TeamCheatingDetector {

    private static final int MIN_TEAM_SIZE = 2;
    private static final int MAX_TEAM_SIZE = 10;
    private static final double SYNC_THRESHOLD = 0.85;
    private static final double TEAM_CHEAT_THRESHOLD = 0.7;

    private final ProfileManager profileManager;
    private final SocialGraph socialGraph;
    private final BehaviorSimilarityCalculator similarityCalculator;
    private final Map<UUID, CheatingTeam> detectedTeams;
    private final Map<UUID, List<SynchronizationEvent>> synchronizationEvents;

    public TeamCheatingDetector(ProfileManager profileManager, SocialGraph socialGraph) {
        this.profileManager = profileManager;
        this.socialGraph = socialGraph;
        this.similarityCalculator = new BehaviorSimilarityCalculator(profileManager);
        this.detectedTeams = new ConcurrentHashMap<>();
        this.synchronizationEvents = new ConcurrentHashMap<>();
    }

    public List<CheatingTeam> detectTeams() {
        List<CheatingTeam> teams = new ArrayList<>();

        List<SocialGraph.CheatingGroup> suspiciousGroups = socialGraph.detectCheatingGroups();

        for (SocialGraph.CheatingGroup group : suspiciousGroups) {
            if (group.members.size() >= MIN_TEAM_SIZE &&
                group.members.size() <= MAX_TEAM_SIZE) {

                if (hasSynchronizedAnomalies(new HashSet<>(group.members))) {
                    CheatingTeam team = analyzeTeam(group);
                    if (team != null && team.getCheatScore() >= TEAM_CHEAT_THRESHOLD) {
                        teams.add(team);
                        detectedTeams.put(team.getTeamId(), team);
                    }
                }
            }
        }

        return teams;
    }

    public boolean hasSynchronizedAnomalies(Set<UUID> team) {
        List<UUID> members = new ArrayList<>(team);

        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                UUID player1 = members.get(i);
                UUID player2 = members.get(j);

                List<SynchronizationEvent> events1 = synchronizationEvents.get(player1);
                List<SynchronizationEvent> events2 = synchronizationEvents.get(player2);

                if (events1 != null && events2 != null) {
                    if (detectSynchronization(events1, events2)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean detectSynchronization(List<SynchronizationEvent> events1,
                                         List<SynchronizationEvent> events2) {
        if (events1.size() < 5 || events2.size() < 5) {
            return false;
        }

        int synchronizedCount = 0;
        int totalComparisons = 0;

        for (SynchronizationEvent event1 : events1) {
            for (SynchronizationEvent event2 : events2) {
                if (isWithinTolerance(event1.timestamp, event2.timestamp, 500)) {
                    synchronizedCount++;
                    totalComparisons++;
                } else {
                    totalComparisons++;
                }
            }
        }

        if (totalComparisons == 0) {
            return false;
        }

        double syncRatio = (double) synchronizedCount / totalComparisons;
        return syncRatio >= SYNC_THRESHOLD;
    }

    private boolean isWithinTolerance(long time1, long time2, long tolerance) {
        return Math.abs(time1 - time2) <= tolerance;
    }

    public double calculateTeamCheatScore(CheatingTeam team) {
        Set<UUID> members = team.getMembers();
        if (members.size() < MIN_TEAM_SIZE) {
            return 0.0;
        }

        double totalScore = 0.0;
        int comparisonCount = 0;

        List<UUID> memberList = new ArrayList<>(members);
        for (int i = 0; i < memberList.size(); i++) {
            for (int j = i + 1; j < memberList.size(); j++) {
                double pairScore = calculatePairCheatScore(memberList.get(i), memberList.get(j));
                totalScore += pairScore;
                comparisonCount++;
            }
        }

        double avgScore = comparisonCount > 0 ? totalScore / comparisonCount : 0.0;

        double syncScore = calculateSynchronizationScore(members);

        double networkScore = calculateNetworkScore(members);

        double teamScore = (avgScore * 0.4) + (syncScore * 0.4) + (networkScore * 0.2);

        return Math.min(1.0, teamScore);
    }

    private double calculatePairCheatScore(UUID player1, UUID player2) {
        double behaviorSim = similarityCalculator.calculateTwinScore(player1, player2);

        PlayerProfile profile1 = profileManager.getProfile(player1);
        PlayerProfile profile2 = profileManager.getProfile(player2);

        double riskScore1 = profile1 != null ? profile1.getRiskScore() : 0.0;
        double riskScore2 = profile2 != null ? profile2.getRiskScore() : 0.0;

        double combinedRisk = (riskScore1 + riskScore2) / 2.0;

        double socialScore = calculateSocialConnectionScore(player1, player2);

        return (behaviorSim * 0.5) + (combinedRisk * 0.3) + (socialScore * 0.2);
    }

    private double calculateSocialConnectionScore(UUID player1, UUID player2) {
        Set<SocialGraph.InteractionRecord> interactions1 = socialGraph.getInteractionHistory(player1)
            .stream()
            .collect(java.util.stream.Collectors.toSet());

        Set<SocialGraph.InteractionRecord> interactions2 = socialGraph.getInteractionHistory(player2)
            .stream()
            .collect(java.util.stream.Collectors.toSet());

        Set<UUID> commonConnections = new HashSet<>();
        for (SocialGraph.InteractionRecord record : interactions1) {
            UUID other = record.player1.equals(player1) ? record.player2 : record.player1;
            for (SocialGraph.InteractionRecord record2 : interactions2) {
                UUID other2 = record2.player1.equals(player2) ? record2.player2 : record2.player1;
                if (other.equals(other2)) {
                    commonConnections.add(other);
                }
            }
        }

        double connectionScore = Math.min(1.0, commonConnections.size() / 5.0);

        return connectionScore;
    }

    private double calculateSynchronizationScore(Set<UUID> members) {
        int syncEvents = 0;
        int totalPossible = 0;

        List<UUID> memberList = new ArrayList<>(members);

        for (int i = 0; i < memberList.size(); i++) {
            List<SynchronizationEvent> events1 = synchronizationEvents.get(memberList.get(i));
            if (events1 == null) continue;

            for (int j = i + 1; j < memberList.size(); j++) {
                List<SynchronizationEvent> events2 = synchronizationEvents.get(memberList.get(j));
                if (events2 == null) continue;

                for (SynchronizationEvent event1 : events1) {
                    for (SynchronizationEvent event2 : events2) {
                        if (isWithinTolerance(event1.timestamp, event2.timestamp, 500)) {
                            syncEvents++;
                        }
                        totalPossible++;
                    }
                }
            }
        }

        return totalPossible > 0 ? (double) syncEvents / totalPossible : 0.0;
    }

    private double calculateNetworkScore(Set<UUID> members) {
        double totalClustering = 0.0;
        int memberCount = 0;

        for (UUID member : members) {
            double clustering = socialGraph.getClusteringCoefficient(member);
            totalClustering += clustering;
            memberCount++;
        }

        double avgClustering = memberCount > 0 ? totalClustering / memberCount : 0.0;

        return avgClustering * 2;
    }

    private CheatingTeam analyzeTeam(SocialGraph.CheatingGroup group) {
        UUID teamId = UUID.randomUUID();
        Set<UUID> members = new HashSet<>(group.members);

        double cheatScore = calculateTeamCheatScore(teamId, members);

        CheatingTeam team = new CheatingTeam(teamId, members, cheatScore);

        analyzeCheatingPatterns(team);

        return team;
    }

    private double calculateTeamCheatScore(UUID teamId, Set<UUID> members) {
        double totalScore = 0.0;
        int comparisonCount = 0;

        List<UUID> memberList = new ArrayList<>(members);
        for (int i = 0; i < memberList.size(); i++) {
            for (int j = i + 1; j < memberList.size(); j++) {
                double pairScore = calculatePairCheatScore(memberList.get(i), memberList.get(j));
                totalScore += pairScore;
                comparisonCount++;
            }
        }

        return comparisonCount > 0 ? totalScore / comparisonCount : 0.0;
    }

    private void analyzeCheatingPatterns(CheatingTeam team) {
        Set<UUID> members = team.getMembers();

        boolean hasBehaviorSync = checkBehaviorSynchronization(members);
        if (hasBehaviorSync) {
            team.addCheatingPattern("synchronized_behavior");
        }

        boolean hasMovementSync = checkMovementSynchronization(members);
        if (hasMovementSync) {
            team.addCheatingPattern("synchronized_movement");
        }

        boolean hasCombatSync = checkCombatSynchronization(members);
        if (hasCombatSync) {
            team.addCheatingPattern("synchronized_combat");
        }

        boolean hasNamePattern = checkNamePatterns(members);
        if (hasNamePattern) {
            team.addCheatingPattern("name_pattern_match");
        }

        boolean hasIPCluster = checkIPClustering(members);
        if (hasIPCluster) {
            team.addCheatingPattern("ip_clustering");
        }
    }

    private boolean checkBehaviorSynchronization(Set<UUID> members) {
        return hasSynchronizedAnomalies(members);
    }

    private boolean checkMovementSynchronization(Set<UUID> members) {
        List<UUID> memberList = new ArrayList<>(members);
        for (int i = 0; i < memberList.size(); i++) {
            for (int j = i + 1; j < memberList.size(); j++) {
                double similarity = similarityCalculator.calculateCosineSimilarity(
                    memberList.get(i), memberList.get(j)
                );
                if (similarity >= 0.9) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkCombatSynchronization(Set<UUID> members) {
        return checkMovementSynchronization(members);
    }

    private boolean checkNamePatterns(Set<UUID> members) {
        Map<String, Integer> namePrefixes = new HashMap<>();

        for (UUID member : members) {
            PlayerProfile profile = profileManager.getProfile(member);
            if (profile != null) {
                String name = profile.getPlayerName();
                if (name != null && name.length() >= 3) {
                    String prefix = name.substring(0, 3);
                    namePrefixes.put(prefix, namePrefixes.getOrDefault(prefix, 0) + 1);
                }
            }
        }

        for (int count : namePrefixes.values()) {
            if (count >= 2) {
                return true;
            }
        }

        return false;
    }

    private boolean checkIPClustering(Set<UUID> members) {
        Map<String, Integer> ipCounts = new HashMap<>();

        for (UUID member : members) {
            Set<UUID> neighbors = socialGraph.getNeighbors(member);
            for (UUID neighbor : neighbors) {
                if (members.contains(neighbor)) {
                    String edgeKey = createEdgeKey(member, neighbor);
                    ipCounts.put(edgeKey, ipCounts.getOrDefault(edgeKey, 0) + 1);
                }
            }
        }

        for (int count : ipCounts.values()) {
            if (count >= 3) {
                return true;
            }
        }

        return false;
    }

    private String createEdgeKey(UUID player1, UUID player2) {
        String id1 = player1.toString();
        String id2 = player2.toString();
        return id1.compareTo(id2) < 0 ? id1 + "-" + id2 : id2 + "-" + id1;
    }

    public void recordSynchronizationEvent(UUID playerUUID, long timestamp, String eventType) {
        SynchronizationEvent event = new SynchronizationEvent(timestamp, eventType);

        synchronizationEvents.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(event);

        List<SynchronizationEvent> events = synchronizationEvents.get(playerUUID);
        if (events.size() > 1000) {
            events.remove(0);
        }
    }

    public Map<UUID, CheatingTeam> getDetectedTeams() {
        return new HashMap<>(detectedTeams);
    }

    public CheatingTeam getTeam(UUID teamId) {
        return detectedTeams.get(teamId);
    }

    public static class SynchronizationEvent {
        public final long timestamp;
        public final String eventType;

        public SynchronizationEvent(long timestamp, String eventType) {
            this.timestamp = timestamp;
            this.eventType = eventType;
        }
    }
}
