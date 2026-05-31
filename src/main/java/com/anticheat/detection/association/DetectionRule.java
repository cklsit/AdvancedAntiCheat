package com.anticheat.detection.association;

import java.io.Serializable;
import java.util.*;

public class DetectionRule implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String ruleId;
    private final String ruleName;
    private final String description;
    private final RuleType type;
    private final double threshold;
    private final long creationTime;
    private final Map<String, Object> parameters;
    private boolean active;

    public DetectionRule(String ruleId, String ruleName, String description,
                        RuleType type, double threshold) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.description = description;
        this.type = type;
        this.threshold = threshold;
        this.creationTime = System.currentTimeMillis();
        this.parameters = new HashMap<>();
        this.active = true;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getDescription() {
        return description;
    }

    public RuleType getType() {
        return type;
    }

    public double getThreshold() {
        return threshold;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public Map<String, Object> getParameters() {
        return new HashMap<>(parameters);
    }

    public void setParameter(String key, Object value) {
        parameters.put(key, value);
    }

    public Object getParameter(String key) {
        return parameters.get(key);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean evaluate(double value) {
        return value >= threshold;
    }

    public enum RuleType {
        BEHAVIOR_ANOMALY("行为异常检测"),
        PATTERN_MATCH("模式匹配"),
        THRESHOLD_BASED("阈值检测"),
        CORRELATION("关联检测"),
        SEQUENCE_ANALYSIS("序列分析");

        private final String description;

        RuleType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public static class RuleBuilder {
        private String ruleId;
        private String ruleName;
        private String description;
        private RuleType type;
        private double threshold;
        private Map<String, Object> parameters = new HashMap<>();

        public RuleBuilder setRuleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public RuleBuilder setRuleName(String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        public RuleBuilder setDescription(String description) {
            this.description = description;
            return this;
        }

        public RuleBuilder setType(RuleType type) {
            this.type = type;
            return this;
        }

        public RuleBuilder setThreshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        public RuleBuilder addParameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public DetectionRule build() {
            DetectionRule rule = new DetectionRule(
                ruleId, ruleName, description, type, threshold
            );
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                rule.setParameter(entry.getKey(), entry.getValue());
            }
            return rule;
        }
    }
}
