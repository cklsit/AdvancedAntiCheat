package com.anticheat.detection.association;

import com.anticheat.profiles.PlayerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线规则扫描器的规则判定契约。
 *
 * <p>为什么需要这个测试：{@code evaluateCorrelation} 与 {@code evaluateSequence}
 * 此前是恒 {@code return false} 的桩函数——{@link DetectionRule.RuleType} 声明了
 * "关联检测"与"序列分析"两类规则，但它们在 {@code scanHistory} 中永远不会命中，
 * 属于"配置/枚举承诺了能力、实现却是空转"。
 *
 * <p>本测试锁定这两类规则的判定语义，并顺带覆盖参数缺失、长度不匹配等输入异常。
 * 不触碰任何 Bukkit API，可脱离服务端运行。</p>
 */
class OfflineRuleScannerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** 构造函数只做字段赋值，传 null 依赖即可脱离服务端构造实例。 */
    private static OfflineRuleScanner newScanner() {
        return new OfflineRuleScanner(null, null);
    }

    private static PlayerProfile profileWithCps(double cps) {
        PlayerProfile profile = new PlayerProfile(PLAYER, "tester");
        for (int i = 0; i < 5; i++) {
            profile.updateCPS(cps); // 多次写入让均值稳定
        }
        return profile;
    }

    private static PlayerProfile profileWithCpsAndTurnSpeed(double cps, double turnSpeed) {
        PlayerProfile profile = profileWithCps(cps);
        for (int i = 0; i < 5; i++) {
            profile.updateTurnSpeed(turnSpeed);
        }
        return profile;
    }

    private static DetectionRule correlationRule(String metrics, String thresholds, Integer minMatch) {
        DetectionRule.RuleBuilder builder = new DetectionRule.RuleBuilder()
                .setRuleId("corr-test")
                .setRuleName("关联检测")
                .setDescription("多指标同时越限")
                .setType(DetectionRule.RuleType.CORRELATION)
                .setThreshold(0.0)
                .addParameter("metrics", metrics)
                .addParameter("thresholds", thresholds);
        if (minMatch != null) {
            builder.addParameter("minMatch", minMatch);
        }
        return builder.build();
    }

    private static DetectionRule sequenceRule(int minOccurrences) {
        return new DetectionRule.RuleBuilder()
                .setRuleId("seq-test")
                .setRuleName("序列分析")
                .setDescription("窗口内反复违规")
                .setType(DetectionRule.RuleType.SEQUENCE_ANALYSIS)
                .setThreshold(1.0)
                .addParameter("minOccurrences", minOccurrences)
                .build();
    }

    // ==================== CORRELATION ====================

    @Test
    @DisplayName("关联检测：多个指标同时越限即命中")
    void correlationHitsWhenMultipleMetricsExceed() {
        OfflineRuleScanner scanner = newScanner();
        PlayerProfile profile = profileWithCpsAndTurnSpeed(20.0, 500.0);

        // 默认要求 metrics 全部命中
        assertTrue(scanner.evaluateRule(profile, correlationRule("cps,turnSpeed", "15.0,300.0", null)),
                "cps 与 turnSpeed 都越限，应命中");
    }

    @Test
    @DisplayName("关联检测：仅部分指标越限时不命中（单点噪声不足为证）")
    void correlationMissesWhenOnlyOneMetricExceeds() {
        OfflineRuleScanner scanner = newScanner();
        PlayerProfile profile = profileWithCpsAndTurnSpeed(20.0, 100.0);

        assertFalse(scanner.evaluateRule(profile, correlationRule("cps,turnSpeed", "15.0,300.0", null)),
                "只有 cps 越限，默认要求全部命中，应不命中");
    }

    @Test
    @DisplayName("关联检测：minMatch 可放宽命中条件")
    void correlationHonoursMinMatch() {
        OfflineRuleScanner scanner = newScanner();
        PlayerProfile profile = profileWithCpsAndTurnSpeed(20.0, 100.0);

        assertTrue(scanner.evaluateRule(profile, correlationRule("cps,turnSpeed", "15.0,300.0", 1)),
                "minMatch=1 时单项越限即命中");
        assertFalse(scanner.evaluateRule(profile, correlationRule("cps,turnSpeed", "15.0,300.0", 2)),
                "minMatch=2 时单项越限不命中");
    }

    @Test
    @DisplayName("关联检测：参数缺失或长度不匹配时安全返回 false")
    void correlationHandlesBadParameters() {
        OfflineRuleScanner scanner = newScanner();
        PlayerProfile profile = profileWithCps(20.0);

        assertFalse(scanner.evaluateRule(profile, correlationRule(null, "15.0", null)),
                "缺少 metrics 应返回 false");
        assertFalse(scanner.evaluateRule(profile, correlationRule("cps", null, null)),
                "缺少 thresholds 应返回 false");
        assertFalse(scanner.evaluateRule(profile, correlationRule("cps,turnSpeed", "15.0", null)),
                "metrics 与 thresholds 数量不匹配应返回 false");
    }

    @Test
    @DisplayName("关联检测：非法阈值文本不会中断扫描")
    void correlationSkipsUnparsableThreshold() {
        OfflineRuleScanner scanner = newScanner();
        PlayerProfile profile = profileWithCps(20.0);

        // "abc" 无法解析 → 跳过该项；cps=20 未达 999 → 不命中
        assertFalse(scanner.evaluateRule(profile, correlationRule("cps,unknown", "999.0,abc", null)));
    }

    // ==================== SEQUENCE_ANALYSIS ====================

    @Test
    @DisplayName("序列分析：无违规流水时不命中")
    void sequenceMissesWithoutHistory() {
        OfflineRuleScanner scanner = newScanner();
        assertFalse(scanner.evaluateRule(profileWithCps(8.0), sequenceRule(3)));
    }

    @Test
    @DisplayName("序列分析：窗口内违规次数达到阈值即命中")
    void sequenceHitsWhenOccurrencesReachThreshold() {
        OfflineRuleScanner scanner = newScanner();
        DetectionRule rule = sequenceRule(3);

        scanner.addViolation(PLAYER, rule);
        scanner.addViolation(PLAYER, rule);
        assertFalse(scanner.evaluateRule(profileWithCps(8.0), rule), "2 条流水未达阈值 3");

        scanner.addViolation(PLAYER, rule);
        assertTrue(scanner.evaluateRule(profileWithCps(8.0), rule), "3 条流水达到阈值 3，应命中");
    }

    @Test
    @DisplayName("序列分析：minOccurrences 缺省时回退到规则的 threshold")
    void sequenceFallsBackToRuleThreshold() {
        OfflineRuleScanner scanner = newScanner();
        DetectionRule rule = new DetectionRule.RuleBuilder()
                .setRuleId("seq-threshold")
                .setRuleName("序列分析")
                .setDescription("用 threshold 作为次数阈值")
                .setType(DetectionRule.RuleType.SEQUENCE_ANALYSIS)
                .setThreshold(2.0)
                .build();

        scanner.addViolation(PLAYER, rule);
        assertFalse(scanner.evaluateRule(profileWithCps(8.0), rule), "1 条流水未达 threshold=2");

        scanner.addViolation(PLAYER, rule);
        assertTrue(scanner.evaluateRule(profileWithCps(8.0), rule), "2 条流水达到 threshold=2");
    }

    @Test
    @DisplayName("序列分析：不同玩家的违规流水互不串扰")
    void sequenceIsScopedPerPlayer() {
        OfflineRuleScanner scanner = newScanner();
        DetectionRule rule = sequenceRule(2);

        scanner.addViolation(UUID.randomUUID(), rule);
        scanner.addViolation(UUID.randomUUID(), rule);

        assertFalse(scanner.evaluateRule(profileWithCps(8.0), rule),
                "其他玩家的违规流水不应让本玩家命中");
    }

    // ==================== 其他规则类型回归 ====================

    @Test
    @DisplayName("阈值检测：按 metric 取值与 threshold 比较")
    void thresholdRuleStillWorks() {
        OfflineRuleScanner scanner = newScanner();
        DetectionRule rule = new DetectionRule.RuleBuilder()
                .setRuleId("thr-test")
                .setRuleName("阈值检测")
                .setDescription("cps 阈值")
                .setType(DetectionRule.RuleType.THRESHOLD_BASED)
                .setThreshold(15.0)
                .addParameter("metric", "cps")
                .build();

        assertTrue(scanner.evaluateRule(profileWithCps(20.0), rule));
        assertFalse(scanner.evaluateRule(profileWithCps(5.0), rule));
    }

    @Test
    @DisplayName("未知指标名的阈值检测安全返回 0，不会抛异常")
    void thresholdWithUnknownMetricIsSafe() {
        OfflineRuleScanner scanner = newScanner();
        DetectionRule rule = new DetectionRule.RuleBuilder()
                .setRuleId("thr-unknown")
                .setRuleName("阈值检测")
                .setDescription("未知指标")
                .setType(DetectionRule.RuleType.THRESHOLD_BASED)
                .setThreshold(1.0)
                .addParameter("metric", "no-such-metric")
                .build();

        assertFalse(scanner.evaluateRule(profileWithCps(20.0), rule));
    }
}
