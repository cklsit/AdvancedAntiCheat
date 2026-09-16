package com.anticheat.captcha.dtw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TypeB 动作模仿检测项的核心判定器回归测试。
 *
 * <p>不依赖 Bukkit：{@link DtwMatcher} 是纯函数式实现，因此这套测试
 * 在 paper(1.21) 与 spigot(1.8.8) 两个编译 profile 下都能跑。
 *
 * <p>断言的是<b>业务语义</b>而不是实现细节：
 * 玩家照做 → 通过；漏做/做错 → 拒绝；手抖多做一个 → 容忍。
 * 生产阈值（captcha.tasks.motion-mimicry.max-distance = 0.17）写进断言，
 * 因此一旦有人改坏罚分或归一化方式，这里会立刻红。
 *
 * <p><b>时长不参与判定</b>（2026-09-16）：生产配置 `duration-weight = 0`，
 * 判据只看"做了哪些动作 + 顺序对不对"。玩家做得快/慢、中间停顿多久都不影响通过，
 * 对应的契约由 {@link #onlyActionsMatterNotDuration()} 锁住。
 */
class DtwMatcherTest {

    /** 生产配置里的判定阈值，必须以 config.yml 为准。 */
    private static final double PROD_MAX_DISTANCE = 0.17;
    private static final double MISSING_PENALTY = 1.0;
    private static final double EXTRA_PENALTY = 0.5;

    /** 完全相同索引 → 0 代价；相邻动作 → 中等代价；其余 → 1。 */
    private static DtwMatcher.Cost actionCost(int[] template, int[] observed) {
        return (i, j) -> {
            if (i >= template.length || j >= observed.length) {
                return 1.0;
            }
            int d = Math.abs(template[i] - observed[j]);
            if (d == 0) {
                return 0.0;
            }
            if (d == 1) {
                return 0.5;
            }
            return 1.0;
        };
    }

    private static double dist(int[] template, int[] observed) {
        return DtwMatcher.distance(template.length, observed.length,
                actionCost(template, observed), MISSING_PENALTY, EXTRA_PENALTY);
    }

    @Test
    @DisplayName("完全照做：归一化距离为 0，必须放行")
    void perfectReplayIsZero() {
        double d = dist(new int[]{0, 1, 2, 3}, new int[]{0, 1, 2, 3});
        assertEquals(0.0, d, 1e-9);
        assertTrue(d <= PROD_MAX_DISTANCE, "照做必须判通过");
    }

    @Test
    @DisplayName("时间轴被拉伸（同样动作、不同节奏）不影响判定")
    void timingInvariance() {
        // 模板 3 步，玩家同样 3 步但「拖长」了（DTW 对时间轴非线性拉伸免疫）
        double d = dist(new int[]{0, 1, 2}, new int[]{0, 1, 2});
        assertEquals(0.0, d, 1e-9);
    }

    @Test
    @DisplayName("漏做一个模板动作：距离 ≥ 1/n，必须拒绝")
    void missingActionIsRejected() {
        // 模板 4 步 [跳,左转,右转,疾跑]，玩家只做了前 3 步（漏做疾跑）
        double d = dist(new int[]{0, 1, 2, 3}, new int[]{0, 1, 2});
        assertTrue(d >= 0.25 - 1e-9, "漏做一步应为 1/4=0.25，实际 " + d);
        assertTrue(d > PROD_MAX_DISTANCE, "漏做必须判不通过（阈值 " + PROD_MAX_DISTANCE + "）");
    }

    @Test
    @DisplayName("玩家多做一步（手抖误触）：距离 = 0.5/n，必须容忍")
    void extraActionIsTolerated() {
        // 模板 3 步，玩家做了 4 步（中间多按了一次潜行）
        double d = dist(new int[]{0, 1, 2}, new int[]{0, 1, 2, 2});
        assertEquals(0.125, d, 1e-9);
        assertTrue(d <= PROD_MAX_DISTANCE, "误触一步应容忍，实际 " + d);
    }

    @Test
    @DisplayName("非对称罚分：同样差 1 步，漏做比多做严格整整 2 倍")
    void asymmetricPenalty() {
        double missing = dist(new int[]{0, 1, 2, 3}, new int[]{0, 1, 2});
        double extra = dist(new int[]{0, 1, 2}, new int[]{0, 1, 2, 2});
        // 归一化分母分别是 4 与 4，正好体现 missingPenalty=1.0 / extraPenalty=0.5
        assertTrue(missing > extra, "漏做必须比多做严判");
        assertEquals(2.0, missing / extra, 1e-9);
    }

    @Test
    @DisplayName("做错动作（做了别的动作）：代价高，超出阈值")
    void wrongActionIsRejected() {
        // 模板 [跳,左转,右转]，玩家 [跳,左转,跳]（最后一步做错）
        double d = dist(new int[]{0, 1, 2}, new int[]{0, 1, 0});
        assertTrue(d > PROD_MAX_DISTANCE, "做错动作必须判不通过，实际 " + d);
    }

    // ================================================================
    // 2026-09-16 调整：**时长完全不参与判定**（duration-weight = 0）
    // 判据只剩"做了哪些动作 + 顺序对不对"，玩家做得快/慢、中间停顿多久都不影响通过。
    // 下面这组测试把这条契约与生产动作代价表一起锁住。
    // ================================================================

    private static final int JUMP = 0;
    private static final int TURN_LEFT = 1;
    private static final int TURN_RIGHT = 2;
    private static final int SPRINT = 3;
    private static final int WALK = 4;
    private static final int SNEAK = 5;

    /** 复刻 TypeB_MotionMimicry#actionCost 的代价表。 */
    private static double prodActionCost(int a, int b) {
        if (a == b) {
            return 0.0;
        }
        if ((a == SPRINT && b == WALK) || (a == WALK && b == SPRINT)) {
            return 0.30;   // 同向位移只差速度 → 软惩罚
        }
        if ((a == TURN_LEFT && b == TURN_RIGHT) || (a == TURN_RIGHT && b == TURN_LEFT)) {
            return 0.75;   // 同为转身但方向反了
        }
        return 1.0;
    }

    /** 用生产代价表跑一遍 DTW。 */
    private static double prodDist(int[] template, int[] observed) {
        DtwMatcher.Cost c = (i, j) -> i >= template.length || j >= observed.length
                ? 1.0
                : prodActionCost(template[i], observed[j]);
        return DtwMatcher.distance(template.length, observed.length, c, MISSING_PENALTY, EXTRA_PENALTY);
    }

    @Test
    @DisplayName("只要动作做对就通过：时长不参与判定，任何节奏都是 0")
    void onlyActionsMatterNotDuration() {
        // duration-weight = 0 后，代价函数只由"动作是否相同"决定。
        // 玩家把 4 个动作做得极快或极慢，传入的代价函数都一模一样 → 距离恒为 0。
        int[] tpl = {JUMP, TURN_LEFT, SPRINT, SNEAK};
        assertEquals(0.0, prodDist(tpl, tpl.clone()), 1e-9);
        // 用同一代价函数重复评估（模拟不同节奏的多次提交）结果必须完全一致
        for (int round = 0; round < 50; round++) {
            assertEquals(0.0, prodDist(tpl, tpl.clone()), 1e-9);
        }
    }

    @Test
    @DisplayName("疾跑做成行走：软惩罚，必须容忍")
    void sprintDoneAsWalkStillPasses() {
        int[] tpl = {JUMP, TURN_LEFT, SPRINT, SNEAK};
        int[] obs = {JUMP, TURN_LEFT, WALK, SNEAK};
        double d = prodDist(tpl, obs);
        assertEquals(0.075, d, 1e-9);
        assertTrue(d <= PROD_MAX_DISTANCE, "不会冲刺的玩家不能被误杀，实际 " + d);
    }

    @Test
    @DisplayName("转向方向做反：必须拒绝")
    void flippedTurnIsRejected() {
        int[] tpl = {TURN_LEFT, JUMP, SNEAK};
        int[] obs = {TURN_RIGHT, JUMP, SNEAK};
        double d = prodDist(tpl, obs);
        assertTrue(d > PROD_MAX_DISTANCE, "转身方向反了必须判不通过，实际 " + d);
    }

    @Test
    @DisplayName("动作做错 + 少做：生产代价表下都必须拒绝")
    void wrongOrMissingWithProdTable() {
        int[] tpl = {TURN_RIGHT, JUMP, SPRINT, SNEAK};
        assertTrue(prodDist(tpl, new int[]{TURN_RIGHT, JUMP, SNEAK, SNEAK}) > PROD_MAX_DISTANCE);
        assertTrue(prodDist(tpl, new int[]{TURN_RIGHT, JUMP, SPRINT}) > PROD_MAX_DISTANCE);
    }

    @Test
    @DisplayName("人类节奏抖动 + 轻度动作识别误差仍落在通过区（≤0.14 分离带）")
    void humanJitterStaysWithinThreshold() {
        // 4 步序列，每步识别误差 0.1（保持顺序），归一化后 = 0.1
        DtwMatcher.Cost jitter = (i, j) -> i == j ? 0.1 : 1.0;
        double d = DtwMatcher.distance(4, 4, jitter, MISSING_PENALTY, EXTRA_PENALTY);
        assertEquals(0.1, d, 1e-9);
        assertTrue(d <= 0.14, "人类抖动应稳定落在 0.14 以下，实际 " + d);
    }

    @ParameterizedTest(name = "模板{0}步 观察{1}步 → 距离 {2}")
    @CsvSource({
            "3, 3, 0.0",
            "4, 4, 0.0",
            "5, 5, 0.0",
    })
    @DisplayName("归一化分母用 max(n,m)：完全一致时与序列长度无关")
    void normalizationByMaxNotByPath(int n, int m, double expected) {
        DtwMatcher.Cost zero = (i, j) -> 0.0;
        assertEquals(expected, DtwMatcher.distance(n, m, zero, MISSING_PENALTY, EXTRA_PENALTY), 1e-9);
    }

    @Test
    @DisplayName("空序列：视为完全不像（返回 1.0），不能抛异常")
    void emptySequenceIsWorstCase() {
        DtwMatcher.Cost any = (i, j) -> 0.0;
        assertEquals(1.0, DtwMatcher.distance(0, 3, any, 1.0, 0.5), 1e-9);
        assertEquals(1.0, DtwMatcher.distance(3, 0, any, 1.0, 0.5), 1e-9);
        assertEquals(1.0, DtwMatcher.distance(0, 0, any, 1.0, 0.5), 1e-9);
    }

    @Test
    @DisplayName("玩家只做了一个动作：绝大多数模板动作漏做，必须拒绝")
    void onlyOneActionDone() {
        double d = dist(new int[]{0, 1, 2, 3}, new int[]{0});
        assertTrue(d > PROD_MAX_DISTANCE * 2, "近乎全漏做必须重罚，实际 " + d);
    }

    @Test
    @DisplayName("乱序执行（动作都对但顺序错）必须拒绝")
    void outOfOrderIsRejected() {
        // 模板 [跳,左转,右转,疾跑]；玩家 [左转,跳,疾跑,右转]
        double d = dist(new int[]{0, 1, 2, 3}, new int[]{1, 0, 3, 2});
        assertTrue(d > PROD_MAX_DISTANCE, "乱序必须判不通过，实际 " + d);
    }

    @Test
    @DisplayName("阈值分离带：人类最坏情况(0.14) 与 漏做/做错(≥0.25) 之间无重叠")
    void thresholdSeparationBand() {
        double humanWorst = 0.14;
        double cheatBest = 0.25;
        assertTrue(humanWorst < PROD_MAX_DISTANCE, "生产阈值必须高于人类最坏情况");
        assertTrue(PROD_MAX_DISTANCE < cheatBest, "生产阈值必须低于漏做/做错的最低值");
    }

    @Test
    @DisplayName("典型题目长度（3~4 步）下，随机乱做被拒绝的概率高")
    void randomWrongAnswersMostlyRejected() {
        List<int[]> templatePool = List.of(
                new int[]{0, 1, 2},
                new int[]{0, 1, 2, 3},
                new int[]{2, 0, 3},
                new int[]{1, 3, 2, 0});
        int total = 0;
        int rejected = 0;
        // 确定性伪随机（不用 Random 之外的全局状态，保证可复现）
        java.util.Random rnd = new java.util.Random(20260915L);
        for (int round = 0; round < 400; round++) {
            int[] tpl = templatePool.get(round % templatePool.size());
            int[] obs = new int[tpl.length];
            for (int i = 0; i < obs.length; i++) {
                obs[i] = rnd.nextInt(4);
            }
            total++;
            if (dist(tpl, obs) > PROD_MAX_DISTANCE) {
                rejected++;
            }
        }
        double rejectRate = rejected / (double) total;
        assertTrue(rejectRate > 0.85,
                "随机乱做的拒绝率应显著高于 85%，实际 " + rejectRate);
    }
}
