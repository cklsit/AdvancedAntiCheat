package com.anticheat.core.bounty;

import com.anticheat.bounty.BountyTaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赏金判定链路的纯逻辑测试（不依赖服务端，可离线跑）。
 *
 * <p>覆盖三块：行为指标（[BountyMetrics]）、人类基线（[BaselineModel] /
 * [BaselineLearner]）与判定（[BountyJudge]）。这三块合起来就是文档说的
 * "整个计划的大脑"，所以它们必须能在没有 Minecraft 的情况下被验证——
 * 否则每次调阈值都只能靠"上线看看"。</p>
 */
class BountyLogicTest {

    // ------------------------------------------------------------------ 指标

    @Test
    @DisplayName("移动抖动：匀速直线 → 0；步长忽大忽小 → 明显为正")
    void moveJitterSeparatesUniformFromJittery() {
        List<BountySample> smooth = walk(t -> 0.1 * t, 40);
        assertEquals(0.0, BountyMetrics.moveJitter(smooth), 1e-6,
                "每步位移完全一致时二阶差分应为 0（这是直线加速/飞行类作弊的特征）");

        List<BountySample> jittery = walk(t -> t % 2 == 0 ? 0.1 * t : 0.1 * t + 0.05, 40);
        assertTrue(BountyMetrics.moveJitter(jittery) > 0.01,
                "步长交替变化时二阶差分应明显为正（人类的手部微调）");
    }

    @Test
    @DisplayName("移动抖动：全程静止 → NaN（不能把挂机算成'极其像机器'）")
    void moveJitterIsNanWhenStandingStill() {
        List<BountySample> still = walk(t -> 0.0, 40);
        assertTrue(Double.isNaN(BountyMetrics.moveJitter(still)),
                "没有任何移动步时该指标没有信息量，必须是 NaN 而不是 0");
    }

    @Test
    @DisplayName("转向熵：恒定转向 → 0；转向幅度分散 → 接近 1")
    void turnEntropyDetectsConcentratedTurns() {
        // 每步固定转 10 度：所有增量落进同一个桶 → 熵为 0（自瞄"锁住"目标的特征）
        List<BountySample> fixed = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            fixed.add(sample(i, 0, 0, 0, i * 10f, 0f, false, false));
        }
        assertEquals(0.0, BountyMetrics.turnEntropy(fixed), 1e-6,
                "转向增量全部集中在同一个取值时熵应为 0");

