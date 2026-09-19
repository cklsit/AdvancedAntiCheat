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
    /** SEQUENCE_ANALYSIS 规则的默认统计窗口（毫秒）。 */
    private static final long DEFAULT_SEQUENCE_WINDOW_MS = 300_000L;

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

    /** 包级可见：供单元测试直接验证各 RuleType 的判定语义。 */
    boolean evaluateRule(PlayerProfile profile, DetectionRule rule) {
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

    /**
     * 关联检测：多个指标同时越限（"多信号共振"）。
     *
     * <p>规则参数：</p>
     * <ul>
     *   <li>{@code metrics}（必需）——逗号分隔的指标名，取值同 {@link #getMetricValue}：
     *       {@code cps} / {@code turnSpeed} / {@code jumpInterval} / {@code interfaceAction} / {@code walkStayRatio}</li>
     *   <li>{@code thresholds}（必需）——与 metrics 逐项对应的下限，逗号分隔</li>
     *   <li>{@code minMatch}（可选，默认要求全部命中）——至少多少个指标越限才算命中</li>
     * </ul>
     *
     * <p>为什么是"同时越限"而不是相关系数：{@link PlayerProfile} 只保存各指标的均值与标准差，
     * 不保存原始时间序列，无法计算真实的相关系数。多指标共振是当前数据模型下可解释、
     * 可复核的关联语义——单个指标越限多为噪声，多个相互独立的指标同时越限才指向外挂。
     * 阈值全部由规则自描述，不在代码里写死统计常量。</p>
     */
    private boolean evaluateCorrelation(PlayerProfile profile, DetectionRule rule) {
        List<String> metrics = splitParam(rule.getParameter("metrics"));
        List<String> thresholds = splitParam(rule.getParameter("thresholds"));
        if (metrics.isEmpty() || metrics.size() != thresholds.size()) {
            return false;
        }

        int matches = 0;
        for (int i = 0; i < metrics.size(); i++) {
            Double limit = parseDouble(thresholds.get(i));
            if (limit == null) {
                continue;
            }
            if (getMetricValue(profile, metrics.get(i)) >= limit) {
                matches++;
            }
        }

        int required = intParam(rule.getParameter("minMatch"), metrics.size());
        return required > 0 && matches >= required;
    }

    /**
     * 序列分析：同一玩家在时间窗口内反复触发违规（"屡犯不改"）。
     *
     * <p>规则参数：</p>
     * <ul>
     *   <li>{@code windowMs}（可选，默认 300000 即 5 分钟）——统计窗口长度（毫秒）</li>
     *   <li>{@code minOccurrences}（可选，默认取规则的 threshold）——窗口内至少触发多少次违规</li>
     * </ul>
     *
     * <p>数据来源是本类自行维护的 {@link #violationCache}（带时间戳的违规流水），
     * 因此结果依赖"同一轮扫描中此前已命中的规则"——扫描顺序会影响判定，这是
     * {@link #scanHistory} 的既有语义（它按顺序逐条规则扫描并即时累积违规）。</p>
     */
    private boolean evaluateSequence(PlayerProfile profile, DetectionRule rule) {
        List<RuleViolation> history = violationCache.get(profile.getPlayerUUID());
        if (history == null || history.isEmpty()) {
            return false;
        }

        long windowMs = (long) intParam(rule.getParameter("windowMs"), (int) DEFAULT_SEQUENCE_WINDOW_MS);
        if (windowMs <= 0) {
            windowMs = DEFAULT_SEQUENCE_WINDOW_MS;
        }
        int required = intParam(rule.getParameter("minOccurrences"),
                (int) Math.max(1L, Math.round(rule.getThreshold())));
        if (required <= 0) {
            required = 1;
        }

        long since = System.currentTimeMillis() - windowMs;
        int count = 0;
        synchronized (history) {
            for (RuleViolation violation : history) {
                if (violation.getTimestamp() >= since) {
                    count++;
                }
            }
        }
        return count >= required;
    }

    /** 把规则参数（逗号分隔字符串或集合）拆成去空项的列表。 */
    private static List<String> splitParam(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    out.add(item.toString().trim());
                }
            }
            return out;
        }
        for (String segment : raw.toString().split(",")) {
            if (!segment.trim().isEmpty()) {
                out.add(segment.trim());
            }
        }
        return out;
    }

    private static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null; // 配置写错时跳过该项，不中断整轮扫描
        }
    }

    private static int intParam(Object raw, int def) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                return def; // 配置写错时退回默认值
            }
        }
        return def;
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

    /** 包级可见：供单元测试构造违规流水，验证 SEQUENCE_ANALYSIS 的判定语义。 */
    void addViolation(UUID playerUUID, DetectionRule rule) {
        RuleViolation violation = new RuleViolation(
            rule.getRuleId(),
            rule.getRuleName(),
            System.currentTimeMillis(),
            1.0
        );

        // batchScan 跑在异步线程，此前这里直接对普通 ArrayList 做 add：
        // 与 evaluateSequence 的读取并发时会抛 ConcurrentModificationException 或丢数据。
        List<RuleViolation> list = violationCache.computeIfAbsent(playerUUID,
            k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(violation);
        }
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
