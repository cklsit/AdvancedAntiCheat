package com.anticheat.detection.association;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SocialGraph implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final double DEFAULT_EDGE_WEIGHT = 0.5;
    private static final int MIN_CLIQUE_SIZE = 3;

    private final Map<UUID, Map<UUID, Double>> adjacencyList;
    private final Map<UUID, Set<InteractionRecord>> interactionRecords;
    private final Map<Integer, Set<UUID>> communities;

    public SocialGraph() {
        this.adjacencyList = new ConcurrentHashMap<>();
        this.interactionRecords = new ConcurrentHashMap<>();
        this.communities = new ConcurrentHashMap<>();
    }

    public void addInteraction(UUID player1, UUID player2, InteractionType type) {
        double weight = type.getWeight();

        adjacencyList.computeIfAbsent(player1, k -> new ConcurrentHashMap<>())
                    .merge(player2, weight, (existing, newWeight) -> existing + newWeight);

        adjacencyList.computeIfAbsent(player2, k -> new ConcurrentHashMap<>())
                    .merge(player1, weight, (existing, newWeight) -> existing + newWeight);

        InteractionRecord record = new InteractionRecord(
            player1, player2, type, System.currentTimeMillis()
        );

        interactionRecords.computeIfAbsent(player1, k -> ConcurrentHashMap.newKeySet()).add(record);
        interactionRecords.computeIfAbsent(player2, k -> ConcurrentHashMap.newKeySet()).add(record);
    }

    public void addEdge(UUID player1, UUID player2, double weight) {
        adjacencyList.computeIfAbsent(player1, k -> new ConcurrentHashMap<>())
                    .put(player2, weight);

        adjacencyList.computeIfAbsent(player2, k -> new ConcurrentHashMap<>())
                    .put(player1, weight);
    }

    public List<Set<UUID>> findCliques(double threshold) {
        List<Set<UUID>> cliques = new ArrayList<>();
        Set<UUID> allPlayers = new HashSet<>(adjacencyList.keySet());
        List<UUID> playerList = new ArrayList<>(allPlayers);

        for (int i = 0; i < playerList.size(); i++) {
            Set<UUID> clique = new HashSet<>();
            clique.add(playerList.get(i));

            for (int j = i + 1; j < playerList.size(); j++) {
                UUID candidate = playerList.get(j);
                if (isConnectedToAll(candidate, clique, threshold)) {
                    clique.add(candidate);
                }
            }

            if (clique.size() >= MIN_CLIQUE_SIZE) {
                boolean isSubset = false;
                for (Set<UUID> existingClique : cliques) {
                    if (existingClique.containsAll(clique)) {
                        isSubset = true;
                        break;
                    }
                }

                if (!isSubset) {
                    cliques.removeIf(existing -> clique.containsAll(existing));
                    cliques.add(clique);
                }
            }
        }

        return cliques;
    }

    private boolean isConnectedToAll(UUID player, Set<UUID> clique, double threshold) {
        for (UUID member : clique) {
            Double weight = getEdgeWeight(player, member);
            if (weight == null || weight < threshold) {
                return false;
            }
        }
        return true;
    }

    public List<CheatingGroup> detectCheatingGroups() {
        List<CheatingGroup> groups = new ArrayList<>();

        List<Set<UUID>> cliques = findCliques(DEFAULT_EDGE_WEIGHT);

        for (Set<UUID> clique : cliques) {
            CheatingGroup group = analyzeClique(clique);
            if (group != null && group.getSuspicionScore() > 0.7) {
                groups.add(group);
            }
        }

        return groups;
    }

    private CheatingGroup analyzeClique(Set<UUID> clique) {
        if (clique.size() < MIN_CLIQUE_SIZE) {
            return null;
        }

        double totalWeight = 0.0;
        int edgeCount = 0;

        List<UUID> members = new ArrayList<>(clique);
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                Double weight = getEdgeWeight(members.get(i), members.get(j));
                if (weight != null) {
                    totalWeight += weight;
                    edgeCount++;
                }
            }
        }

        double averageWeight = edgeCount > 0 ? totalWeight / edgeCount : 0.0;
        double density = calculateDensity(clique);
        double suspicionScore = (averageWeight * 0.6) + (density * 0.4);

        return new CheatingGroup(new ArrayList<>(clique), suspicionScore, averageWeight, density);
    }

    private double calculateDensity(Set<UUID> clique) {
        int n = clique.size();
        int possibleEdges = (n * (n - 1)) / 2;

        if (possibleEdges == 0) {
            return 0.0;
        }

        int actualEdges = 0;
        List<UUID> members = new ArrayList<>(clique);

        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                if (hasEdge(members.get(i), members.get(j))) {
                    actualEdges++;
                }
            }
        }

        return (double) actualEdges / possibleEdges;
    }

    public boolean hasEdge(UUID player1, UUID player2) {
        Map<UUID, Double> edges = adjacencyList.get(player1);
        return edges != null && edges.containsKey(player2);
    }

    public Double getEdgeWeight(UUID player1, UUID player2) {
        Map<UUID, Double> edges = adjacencyList.get(player1);
        return edges != null ? edges.get(player2) : null;
    }

    public Set<UUID> getNeighbors(UUID player) {
        Map<UUID, Double> edges = adjacencyList.get(player);
        return edges != null ? edges.keySet() : Collections.emptySet();
    }

    public Set<UUID> getStrongNeighbors(UUID player, double minWeight) {
        Map<UUID, Double> edges = adjacencyList.get(player);
        if (edges == null) {
            return Collections.emptySet();
        }

        Set<UUID> strongNeighbors = new HashSet<>();
        for (Map.Entry<UUID, Double> entry : edges.entrySet()) {
            if (entry.getValue() >= minWeight) {
                strongNeighbors.add(entry.getKey());
            }
        }

        return strongNeighbors;
    }

    public double getClusteringCoefficient(UUID player) {
        Set<UUID> neighbors = getNeighbors(player);
        List<UUID> neighborList = new ArrayList<>(neighbors);
        int k = neighborList.size();

        if (k < 2) {
            return 0.0;
        }

        int edgesBetweenNeighbors = 0;
        for (int i = 0; i < neighborList.size(); i++) {
            for (int j = i + 1; j < neighborList.size(); j++) {
                if (hasEdge(neighborList.get(i), neighborList.get(j))) {
                    edgesBetweenNeighbors++;
                }
            }
        }

        int possibleEdges = (k * (k - 1)) / 2;
        return (double) edgesBetweenNeighbors / possibleEdges;
    }

    public List<Set<UUID>> detectCommunities() {
        Map<UUID, Integer> componentMap = new HashMap<>();
        int componentId = 0;

        Set<UUID> allPlayers = new HashSet<>(adjacencyList.keySet());

        for (UUID player : allPlayers) {
            if (!componentMap.containsKey(player)) {
                Set<UUID> component = new HashSet<>();
                Queue<UUID> queue = new LinkedList<>();
                queue.add(player);

                while (!queue.isEmpty()) {
                    UUID current = queue.poll();
                    if (componentMap.containsKey(current)) {
                        continue;
                    }

                    componentMap.put(current, componentId);
                    component.add(current);

                    Set<UUID> neighbors = getStrongNeighbors(current, DEFAULT_EDGE_WEIGHT);
                    for (UUID neighbor : neighbors) {
                        if (!componentMap.containsKey(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }

                if (component.size() >= MIN_CLIQUE_SIZE) {
                    communities.put(componentId, component);
                }

                componentId++;
            }
        }

        return new ArrayList<>(communities.values());
    }

    public List<InteractionRecord> getInteractionHistory(UUID player) {
        Set<InteractionRecord> records = interactionRecords.get(player);
        return records != null ? new ArrayList<>(records) : new ArrayList<>();
    }

    public static class InteractionRecord implements Serializable {
        private static final long serialVersionUID = 1L;

        public final UUID player1;
        public final UUID player2;
        public final InteractionType type;
        public final long timestamp;

        public InteractionRecord(UUID player1, UUID player2, InteractionType type, long timestamp) {
            this.player1 = player1;
            this.player2 = player2;
            this.type = type;
            this.timestamp = timestamp;
        }
    }

    public static class CheatingGroup implements Serializable {
        private static final long serialVersionUID = 1L;

        public final List<UUID> members;
        public final double suspicionScore;
        public final double averageEdgeWeight;
        public final double density;

        public CheatingGroup(List<UUID> members, double suspicionScore,
                            double averageEdgeWeight, double density) {
            this.members = new ArrayList<>(members);
            this.suspicionScore = suspicionScore;
            this.averageEdgeWeight = averageEdgeWeight;
            this.density = density;
        }

        public double getSuspicionScore() {
            return suspicionScore;
        }
    }
}