        // 转向幅度在 -80..80 之间来回摆动 → 覆盖面广 → 熵接近满格
        List<BountySample> spread = new ArrayList<>();
        int[] deltas = {-80, -60, -40, -20, 20, 40, 60, 80};
        float yaw = 0f;
        for (int i = 0; i < 80; i++) {
            yaw += deltas[i % deltas.length];
            spread.add(sample(i, 0, 0, 0, yaw, 0f, false, false));
        }
        assertTrue(BountyMetrics.turnEntropy(spread) > 0.7,
                "转向增量分散时熵应明显偏高，实际=" + BountyMetrics.turnEntropy(spread));
    }

    @Test
    @DisplayName("转向熵：全程不转 → NaN")
    void turnEntropyIsNanWithoutTurns() {
        List<BountySample> still = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            still.add(sample(i, 0, 0, 0, 90f, 0f, false, false));
        }
        assertTrue(Double.isNaN(BountyMetrics.turnEntropy(still)),
                "一步都没转过时不该给出熵值");
    }

    @Test
    @DisplayName("攻击间隔变异系数：等间隔 → 0；间隔不均 → 明显为正；少于 3 次攻击 → NaN")
    void attackIntervalCv() {
        assertEquals(0.0, BountyMetrics.attackIntervalCv(attacksAt(10, 20, 30, 40)), 1e-9,
                "间隔恒定 10 tick 时变异系数应为 0（宏/自动点击器的特征）");

        double varied = BountyMetrics.attackIntervalCv(attacksAt(10, 20, 40, 70));
        assertTrue(varied > 0.3, "间隔 10/20/30 的变异系数应明显为正，实际=" + varied);

        assertTrue(Double.isNaN(BountyMetrics.attackIntervalCv(attacksAt(10, 20))),
                "两次攻击只能算出一个间隔，不足以谈'变异'");
    }

    @Test
    @DisplayName("速度变异系数：匀速 → 0；忽快忽慢 → 为正")
    void speedCv() {
        List<BountySample> uniform = walk(t -> 0.2 * t, 40);
        assertEquals(0.0, BountyMetrics.speedCv(uniform), 1e-9, "匀速时速度变异系数应为 0");

        List<BountySample> uneven = walk(t -> (t % 2 == 0) ? 0.2 * t : 0.2 * t + 0.3, 40);
        assertTrue(BountyMetrics.speedCv(uneven) > 0.3, "速度起伏时变异系数应明显为正");
    }

    @Test
    @DisplayName("compute：样本不足的指标返回 NaN，而不是 0")
    void computeUsesNanForInsufficientMetrics() {
        Map<String, Double> one = BountyMetrics.compute(Collections.singletonList(
                sample(0, 0, 0, 0, 0f, 0f, true, false)));
        for (String key : BountyMetrics.getAllKeys()) {
            assertTrue(one.containsKey(key), "应包含全部指标键: " + key);
            assertTrue(Double.isNaN(one.get(key)), key + " 在样本不足时必须是 NaN");
        }
    }

    // ------------------------------------------------------------------ 分布与基线

    @Test
    @DisplayName("正态分布：Φ(0)=0.5，Φ(1.96)≈0.975，erf(0)=0")
    void normalDistributionSanity() {
        assertEquals(0.0, BaselineModel.erf(0.0), 1e-9);
        assertEquals(0.5, BaselineModel.normalCdf(0.0), 1e-9);
        assertEquals(0.975, BaselineModel.normalCdf(1.96), 1e-3);
        assertEquals(0.025, BaselineModel.normalCdf(-1.96), 1e-3);
    }

    @Test
    @DisplayName("基线未就绪：异常分必须是 0 且 ready=false（绝不能把'不知道'当成'正常'）")
    void baselineNotReadyYieldsZeroAndNotReady() {
        BaselineModel empty = new BaselineModel(Collections.emptyMap(), 200L, 2, 12.0);
        AnomalyResult result = empty.anomaly(Collections.singletonMap(BountyMetrics.KEY_MOVE_JITTER, 0.0001));
        assertFalse(result.getReady(), "没有基线时不得声称就绪");
        assertEquals(0.0, result.getScore(), 1e-9);
        assertFalse(empty.ready());
    }

    @Test
    @DisplayName("基线就绪：明显低于人类均值 → 高异常分；接近均值 → 低异常分")
    void anomalyScoringRespondsToDeviation() {
        Map<String, MetricBaseline> baselines = new LinkedHashMap<>();
        baselines.put(BountyMetrics.KEY_MOVE_JITTER, baseline(BountyMetrics.KEY_MOVE_JITTER, 0.05, 0.01, 500));
        baselines.put(BountyMetrics.KEY_TURN_ENTROPY, baseline(BountyMetrics.KEY_TURN_ENTROPY, 0.80, 0.05, 500));
        BaselineModel model = new BaselineModel(baselines, 200L, 2, 12.0);
        assertTrue(model.ready(), "两个指标样本都够，基线应就绪");

        Map<String, Double> humanLike = new LinkedHashMap<>();
        humanLike.put(BountyMetrics.KEY_MOVE_JITTER, 0.05);
        humanLike.put(BountyMetrics.KEY_TURN_ENTROPY, 0.80);
        AnomalyResult normal = model.anomaly(humanLike);
        assertTrue(normal.getReady());
        assertEquals(0.0, normal.getScore(), 1e-6, "完全落在均值上时异常分应为 0");

        Map<String, Double> machineLike = new LinkedHashMap<>();
        machineLike.put(BountyMetrics.KEY_MOVE_JITTER, 0.0005);   // 比人类低 5 个标准差
        machineLike.put(BountyMetrics.KEY_TURN_ENTROPY, 0.02);    // 转向几乎是单一值
        AnomalyResult suspicious = model.anomaly(machineLike);
        assertTrue(suspicious.getReady());
        assertTrue(suspicious.getScore() > 80.0,
                "两维同时极端偏离时异常分应很高，实际=" + suspicious.getScore());
        assertTrue(suspicious.topContributors(2).size() == 2, "应给出主要贡献指标");

        // NaN 的指标必须被跳过，不能当成 0 参与计算（那会把异常分拉满）
        Map<String, Double> withNaN = new LinkedHashMap<>();
        withNaN.put(BountyMetrics.KEY_MOVE_JITTER, Double.NaN);
        withNaN.put(BountyMetrics.KEY_TURN_ENTROPY, 0.80);
        assertFalse(model.anomaly(withNaN).getReady(), "只剩一个可用指标时不足以判定就绪");
    }

    @Test
    @DisplayName("学习器：Welford 均值/标准差不因样本量而失真，且忽略 NaN")
    void learnerComputesMeanAndSd() {
        BaselineLearner learner = new BaselineLearner();
        for (int i = 1; i <= 100; i++) {
            learner.observe("k", i);
        }
        learner.observe("k", Double.NaN);   // 必须被忽略

        List<MetricBaseline> snapshot = learner.snapshot();
        assertEquals(1, snapshot.size());
        MetricBaseline baseline = snapshot.get(0);
        assertEquals(100, baseline.getSamples(), "NaN 不应计入样本数");
        assertEquals(50.5, baseline.getMean(), 1e-9);
        // 1..100 的样本标准差 = sqrt((n(n+1)/12)) 的离散形式；用已知值核对
        assertEquals(29.011491975882016, baseline.getSd(), 1e-9);
        assertEquals(100L, learner.totalObservations());
    }

    @Test
    @DisplayName("学习器：seed 之后可以继续增量学习（基线跨重启延续）")
    void learnerSeedsFromPersistedBaseline() {
        BaselineLearner learner = new BaselineLearner();
        learner.seed(Collections.singletonList(
                new MetricBaseline("k", 10.0, 2.0, 50L, MetricDirection.LOWER_SUSPICIOUS)));
        assertEquals(50L, learner.sampleCount("k"));
        learner.observe("k", 12.0);
        MetricBaseline after = learner.snapshot().get(0);
        assertEquals(51L, after.getSamples());
        // 新均值 = (50*10 + 12) / 51
        assertEquals((50 * 10.0 + 12.0) / 51.0, after.getMean(), 1e-9);
    }

    // ------------------------------------------------------------------ 判定

    @Test
    @DisplayName("判定：VL 达门槛 → 检测成功（保底赏金），无论目标是否达成")
    void judgeDetectedTakesPrecedence() {
        BountyTuning tuning = new BountyTuning();
        JudgeResult result = BountyJudge.judge(
                new JudgeInput(3, 12.0, Boolean.TRUE, notReady()), tuning, 100);
        assertEquals(BountyVerdict.DETECTED, result.getVerdict());
        assertEquals(tuning.getBaseReward(), result.getReward());
        assertFalse(result.getVerdict().isFinding(), "被检测到不算'发现'，不该进复核队列");
    }

    @Test
    @DisplayName("判定：未达成目标且无证据 → 无结论、不发赏金（这是原实现最严重的假阳性）")
    void judgeInconclusiveWhenObjectiveMissed() {
        JudgeResult result = BountyJudge.judge(
                new JudgeInput(0, 0.0, Boolean.FALSE, notReady()), new BountyTuning(), 100);
        assertEquals(BountyVerdict.INCONCLUSIVE, result.getVerdict());
        assertEquals(0, result.getReward());
        assertFalse(result.getVerdict().isFinding());
    }

    @Test
    @DisplayName("判定：完成目标但基线未就绪 → 绕过（低置信、进人工复核），不是无结论")
    void judgeBypassWithLowConfidenceWhenBaselineNotReady() {
        JudgeResult result = BountyJudge.judge(
                new JudgeInput(0, 2.0, Boolean.TRUE, notReady()), new BountyTuning(), 50);
        assertEquals(BountyVerdict.BYPASSED, result.getVerdict());
        assertEquals(BountyConfidence.LOW, result.getConfidence());
        assertEquals(50, result.getReward(), "名义赏金 50 × 倍率 1.0");
        assertTrue(result.getVerdict().isFinding());
    }

    @Test
    @DisplayName("判定：异常分决定绕过档位（中置信 vs 高危）")
    void judgeAnomalyDrivesTier() {
        BountyTuning tuning = new BountyTuning();

        JudgeResult medium = BountyJudge.judge(
                new JudgeInput(0, 0.0, Boolean.TRUE, ready(60.0)), tuning, 10);
        assertEquals(BountyVerdict.BYPASSED, medium.getVerdict());
        assertEquals(BountyConfidence.MEDIUM, medium.getConfidence());

        JudgeResult zeroDay = BountyJudge.judge(
                new JudgeInput(0, 0.0, Boolean.TRUE, ready(90.0)), tuning, 10);
        assertEquals(BountyVerdict.ZERO_DAY, zeroDay.getVerdict());
        assertEquals(tuning.getZeroDayReward(), zeroDay.getReward());
    }

    @Test
    @DisplayName("判定：自由测试（无目标）靠异常分单独定论")
    void judgeFreeTestUsesAnomalyOnly() {
        BountyTuning tuning = new BountyTuning();
        // 无目标 + 行为正常 → 没有信息量
        assertEquals(BountyVerdict.INCONCLUSIVE, BountyJudge.judge(
                new JudgeInput(0, 0.0, null, ready(20.0)), tuning, 150).getVerdict());
        // 无目标 + 行为异常 → 高危（文档对自由测试的要求）
        assertEquals(BountyVerdict.ZERO_DAY, BountyJudge.judge(
                new JudgeInput(0, 0.0, null, ready(95.0)), tuning, 150).getVerdict());
        // 无目标 + 基线未就绪 → 无结论（不能凭空给赏金）
        assertEquals(BountyVerdict.INCONCLUSIVE, BountyJudge.judge(
                new JudgeInput(0, 0.0, null, notReady()), tuning, 150).getVerdict());
    }

    @Test
    @DisplayName("判定：奖励倍率与下限")
    void judgeRewardScaling() {
        BountyTuning doubled = new BountyTuning(8.0, 55.0, 80.0, 1, 2.0, 500);
        assertEquals(20, BountyJudge.scaledBypassReward(10, doubled), "10 × 2.0");
        // 名义赏金为 0（配置错）时先夹到 1、再乘倍率，所以是 2 而不是 0；
        // 契约是"**结果**至少 1"，两个下限都成立才不会有 0 元赏金。
        assertEquals(2, BountyJudge.scaledBypassReward(0, doubled), "0 先夹到 1 再乘 2.0");
        assertTrue(BountyJudge.scaledBypassReward(0, new BountyTuning()) >= 1,
                "无论倍率如何，绕过档的赏金都不能是 0");
    }

    // ------------------------------------------------------------------ 任务目录

    @Test
    @DisplayName("任务目录：三种写法都能解析，未知返回 null")
    void taskTypeParsing() {
        assertEquals(BountyTaskType.MOVE_BASIC, BountyTaskType.byId("MOVE_BASIC"));
        assertEquals(BountyTaskType.MOVE_BASIC, BountyTaskType.byId("move-basic"));
        assertEquals(BountyTaskType.MOVE_BASIC, BountyTaskType.byId(" move_basic "));
        assertNull(BountyTaskType.byId("nope"));
        assertNull(BountyTaskType.byId(null));

        for (BountyTaskType type : BountyTaskType.values()) {
            assertNotNull(type.getId());
            assertTrue(type.getBounty() > 0, type + " 的赏金必须为正");
            assertTrue(type.getDurationSeconds() > 0, type + " 的时长必须为正");
            assertNotNull(type.getObjectiveType());
        }
        assertTrue(BountyTaskType.optionsText().contains("move-basic"));
    }

    // ------------------------------------------------------------------ 夹具

    private static BountySample sample(long seq, double x, double y, double z,
                                       float yaw, float pitch, boolean onGround, boolean attacked) {
        return new BountySample(seq, x, y, z, yaw, pitch, onGround, attacked);
    }

    /** 沿 x 轴按 [xOf] 给出的位置生成一串采样（z / 朝向恒定）。 */
    private static List<BountySample> walk(java.util.function.IntToDoubleFunction xOf, int count) {
        List<BountySample> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(sample(i, xOf.applyAsDouble(i), 64.0, 0.0, 0f, 0f, true, false));
        }
        return out;
    }

    /** 只在给定序号处攻击的采样序列（长度覆盖到最后一个攻击点）。 */
    private static List<BountySample> attacksAt(long... ticks) {
        long last = ticks[ticks.length - 1];
        List<BountySample> out = new ArrayList<>((int) last + 1);
        List<Long> marks = new ArrayList<>();
        for (long tick : ticks) marks.add(tick);
        for (long i = 0; i <= last; i++) {
            out.add(sample(i, 0, 64, 0, 0f, 0f, true, marks.contains(i)));
        }
        return out;
    }

    private static MetricBaseline baseline(String key, double mean, double sd, long samples) {
        return new MetricBaseline(key, mean, sd, samples, MetricDirection.LOWER_SUSPICIOUS);
    }

    private static AnomalyResult notReady() {
        return new AnomalyResult(0.0, false, Collections.emptyList());
    }

    private static AnomalyResult ready(double score) {
        return new AnomalyResult(score, true, Arrays.asList(
                new MetricContribution("move-jitter", 0.001, 0.05, 0.01, -49.0, 3.2),
                new MetricContribution("turn-entropy", 0.02, 0.80, 0.05, -15.6, 4.1)));
    }
}
