package com.anticheat.profiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class RiskHistory implements Serializable {
    private static final long serialVersionUID = 1L;

    private double riskScore;
    private long lastRiskUpdate;
    private List<ViolationRecord> violations;
    private List<CaptchaRecord> captchaTrials;

    public RiskHistory() {
        this.riskScore = 0.0;
        this.lastRiskUpdate = System.currentTimeMillis();
        this.violations = new ArrayList<>();
        this.captchaTrials = new ArrayList<>();
    }

    public void addViolation(String rule, int severity, String penalty, boolean falsePositive, String executor) {
        violations.add(new ViolationRecord(System.currentTimeMillis(), rule, severity, penalty, falsePositive, executor));
        updateRiskScore(severity);
    }

    public void addCaptchaTrial(String reason, boolean passed) {
        captchaTrials.add(new CaptchaRecord(System.currentTimeMillis(), reason, passed));
    }

    private void updateRiskScore(int severity) {
        this.riskScore = Math.min(1000.0, this.riskScore + severity * 10);
        this.lastRiskUpdate = System.currentTimeMillis();
    }

    public void decayRiskScore() {
        long hoursSinceUpdate = (System.currentTimeMillis() - lastRiskUpdate) / (1000 * 60 * 60);
        if (hoursSinceUpdate > 0) {
            double decay = hoursSinceUpdate * 5.0;
            this.riskScore = Math.max(0.0, this.riskScore - decay);
        }
    }

    public static class ViolationRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public long timestamp;
        public String rule;
        public int severity;
        public String penalty;
        public boolean falsePositive;
        public String executor;

        public ViolationRecord(long timestamp, String rule, int severity, String penalty, boolean falsePositive, String executor) {
            this.timestamp = timestamp;
            this.rule = rule;
            this.severity = severity;
            this.penalty = penalty;
            this.falsePositive = falsePositive;
            this.executor = executor;
        }
    }

    public static class CaptchaRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public long timestamp;
        public String reason;
        public boolean passed;

        public CaptchaRecord(long timestamp, String reason, boolean passed) {
            this.timestamp = timestamp;
            this.reason = reason;
            this.passed = passed;
        }
    }

    public double getRiskScore() { return riskScore; }
    public List<ViolationRecord> getViolations() { return violations; }
    public List<CaptchaRecord> getCaptchaTrials() { return captchaTrials; }
}

