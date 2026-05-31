package com.anticheat.detection.association;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ProfileManager;
import com.anticheat.profiles.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class OfflineRuleScanner {

    private static final int BATCH_SIZE = 50;
    private static final long SCAN_INTERVAL = 60000L;

    private final AdvancedAntiCheat plugin;
    private final ProfileManager profileManager;
    private final Map<String, DetectionRule> activeRules;
    private final Map<UUID, List<RuleViolation>> violationCache;
    private final ScanStatistics statistics;

    public OfflineRuleScanner(AdvancedAntiCheat plugin, ProfileManager profileManager) {
        this.plugin = plugin;
        this.profileManager = profileManager;
        this.activeRules = new ConcurrentHashMap<>();
        this.violationCache = new ConcurrentHashMap<>();
        this.statistics = new ScanStatistics();
    }

    public List<UUID> scanHistory(DetectionRule newRule) {
        if (!newRule.isActive()) {
            return new ArrayList<>();
        }

        activeRules.put(newRule.getRuleId(), newRule);

        List<UUID> flaggedPlayers = Collections.synchronizedList(new ArrayList<>());

        Collection<PlayerProfile> allProfiles = profileManager.getCachedProfiles().values();

        for (PlayerProfile profile : allProfiles) {
            if (evaluateRule(profile, newRule)) {
                UUID playerUUID = profile.getPlayerUUID();
                flaggedPlayers.add(playerUUID);

                addViolation(playerUUID, newRule);

                statistics.recordViolation();
            }

            statistics.incrementScanned();
        }

        return flaggedPlayers;
    }

    private boolean evaluateRule(PlayerProfile profile, DetectionRule rule) {
        switch (rule.getType()) {
            case BEHAVIOR_ANOMALY:
                return evaluateBehaviorAnomaly(profile, rule);
            case PATTERN_MATCH:
                return evaluatePatternMatch(profile, rule);
            case THRESHOLD_BASED:
                return evaluateThreshold(profile, rule);
            case CORRELATION:
                return evaluateCorrelation(profile, rule);
            case SEQUENCE_ANALYSIS:
                return evaluateSequence(profile, rule);
            default:
                return false;
        }
    }

    private boolean evaluateBehaviorAnomaly(PlayerProfile profile, DetectionRule rule) {
        double cpsMean = profile.getCpsMean();
        double cpsStdDev = profile.getCpsStdDev();

        if (cpsMean <= 0 || cpsStdDev <= 0) {
            return false;
        }

        double zScore = Math.abs(cpsMean - getExpectedCPS()) / cpsStdDev;
        return rule.evaluate(zScore);
    }

    private double getExpectedCPS() {
        Object expected = activeRules.values().stream()
            .filter(r -> r.getRuleId().contains("cps"))
            .findFirst()
            .map(r -> r.getParameter("expectedCPS"))
            .orElse(8.0);

        return expected instanceof Number ? ((Number) expected).doubleValue() : 8.0;
    }

    private boolean evaluatePatternMatch(PlayerProfile profile, DetectionRule rule) {
        Object patternObj = rule.getParameter("pattern");
        if (patternObj == null) {
            return false;
        }

        String pattern = patternObj.toString();

        if (pattern.equals("consistent_cps")) {
            return checkConsistentCPS(profile, rule);
        } else if (pattern.equals("perfect_timing")) {
            return checkPerfectTiming(profile, rule);
        } else if (pattern.equals("rigid_movement")) {
            return checkRigidMovement(profile, rule);
        }

        return false;
    }

    private boolean checkConsistentCPS(PlayerProfile profile, DetectionRule rule) {
        double stdDev = profile.getCpsStdDev();
        double mean = profile.getCpsMean();

        if (mean <= 0) {
            return false;
        }

        double coefficientOfVariation = stdDev / mean;
        return coefficientOfVariation < rule.getThreshold();
    }

    private boolean checkPerfectTiming(PlayerProfile profile, DetectionRule rule) {
        double intervalMean = profile.getJumpIntervalMean();
        double intervalStdDev = profile.getJumpIntervalStdDev();

        if (intervalMean <= 0 || intervalStdDev <= 0) {
            return false;
        }

        double coefficientOfVariation = intervalStdDev / intervalMean;
        return coefficientOfVariation < rule.getThreshold();
    }

    private boolean checkRigidMovement(PlayerProfile profile, DetectionRule rule) {
        double turnSpeedStdDev = profile.getTurnSpeedStdDev();
        double turnSpeedMean = profile.getTurnSpeedMean();

        if (turnSpeedMean <= 0 || turnSpeedStdDev <= 0) {
            return false;
        }

        double coefficientOfVariation = turnSpeedStdDev / turnSpeedMean;
        return coefficientOfVariation < rule.getThreshold();
    }

    private boolean evaluateThreshold(PlayerProfile profile, DetectionRule rule) {
        Object metricObj = rule.getParameter("metric");
        if (metricObj == null) {
            return false;
        }

        String metric = metricObj.toString();
        double value = getMetricValue(profile, metric);

        return rule.evaluate(value);
    }

    private double getMetricValue(PlayerProfile profile, String metric) {
        switch (metric) {
            case "cps":
                return profile.getCpsMean();
            case "turnSpeed":
                return profile.getTurnSpeedMean();
            case "jumpInterval":
                return profile.getJumpIntervalMean();
            case "interfaceAction":
                return profile.getInterfaceActionMean();
            case "walkStayRatio":
                return profile.getWalkStayRatioMean();
            default:
                return 0.0;
        }
    }

    private boolean evaluateCorrelation(PlayerProfile profile, DetectionRule rule) {
        return false;
    }

    private boolean evaluateSequence(PlayerProfile profile, DetectionRule rule) {
        return false;
    }

    public void batchScan(List<DetectionRule> rules) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (DetectionRule rule : rules) {
                    scanHistory(rule);
                }

                plugin.getLogger().info("批量扫描完成，共扫描 " + statistics.getScannedCount() +
                                       " 个玩家，发现 " + statistics.getViolationCount() +
                                       " 个违规");
            }
        }.runTaskAsynchronously(plugin);
    }

    public void reportResults(List<UUID> flaggedPlayers) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID playerUUID : flaggedPlayers) {
                    List<RuleViolation> violations = violationCache.get(playerUUID);

                    if (violations != null && !violations.isEmpty()) {
                        generatePlayerReport(playerUUID, violations);
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void generatePlayerReport(UUID playerUUID, List<RuleViolation> violations) {
        PlayerProfile profile = profileManager.getProfile(playerUUID);
        if (profile == null) {
            return;
        }

        String playerName = profile.getPlayerName();
        if (playerName == null) {
            playerName = playerUUID.toString();
        }

        double avgViolationScore = violations.stream()
            .mapToDouble(RuleViolation::getScore)
            .average()
            .orElse(0.0);

        plugin.getLogger().warning("[离线扫描] 玩家 " + playerName + " (UUID: " + playerUUID +
                                   ") 触发 " + violations.size() + " 条规则，违规分数: " +
                                   String.format("%.2f", avgViolationScore));
    }

    private void addViolation(UUID playerUUID, DetectionRule rule) {
        RuleViolation violation = new RuleViolation(
            rule.getRuleId(),
            rule.getRuleName(),
            System.currentTimeMillis(),
            1.0
        );

        violationCache.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(violation);
    }

    public void startScheduledScans() {
        Bukkit.getScheduler().runTaskTimer(plugin, new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeRules.isEmpty()) {
                    List<DetectionRule> rules = new ArrayList<>(activeRules.values());
                    batchScan(rules);
                }
            }
        }, SCAN_INTERVAL, SCAN_INTERVAL);
    }

    public void stopScheduledScans() {
        Bukkit.getScheduler().cancelTasks(plugin);
    }

    public void addRule(DetectionRule rule) {
        activeRules.put(rule.getRuleId(), rule);
    }

    public void removeRule(String ruleId) {
        activeRules.remove(ruleId);
    }

    public List<RuleViolation> getViolations(UUID playerUUID) {
        return new ArrayList<>(violationCache.getOrDefault(playerUUID, new ArrayList<>()));
    }

    public Map<String, DetectionRule> getActiveRules() {
        return new HashMap<>(activeRules);
    }

    public ScanStatistics getStatistics() {
        return statistics;
    }

    public static class RuleViolation implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String ruleId;
        private final String ruleName;
        private final long timestamp;
        private final double score;

        public RuleViolation(String ruleId, String ruleName, long timestamp, double score) {
            this.ruleId = ruleId;
            this.ruleName = ruleName;
            this.timestamp = timestamp;
            this.score = score;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getRuleName() {
            return ruleName;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public double getScore() {
            return score;
        }
    }

    public static class ScanStatistics {
        private long scannedCount;
        private long violationCount;
        private long lastScanTime;

        public synchronized void incrementScanned() {
            scannedCount++;
        }

        public synchronized void recordViolation() {
            violationCount++;
        }

        public synchronized void setLastScanTime(long time) {
            lastScanTime = time;
        }

        public long getScannedCount() {
            return scannedCount;
        }

        public long getViolationCount() {
            return violationCount;
        }

        public long getLastScanTime() {
            return lastScanTime;
        }

        public void reset() {
            scannedCount = 0;
            violationCount = 0;
            lastScanTime = System.currentTimeMillis();
        }
    }
}
