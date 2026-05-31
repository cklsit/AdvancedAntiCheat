package com.anticheat.detection.fusion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProbabilityFusionEngine {
    
    private final Map<UUID, Map<String, Double>> playerProbabilities;
    private final Map<String, Double> moduleWeights;
    private final BayesianNetwork bayesianNetwork;
    private final Map<UUID, Long> lastUpdateTime;
    private final Map<UUID, Double> rcpCache;
    
    private static final long CACHE_EXPIRY_MS = 5000;
    private static final double DEFAULT_WEIGHT = 1.0;
    private static final double MIN_PROBABILITY = 0.0;
    private static final double MAX_PROBABILITY = 1.0;
    
    public ProbabilityFusionEngine() {
        this.playerProbabilities = new ConcurrentHashMap<>();
        this.moduleWeights = new ConcurrentHashMap<>();
        this.bayesianNetwork = new BayesianNetwork();
        this.lastUpdateTime = new ConcurrentHashMap<>();
        this.rcpCache = new ConcurrentHashMap<>();
        initializeDefaultWeights();
    }
    
    private void initializeDefaultWeights() {
        moduleWeights.put("fly", 1.2);
        moduleWeights.put("speed", 1.1);
        moduleWeights.put("reach", 1.0);
        moduleWeights.put("aimbot", 1.3);
        moduleWeights.put("killaura", 1.2);
        moduleWeights.put("autoclicker", 1.0);
        moduleWeights.put("scaffold", 0.9);
        moduleWeights.put("noslow", 0.8);
        moduleWeights.put("combat", 1.1);
        moduleWeights.put("movement", 1.0);
        moduleWeights.put("packetAnalysis", 1.2);
        moduleWeights.put("behaviorAnalysis", 1.0);
    }
    
    public void addModuleProbability(String moduleName, double probability) {
        if (probability < MIN_PROBABILITY || probability > MAX_PROBABILITY) {
            throw new IllegalArgumentException("Probability must be between 0.0 and 1.0");
        }
        bayesianNetwork.addEvidence(moduleName, probability);
    }
    
    public void addPlayerProbability(UUID playerUUID, String moduleName, double probability) {
        if (probability < MIN_PROBABILITY || probability > MAX_PROBABILITY) {
            throw new IllegalArgumentException("Probability must be between 0.0 and 1.0");
        }
        
        playerProbabilities
            .computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>())
            .put(moduleName, probability);
        
        lastUpdateTime.put(playerUUID, System.currentTimeMillis());
        rcpCache.remove(playerUUID);
    }
    
    public double calculateFusedProbability() {
        Map<String, Double> evidence = bayesianNetwork.getEvidence();
        
        if (evidence.isEmpty()) {
            return 0.0;
        }
        
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        
        for (Map.Entry<String, Double> entry : evidence.entrySet()) {
            String moduleName = entry.getKey();
            double probability = entry.getValue();
            double weight = moduleWeights.getOrDefault(moduleName, DEFAULT_WEIGHT);
            
            weightedSum += probability * weight;
            totalWeight += weight;
        }
        
        if (totalWeight == 0) {
            return 0.0;
        }
        
        double fusedProbability = weightedSum / totalWeight;
        
        fusedProbability = applyConditionalDependencies(fusedProbability);
        
        return clampProbability(fusedProbability);
    }
    
    public double getRCP(UUID playerUUID) {
        Long lastUpdate = lastUpdateTime.get(playerUUID);
        if (lastUpdate != null) {
            long timeSinceUpdate = System.currentTimeMillis() - lastUpdate;
            if (timeSinceUpdate < CACHE_EXPIRY_MS) {
                Double cached = rcpCache.get(playerUUID);
                if (cached != null) {
                    return cached;
                }
            }
        }
        
        double rcp = computePlayerRCP(playerUUID);
        rcpCache.put(playerUUID, rcp);
        
        return rcp;
    }
    
    private double computePlayerRCP(UUID playerUUID) {
        Map<String, Double> probabilities = playerProbabilities.get(playerUUID);
        
        if (probabilities == null || probabilities.isEmpty()) {
            return 0.0;
        }
        
        bayesianNetwork.clearEvidence();
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            bayesianNetwork.addEvidence(entry.getKey(), entry.getValue());
        }
        
        double bayesianProb = bayesianNetwork.getRealTimeCheatingProbability();
        
        double fusedProb = calculateFusedProbabilityWithPlayerData(playerUUID, probabilities);
        
        double combined = (bayesianProb + fusedProb) / 2.0;
        
        combined = applyConditionalDependencies(combined);
        
        return clampProbability(combined);
    }
    
    private double calculateFusedProbabilityWithPlayerData(UUID playerUUID, Map<String, Double> probabilities) {
        if (probabilities.isEmpty()) {
            return 0.0;
        }
        
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            String moduleName = entry.getKey();
            double probability = entry.getValue();
            double weight = moduleWeights.getOrDefault(moduleName, DEFAULT_WEIGHT);
            
            weightedSum += probability * weight;
            totalWeight += weight;
        }
        
        if (totalWeight == 0) {
            return 0.0;
        }
        
        return weightedSum / totalWeight;
    }
    
    public double applyConditionalDependencies(double baseProbability) {
        double modifier = 1.0;
        
        Map<String, Double> evidence = bayesianNetwork.getEvidence();
        
        if (evidence.containsKey("fly") && evidence.containsKey("speed")) {
            modifier *= 1.15;
        }
        
        if (evidence.containsKey("killaura") && evidence.containsKey("aimbot")) {
            modifier *= 1.2;
        }
        
        if (evidence.containsKey("reach") && evidence.containsKey("combat")) {
            modifier *= 1.1;
        }
        
        if (evidence.containsKey("scaffold") && evidence.containsKey("noslow")) {
            modifier *= 1.05;
        }
        
        double avgEvidence = evidence.values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        
        if (avgEvidence > 0.7) {
            modifier *= 1.1;
        }
        
        if (evidence.size() >= 3) {
            modifier *= 1.05;
        }
        
        double adjusted = baseProbability * modifier;
        return clampProbability(adjusted);
    }
    
    public void updateModuleWeight(String moduleName, double weight) {
        moduleWeights.put(moduleName, Math.max(0.1, Math.min(3.0, weight)));
        rcpCache.clear();
    }
    
    public double getModuleWeight(String moduleName) {
        return moduleWeights.getOrDefault(moduleName, DEFAULT_WEIGHT);
    }
    
    public Map<String, Double> getPlayerProbabilities(UUID playerUUID) {
        Map<String, Double> probs = playerProbabilities.get(playerUUID);
        return probs != null ? new HashMap<>(probs) : new HashMap<>();
    }
    
    public void clearPlayerData(UUID playerUUID) {
        playerProbabilities.remove(playerUUID);
        lastUpdateTime.remove(playerUUID);
        rcpCache.remove(playerUUID);
    }
    
    public void clearAllData() {
        playerProbabilities.clear();
        lastUpdateTime.clear();
        rcpCache.clear();
        bayesianNetwork.clearEvidence();
    }
    
    public int getPlayerModuleCount(UUID playerUUID) {
        Map<String, Double> probs = playerProbabilities.get(playerUUID);
        return probs != null ? probs.size() : 0;
    }
    
    public boolean hasPlayerData(UUID playerUUID) {
        return playerProbabilities.containsKey(playerUUID);
    }
    
    private double clampProbability(double probability) {
        return Math.max(MIN_PROBABILITY, Math.min(MAX_PROBABILITY, probability));
    }
    
    public BayesianNetwork getBayesianNetwork() {
        return bayesianNetwork;
    }
    
    public Set<String> getRegisteredModules() {
        return new HashSet<>(moduleWeights.keySet());
    }
}
