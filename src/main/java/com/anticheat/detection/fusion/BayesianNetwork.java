package com.anticheat.detection.fusion;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BayesianNetwork {
    
    private final Map<String, Double> evidence;
    private final Map<String, Map<String, Double>> conditionalProbabilities;
    private final Map<String, Double> priorProbabilities;
    private final Map<String, Double> hypothesisWeights;
    
    private static final double DEFAULT_PRIOR = 0.1;
    private static final double MIN_PROBABILITY = 0.001;
    private static final double MAX_PROBABILITY = 0.999;
    
    public BayesianNetwork() {
        this.evidence = new ConcurrentHashMap<>();
        this.conditionalProbabilities = new ConcurrentHashMap<>();
        this.priorProbabilities = new ConcurrentHashMap<>();
        this.hypothesisWeights = new ConcurrentHashMap<>();
        initializeDefaultPriors();
    }
    
    private void initializeDefaultPriors() {
        priorProbabilities.put("fly", 0.08);
        priorProbabilities.put("speed", 0.10);
        priorProbabilities.put("reach", 0.07);
        priorProbabilities.put("aimbot", 0.06);
        priorProbabilities.put("killaura", 0.09);
        priorProbabilities.put("autoclicker", 0.08);
        priorProbabilities.put("scaffold", 0.05);
        priorProbabilities.put("noslow", 0.04);
        
        hypothesisWeights.put("fly", 1.0);
        hypothesisWeights.put("speed", 1.0);
        hypothesisWeights.put("reach", 1.0);
        hypothesisWeights.put("aimbot", 1.0);
        hypothesisWeights.put("killaura", 1.0);
        hypothesisWeights.put("autoclicker", 1.0);
        hypothesisWeights.put("scaffold", 1.0);
        hypothesisWeights.put("noslow", 1.0);
    }
    
    public void addEvidence(String moduleName, double probability) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("Probability must be between 0.0 and 1.0");
        }
        evidence.put(moduleName, probability);
    }
    
    public double calculatePosterior(String hypothesis) {
        double prior = priorProbabilities.getOrDefault(hypothesis, DEFAULT_PRIOR);
        double likelihood = calculateLikelihood(hypothesis);
        
        double numerator = likelihood * prior;
        double denominator = calculateEvidence();
        
        if (denominator == 0) {
            return prior;
        }
        
        double posterior = numerator / denominator;
        return clampProbability(posterior);
    }
    
    private double calculateLikelihood(String hypothesis) {
        double likelihood = 1.0;
        double weight = hypothesisWeights.getOrDefault(hypothesis, 1.0);
        
        for (Map.Entry<String, Double> entry : evidence.entrySet()) {
            String moduleName = entry.getKey();
            double evidenceProb = entry.getValue();
            
            double conditionalProb = getConditionalProbability(moduleName, hypothesis);
            likelihood *= (conditionalProb * evidenceProb + 0.0001);
        }
        
        return likelihood * weight;
    }
    
    private double getConditionalProbability(String moduleName, String hypothesis) {
        Map<String, Double> conditionals = conditionalProbabilities.get(moduleName);
        if (conditionals != null && conditionals.containsKey(hypothesis)) {
            return conditionals.get(hypothesis);
        }
        return 0.5;
    }
    
    public void setConditionalProbability(String moduleName, String hypothesis, double probability) {
        conditionalProbabilities
            .computeIfAbsent(moduleName, k -> new ConcurrentHashMap<>())
            .put(hypothesis, probability);
    }
    
    private double calculateEvidence() {
        double evidence = 1.0;
        for (Map.Entry<String, Double> entry : this.evidence.entrySet()) {
            evidence *= (entry.getValue() + 0.0001);
        }
        return evidence + 0.001;
    }
    
    public double getRealTimeCheatingProbability() {
        if (evidence.isEmpty()) {
            return 0.0;
        }
        
        double fusedProbability = 0.0;
        int count = 0;
        
        for (Map.Entry<String, Double> entry : evidence.entrySet()) {
            double moduleProb = entry.getValue();
            String moduleName = entry.getKey();
            
            double weight = hypothesisWeights.getOrDefault(moduleName, 1.0);
            double weightedProb = moduleProb * weight;
            
            fusedProbability += weightedProb;
            count++;
        }
        
        if (count == 0) {
            return 0.0;
        }
        
        fusedProbability /= count;
        
        fusedProbability = applyBayesianAdjustment(fusedProbability);
        
        return clampProbability(fusedProbability);
    }
    
    private double applyBayesianAdjustment(double probability) {
        double alpha = 0.7;
        double beta = 0.3;
        
        double adjusted = alpha * probability + beta * getAverageEvidence();
        
        return clampProbability(adjusted);
    }
    
    private double getAverageEvidence() {
        if (evidence.isEmpty()) {
            return 0.0;
        }
        
        double sum = 0.0;
        for (Double prob : evidence.values()) {
            sum += prob;
        }
        
        return sum / evidence.size();
    }
    
    public void clearEvidence() {
        evidence.clear();
    }
    
    public Map<String, Double> getEvidence() {
        return new HashMap<>(evidence);
    }
    
    public void updatePriorProbability(String hypothesis, double prior) {
        priorProbabilities.put(hypothesis, clampProbability(prior));
    }
    
    public void updateHypothesisWeight(String hypothesis, double weight) {
        hypothesisWeights.put(hypothesis, Math.max(0.1, Math.min(2.0, weight)));
    }
    
    public double getPriorProbability(String hypothesis) {
        return priorProbabilities.getOrDefault(hypothesis, DEFAULT_PRIOR);
    }
    
    public void setPriorProbability(String hypothesis, double prior) {
        priorProbabilities.put(hypothesis, clampProbability(prior));
    }
    
    private double clampProbability(double probability) {
        return Math.max(MIN_PROBABILITY, Math.min(MAX_PROBABILITY, probability));
    }
    
    public int getEvidenceCount() {
        return evidence.size();
    }
    
    public boolean hasEvidence(String moduleName) {
        return evidence.containsKey(moduleName);
    }
}
