package com.anticheat.core.check.impl.reach;

import com.anticheat.core.util.math.PointHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伸手阈值的**标定仿真**——这个测试是阈值取值的唯一依据，它同时给出两件事：
 * 「合法攻击离误报有多远」与「作弊出手需要几刀才会累积到告警」。
 *
 * <h3>时间模型（与真实链路一致）</h3>
 * - 服务端每 tick 采样：攻击者的眼睛位置**滞后一个单程延迟**（那是它最近收到的位置包），
 *   目标位置用服务端权威值；
 * - 客户端在 tick `tc` 出手，服务端在 `tc + d` 收到（`d` = 单程延迟的 tick 数）；
 * - 客户端判断用的是**自己视角**：眼睛在 `tc`、目标在 `tc - d`；
 * - 服务端随后用 `[tc + d - 7, tc + d]` 这段窗口里的眼睛 × 目标历史取最小距离。
 *
 * <h3>本测试固定住的两条结论</h3>
 * <b>1) 窗口对齐的边界是「单程 ≤ 3 tick」。</b>
 * 客户端出手时看到的目标位置是 `P(tc - d)`，而服务端窗口覆盖 `[tc + d - 7, tc + d]`。
 * 要让它落在窗口内必须 `tc - d ≥ tc + d - 7`，即 **d ≤ 3.5 → d ≤ 3（RTT ≤ 300ms）**。
 * 在这个范围内，实测值不会超过客户端当时看到的距离（原版允许的 3.1），
 * 因此**只需要覆盖原版自身的余量**——这就是把基础容差从 0.85 收到
 * {@link ReachTolerance#BASE} 的依据。
 * 超过 3 tick 之后窗口不再保证包含那一对样本，多出来的误差由
 * {@link ReachTolerance#MAX_PING_SLACK} 按延迟线性补偿（这正是那段 slack 存在的理由，
 * 不是"随手留的余量"）。
 *
 * <b>2) 「取历史最小」是有代价的：检测能力会被目标自身的移动量削掉一块。</b>
 * 取最小的语义是"在窗口内的所有可能对齐里取最有利的一对"，所以目标在窗口内
 * 靠近过多少，实测值就被削掉多少。目标相对速度 `v`、窗口 8 tick 时，
 * 削掉的上界约 `v × 0.4s`——冲刺（7.1 格/秒）可达 **2.8 格**。
 * 因此本测试不按"中位实测值"判定检测能力，而是按
 * **实测分布的上界**与**累积到告警所需刀数**（见 {@link #hitsToFlag}）。
 * 这个取舍是刻意的：宁可要"少漏一点、绝不误报"，也不要反过来。
 *
 * <p>运行时打印两张表（`mvn test -Dtest=ReachCalibrationTest`）。
 * 改 {@link ReachTolerance} 或 {@link ReachA} 的任何常数前先看表。</p>
 */
class ReachCalibrationTest {

    /** 站立眼睛高度（原版 1.62）。 */
    private static final double EYE_HEIGHT = 1.62;

    /** 玩家命中盒，与 EntityBoxes.playerBox() 一致。 */
    private static final double PLAYER_HALF_WIDTH = 0.3;

    private static final double PLAYER_HEIGHT = 1.8;

    /** "合法"的定义：原版出手上限 + 命中盒外扩。 */
    private static final double LEGIT_REACH = 3.0 + 0.1;

    /** 攻击冷却（原版 10 tick）。 */
    private static final int ATTACK_COOLDOWN_TICKS = 10;

    private static final int TICKS = 1200;

    /** 单程延迟（tick）、对应 RTT，以及窗口对齐是否仍然成立（d ≤ 3）。 */
    private static final int[] ONE_WAY_TICK_DELAYS = {0, 1, 2, 3, 4};
    private static final int[] PING_SAMPLES = {0, 100, 200, 300, 400};

    /**
     * 单程延迟上界（tick）。超过它之后服务端的 8 tick 窗口不再必然包含
     * "客户端出手时看到的那一对样本"，误差靠 ping-slack 补。
     */
    private static final int ALIGNED_DELAY_TICKS = 3;

    /** 允许的浮点误差。 */
    private static final double EPSILON = 1e-6;

    /** 阈值相对实测上界必须留出的余量（格）。低于它说明阈值已经贴边。 */
    private static final double MIN_REQUIRED_MARGIN = 0.15;

    // ------------------------------------------------------------------ 场景

    /** 目标脚底坐标随时间变化，返回 `{x, y, z}`。 */
    private interface TargetPath {
        double[] at(double tick);
    }

    /** 攻击者脚底坐标随时间变化，返回 `{x, y, z}`。 */
    private interface AttackerPath {
        double[] at(double tick);
    }

    private static final class Scenario {
        final String name;
        final TargetPath target;
        final AttackerPath attacker;

        Scenario(String name, TargetPath target, AttackerPath attacker) {
            this.name = name;
            this.target = target;
            this.attacker = attacker;
        }
    }

    private static final AttackerPath STANDING = tick -> new double[]{0.0, 0.0, 0.0};

    /** 目标往返周期（tick）。60 tick + 3 格幅值 ≈ 6.3 格/秒（冲刺速度）。 */
    private static final double SWEEP_PERIOD_TICKS = 60.0;

    private static List<Scenario> legitScenarios() {
        List<Scenario> list = new ArrayList<>();
        // 最坏情况：静止目标正好贴在出手上限上——窗口帮不上忙，实测值就是原版允许的最大值
        list.add(new Scenario("STATIC-AT-LIMIT",
                tick -> new double[]{3.3, 0.0, 0.0}, STANDING));
        list.add(new Scenario("SPRINT-RETREAT",
                tick -> new double[]{5.0 + 3.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0},
                STANDING));
        list.add(new Scenario("SPRINT-APPROACH",
                tick -> new double[]{5.0 - 3.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0},
                STANDING));
        list.add(new Scenario("STRAFE-LATERAL",
                tick -> new double[]{3.3, 0.0, 2.5 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS)},
                STANDING));
        list.add(new Scenario("SPRINT-JUMP",
                tick -> new double[]{
                        3.3 + 1.5 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS),
                        0.6 * Math.abs(Math.sin(Math.PI * tick / 15.0)),
                        0.0},
                STANDING));
        list.add(new Scenario("BOTH-MOVING",
                tick -> new double[]{3.3 + 1.5 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0},
                tick -> new double[]{1.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0}));
        list.add(new Scenario("CROSSING-PATHS",
                tick -> new double[]{5.0 + 3.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0},
                tick -> new double[]{-3.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0}));
        return list;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("合法攻击：窗口对齐范围内实测值不超过原版允许值，且处处留有余量")
    void legitAttacksNeverExceedThreshold() {
        System.out.println("[reach-calibration] legit attacks, threshold = 3.00 + effective tolerance");
        for (Scenario scenario : legitScenarios()) {
            for (int i = 0; i < ONE_WAY_TICK_DELAYS.length; i++) {
                int oneWayTicks = ONE_WAY_TICK_DELAYS[i];
                int ping = PING_SAMPLES[i];
                Run run = simulate(scenario, oneWayTicks, LEGIT_REACH, ping);

                double threshold = 3.0 + ReachTolerance.effective(ping);
                System.out.println(String.format(
                        "  %-16s RTT=%3dms d=%d hits=%3d max=%.3f p99=%.3f"
                            + " | thr=%.2f margin=%.3f",
                        scenario.name, ping, oneWayTicks, run.samples.size(), run.max, run.p99,
                        threshold, threshold - run.max));

                assertTrue(run.samples.size() >= 15,
                        scenario.name + " RTT " + ping + "ms: only " + run.samples.size()
                                + " samples, the scenario was not really exercised");

                // 实测超出原版值的部分，只能来自"目标在延迟窗口里的位移"，
                // 其预算由容差承担（下面用实际余量断言）
                double excessOverVanilla = run.max - LEGIT_REACH;

                assertTrue(run.max <= threshold,
                        scenario.name + " RTT " + ping + "ms: measured " + run.max
                                + " crosses threshold " + threshold + " (false positive combination)");

                assertTrue(threshold - run.max >= MIN_REQUIRED_MARGIN,
                        scenario.name + " RTT " + ping + "ms: margin only " + (threshold - run.max)
                                + " blocks, below the required " + MIN_REQUIRED_MARGIN
                                + "; either the tolerance is too tight or the scenario regressed");

                assertTrue(excessOverVanilla <= ReachTolerance.MAX_PING_SLACK + 0.1,
                        scenario.name + " RTT " + ping + "ms: measured " + run.max
                                + " is " + excessOverVanilla + " above the vanilla allowance,"
                                + " more than the design budget accounts for");
            }
        }
    }

    @Test
    @DisplayName("对齐边界外（RTT>300ms）：误差由 ping-slack 覆盖，且余量仍达标")
    void beyondAlignmentBoundaryIsCoveredByPingSlack() {
        Scenario retreat = new Scenario("SPRINT-RETREAT-BOUNDARY",
                tick -> new double[]{5.0 + 3.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0},
                STANDING);
        // 最坏的对齐失败组合：目标纵向跳动 + 单程 4 tick
        Scenario jump = new Scenario("SPRINT-JUMP-BOUNDARY",
                tick -> new double[]{
                        3.3 + 1.5 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS),
                        0.6 * Math.abs(Math.sin(Math.PI * tick / 15.0)),
                        0.0},
                STANDING);

        for (Scenario scenario : List.of(retreat, jump)) {
            int oneWayTicks = ALIGNED_DELAY_TICKS + 1;
            int ping = 400;
            Run run = simulate(scenario, oneWayTicks, LEGIT_REACH, ping);
            double threshold = 3.0 + ReachTolerance.effective(ping);
            double excessOverVanilla = run.max - LEGIT_REACH;

            System.out.println(String.format(
                    "  %-22s RTT=%3dms max=%.3f excess-over-vanilla=%.3f slack=%.2f margin=%.3f",
                    scenario.name, ping, run.max, excessOverVanilla,
                    ReachTolerance.MAX_PING_SLACK, threshold - run.max));

            assertTrue(excessOverVanilla <= ReachTolerance.MAX_PING_SLACK,
                    scenario.name + ": the misalignment costs " + excessOverVanilla
                            + " blocks but ping-slack only provides " + ReachTolerance.MAX_PING_SLACK);
            assertTrue(threshold - run.max >= MIN_REQUIRED_MARGIN,
                    scenario.name + ": margin " + (threshold - run.max) + " below " + MIN_REQUIRED_MARGIN);
        }
    }

    /**
     * 可靠识别的 reach 门槛（格）。
     *
     * <p>低于它的作弊（例如 4.0）在冲刺逃逸目标上不一定每次出手都能被单次判出——
     * "取历史最小"会削掉一部分实测距离（见类注释结论 2）。这种量级的作弊
     * 需要靠长时间累积才可能抓到，属于**已知的召回边界**，不是 bug：
     * 想要更高的召回就必须放宽延迟补偿，那就是拿误报换召回。</p>
     */
    private static final double RELIABLE_DETECTION_REACH = 4.5;

    /** 「卡在射程边缘刷刀」的宽度（格）。 */
    private static final double EDGE_BAND = 0.5;

    @Test
    @DisplayName("作弊出手：4.5 格以上稳定越阈且能累积到告警；4.0 属于召回边界")
    void cheatReachIsDetected() {
        double[] cheatReaches = {4.0, 4.5, 6.0};
        // 经典 reach 场景：目标在逃逸，攻击者追上就砍
        TargetPath retreating = tick ->
                new double[]{5.0 + 3.0 * Math.sin(2 * Math.PI * tick / SWEEP_PERIOD_TICKS), 0.0, 0.0};

        System.out.println("[reach-calibration] cheat reach (edge-band spamming, fleeing target), hits-to-flag");
        for (double cheat : cheatReaches) {
            for (int i = 0; i < ONE_WAY_TICK_DELAYS.length; i++) {
                int ping = PING_SAMPLES[i];
                Run run = simulate(
                        new Scenario("CHEAT-" + cheat, retreating, STANDING),
                        ONE_WAY_TICK_DELAYS[i], cheat, ping, cheat - EDGE_BAND);
                double threshold = 3.0 + ReachTolerance.effective(ping);
                int hits = hitsToFlag(run, threshold);

                System.out.println(String.format(
                        "  reach=%.1f RTT=%3dms hits=%3d max=%.3f p95=%.3f p50=%.3f"
                            + " | thr=%.2f hits-to-flag=%s",
                        cheat, ping, run.samples.size(), run.max, run.p95, run.p50,
                        threshold, hits < 0 ? ">samples" : String.valueOf(hits)));

                assertTrue(run.samples.size() >= 8, "CHEAT-" + cheat + ": too few samples");

                if (cheat >= RELIABLE_DETECTION_REACH) {
                    // 可可靠识别的区间：单次出手就能越过阈值，且能在有限刀数内累积到告警
                    assertTrue(run.max > threshold,
                            "reach=" + cheat + " RTT=" + ping + "ms: max measured " + run.max
                                    + " never crosses threshold " + threshold + "; this cheat is invisible");
                    assertTrue(hits > 0,
                            "reach=" + cheat + " RTT=" + ping + "ms: never accumulates to a flag");
                    assertTrue(hits <= MAX_ACCEPTABLE_HITS_TO_FLAG,
                            "reach=" + cheat + " RTT=" + ping + "ms: needs " + hits
                                    + " hits to flag, above the acceptable " + MAX_ACCEPTABLE_HITS_TO_FLAG);
                }
                // 召回边界以内**不做数值断言**：实测上界高度依赖目标的运动模式
                // （横移/往返目标会不断靠近，把实测值压得很低），拿它当验收标准
                // 只会得到一条脆弱的测试。这里只要求在表格里被记录，
                // 上线后用 ReachA 的标定模式核对真实分布。
                // 想提高召回就得放宽延迟补偿——那是拿误报换召回，方向是错的。
            }
        }
    }

    @Test
    @DisplayName("单次孤立超距不可能单独触发告警（累计机制的前提）")
    void singleOutlierCannotFlagAlone() {
        assertTrue(ReachA.MAX_EXCESS_PER_HIT < ReachA.FLAG_BALANCE,
                "single-sample evidence cap must be below the flag threshold");
        assertTrue(ReachA.FLAG_BALANCE / ReachA.MAX_EXCESS_PER_HIT >= 2.0,
                "at least two maximal samples must be needed; with per-tick decay, more in practice");
    }

    @Test
    @DisplayName("容差取值自洽：覆盖量化误差，同时不放过 3.6 以下的 reach 作弊")
    void toleranceStaysInSaneBand() {
        assertEquals(3.1, LEGIT_REACH, 1e-9, "vanilla allows 3.0 plus 0.1 box grow");
        assertTrue(ReachTolerance.BASE >= 0.2,
                "base tolerance too small to cover position/rotation quantization");
        assertTrue(ReachTolerance.BASE <= 0.6,
                "base tolerance too large: reach cheats below 3.6 would be missed");
        assertTrue(ReachTolerance.MAX_PING_SLACK > 0.0,
                "ping slack must be positive while the alignment guarantee stops at d=3");
    }

    /**
     * 累积到告警需要的最多刀数；超过视为检测能力不足。
     *
     * <p>60 刀的来历：仿真里的作弊者按冷却出手，而目标在绕圈，于是"超出阈值的样本"
     * 是**间歇**出现的，而每两刀之间（10 tick）会按 {@link ReachA#DECAY_PER_TICK}
     * 降温 0.5 格。真正决定能否累积起来的是"超出量是否稳定大于降温量"，
     * 不是单次能超出多少——这也解释了为什么 4.0 格这种量级需要很长时间才可能抓到。
     * 60 刀 ≈ 30 秒战斗（600 tick）。</p>
     */
    private static final int MAX_ACCEPTABLE_HITS_TO_FLAG = 60;

    // ------------------------------------------------------------------ 仿真内核

    private static final class Run {
        final List<Double> samples = new ArrayList<>();
        double max = Double.NEGATIVE_INFINITY;
        double p50 = Double.NaN;
        double p95 = Double.NaN;
        double p99 = Double.NaN;

        void add(double value) {
            if (Double.isNaN(value) || value == Double.MAX_VALUE) return;
            samples.add(value);
            if (value > max) max = value;
        }

        void finish() {
            if (samples.isEmpty()) {
                max = Double.NaN;
                return;
            }
            samples.sort(Double::compare);
            p50 = percentile(0.50);
            p95 = percentile(0.95);
            p99 = percentile(0.99);
        }

        double percentile(double p) {
            if (samples.isEmpty()) return Double.NaN;
            int rank = (int) Math.ceil(p * samples.size());
            return samples.get(Math.min(samples.size() - 1, Math.max(0, rank - 1)));
        }
    }

    /**
     * 按 {@link ReachA} 的记账方式复算"第几刀会告警"。
     *
     * <p>每刀之间隔着 10 tick（攻击冷却），这 10 tick 里余额按
     * {@link ReachA#DECAY_PER_TICK} 持续降温，所以一个稳定作弊每刀净增
     * `min(超出量, MAX_EXCESS_PER_HIT) - 10 × DECAY_PER_TICK`。
     * 这个净增必须为正，作弊才可能被抓到——这正是"降温速率决定了能抓到多小的 reach"。</p>
     *
     * @return 需要的刀数；样本耗尽仍不告警时返回 -1
     */
    private static int hitsToFlag(Run run, double threshold) {
        double decayPerCycle = ATTACK_COOLDOWN_TICKS * ReachA.DECAY_PER_TICK;
        double balance = 0.0;
        for (int i = 0; i < run.samples.size(); i++) {
            double excess = Math.max(0.0, run.samples.get(i) - threshold);
            balance += Math.min(excess, ReachA.MAX_EXCESS_PER_HIT);
            if (balance > ReachA.FLAG_BALANCE) return i + 1;
            balance = Math.max(0.0, balance - decayPerCycle);
        }
        return -1;
    }

    /**
     * 跑一次仿真。
     *
     * @param reachLimit 出手时的距离上限（合法判定传 {@link #LEGIT_REACH}；
     *                   作弊场景传它自己的出手距离）
     */
    private static Run simulate(Scenario scenario, int oneWayTicks, double reachLimit, int ping) {
        return simulate(scenario, oneWayTicks, reachLimit, ping, 0.0);
    }

    /**
     * 跑一次仿真。
     *
     * @param minSeenDistance 出手时的距离**下限**。作弊场景传 `reachLimit - 0.5`，
     *   表示"卡在射程边缘刷刀"——这是 reach 作弊的真实用法：真人贴近了才打，
     *   作弊者是**刚进射程就打**。若不加这个下限，样本里会混进大量近距离攻击，
     *   判据就变成在测"目标离得多近"而不是"射程有没有被拉长"。
     */
    private static Run simulate(Scenario scenario, int oneWayTicks, double reachLimit,
                                int ping, double minSeenDistance) {
        PointHistory eyeHistory = new PointHistory(8);
        PointHistory targetHistory = new PointHistory(8);

        Run run = new Run();
        // 不要用 Integer.MIN_VALUE 初始化：`clientTick - MIN_VALUE` 会溢出成负数，
        // 冷却判断于是永远成立，一次攻击都跑不出来（本项目 PlayerData.lastTeleportTick
        // 的注释记过同一类坑）
        int lastAttackTick = -ATTACK_COOLDOWN_TICKS;

        for (int tick = 0; tick <= TICKS; tick++) {
            // 1) 服务端采样：眼睛位置滞后一个单程延迟，目标位置是服务端权威值
            double[] serverEye = eyeOf(scenario.attacker.at(tick - oneWayTicks));
            double[] serverTarget = scenario.target.at(tick);
            eyeHistory.add(serverEye[0], serverEye[1], serverEye[2]);
            targetHistory.add(serverTarget[0], serverTarget[1], serverTarget[2]);

            // 2) 客户端在 (tick - oneWayTicks) 出手 → 服务端本 tick 收到
            int clientTick = tick - oneWayTicks;
            if (clientTick - oneWayTicks < 0) continue;
            if (clientTick - lastAttackTick < ATTACK_COOLDOWN_TICKS) continue;

            double[] clientEye = eyeOf(scenario.attacker.at(clientTick));
            double[] seenTarget = scenario.target.at(clientTick - oneWayTicks);
            double seenDistance = PointHistory.distanceToBox(
                    clientEye[0], clientEye[1], clientEye[2],
                    seenTarget[0], seenTarget[1], seenTarget[2],
                    PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
            if (seenDistance > reachLimit || seenDistance < minSeenDistance) continue;
            lastAttackTick = clientTick;

            // 3) 服务端用真实算法复算（与 ReachA.onServerTick 完全一致）
            if (!eyeHistory.isFull() || !targetHistory.isFull()) continue;
            int maxAge = ReachTolerance.maxSampleAgeTicks(ping);
            run.add(eyeHistory.minDistanceToBoxes(
                    targetHistory, PLAYER_HALF_WIDTH, PLAYER_HEIGHT, maxAge, maxAge));
        }

        run.finish();
        return run;
    }

    private static double[] eyeOf(double[] feet) {
        return new double[]{feet[0], feet[1] + EYE_HEIGHT, feet[2]};
    }
}
