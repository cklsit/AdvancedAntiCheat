package com.anticheat.core.check.impl.movement;

import com.anticheat.core.check.impl.world.NukerA;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 移动类检测的**阈值不变量**。
 *
 * <p>这些断言守的不是"某个数等于多少"，而是几条一旦写反就会静默失效或成批误报的性质。
 * 它们全都是可以在编译期之后、上线之前验证的，因此比"到真机上看看有没有误报"便宜得多。</p>
 *
 * <h3>三条最重要的性质</h3>
 * <ol>
 *   <li>**单次孤立异常不可能触发告警**：每 tick 计入的证据封顶值必须小于累积线。
 *       少了这条，一次爆炸、一次激流、一次攒包补发就能把一个正常玩家踢下线；</li>
 *   <li>**封顶值不能削掉作弊的特征值**：悬停的每拍 shortfall 是 0.0784，
 *       封顶值若低于它，作弊者的累积速率会被削平，检测就退化成"要悬停很久才告警"；</li>
 *   <li>**阈值必须落在合法区间之外**：疾跑方向的上限要大于斜向输入的几何极限（45 度），
 *       速度上限要大于蓝冰滑行的常见值，挖掘视角上限要大于方块的几何张角（约 13 度）。
 *       卡在这些极限之内等于把正常玩法判成作弊。</li>
 * </ol>
 */
class MovementCheckThresholdsTest {

    // ------------------------------------------------------------------ FlyA

    @Test
    @DisplayName("FlyA：单拍的证据封顶小于累积线（一次爆炸不可能触发告警）")
    void flySingleAnomalyCannotFlag() {
        assertTrue(FlyA.MAX_SHORTFALL_PER_TICK < FlyA.FLAG_BALANCE,
                "封顶 " + FlyA.MAX_SHORTFALL_PER_TICK + " >= 累积线 " + FlyA.FLAG_BALANCE
                        + "：一次孤立异常（爆炸 / 激流 / 攒包）就能单独触发飞行告警");
        assertTrue(FlyA.FLAG_BALANCE / FlyA.MAX_SHORTFALL_PER_TICK >= 3.0,
                "至少需要 3 拍连续异常才应告警，当前只需 "
                        + (FlyA.FLAG_BALANCE / FlyA.MAX_SHORTFALL_PER_TICK) + " 拍");
    }

    @Test
    @DisplayName("FlyA：封顶值不削掉悬停的特征值（否则作弊者被变相放宽）")
    void flyCapDoesNotClipTheHoverSignature() {
        // 悬停时每拍的 shortfall 恰好等于"应有的下落量"，见 VerticalMotionTest
        double hoverShortfall = VerticalMotion.shortfall(0.0, 0.0);
        assertEquals(0.0784, hoverShortfall, 1e-6);
        assertTrue(FlyA.MAX_SHORTFALL_PER_TICK > hoverShortfall,
                "封顶值 " + FlyA.MAX_SHORTFALL_PER_TICK + " 小于悬停特征值 " + hoverShortfall
                        + "：悬停会被削平，告警延迟被人为拉长");
    }

    @Test
    @DisplayName("FlyA：腾空宽限期至少盖住离开地面的第一拍")
    void flyGraceCoversTheFirstAirborneTick() {
        assertTrue(FlyA.GRACE_TICKS >= 1,
                "宽限期为 0 时，离开地面的第一拍没有可比的上一拍增量，必然误判");
        assertTrue(FlyA.GRACE_TICKS <= VerticalMotion.apexTick(VerticalMotion.JUMP_VELOCITY),
                "宽限期 " + FlyA.GRACE_TICKS + " 超过了原版跳跃的上升段 "
                        + VerticalMotion.apexTick(VerticalMotion.JUMP_VELOCITY)
                        + " 拍：上升段本身满足重力递推，宽限更长只是白送绕过空间");
    }

    @Test
    @DisplayName("FlyA：容差远小于悬停特征值，否则检测不到任何东西")
    void flyToleranceIsBelowTheHoverSignature() {
        assertTrue(VerticalMotion.DEFAULT_TOLERANCE < VerticalMotion.shortfall(0.0, 0.0),
                "容差 " + VerticalMotion.DEFAULT_TOLERANCE + " 大于等于悬停的 shortfall，"
                        + "悬停式飞行会被整体放过");
    }

    // ------------------------------------------------------------------ GroundSpoofA

    @Test
    @DisplayName("GroundSpoofA：需要连续多拍矛盾状态才告警")
    void groundSpoofNeedsSustainedContradiction() {
        assertTrue(GroundSpoofA.FLAG_BALANCE >= 3.0,
                "累积线 " + GroundSpoofA.FLAG_BALANCE + " 太低：单次测量抖动就能告警");
        assertTrue(GroundSpoofA.DECAY_PER_TICK < 1.0,
                "降温量 >= 每拍累积量 1.0：证据永远攒不起来，检测等于没开");
        assertTrue(GroundSpoofA.MAX_GROUNDED_DESCENT > 0.6,
                "上限 " + GroundSpoofA.MAX_GROUNDED_DESCENT
                        + " 不高于原版自动上台阶高度 0.6：走下台阶会被判成免摔作弊");
        assertTrue(GroundSpoofA.SUPPORT_PROBE_DEPTH >= 1.0,
                "支撑探测深度 " + GroundSpoofA.SUPPORT_PROBE_DEPTH
                        + " 不足 1 格：站在方块顶面上的合法情形会被判成脚下无支撑");
        assertTrue(GroundSpoofA.SUPPORT_PROBE_DEPTH <= 2.0,
                "支撑探测深度 " + GroundSpoofA.SUPPORT_PROBE_DEPTH
                        + " 超过 2 格：落地前的一整段坠落都会被当成有支撑而放过");
    }

    // ------------------------------------------------------------------ SprintA

    @Test
    @DisplayName("SprintA：夹角上限落在斜向输入的几何极限之外")
    void sprintAngleIsAboveTheDiagonalLimit() {
        assertTrue(SprintA.DEFAULT_MAX_ANGLE > 45.0,
                "上限 " + SprintA.DEFAULT_MAX_ANGLE + " 不高于斜向输入（W+A）的 45 度极限："
                        + "正常斜向疾跑会被判成作弊");
        assertTrue(SprintA.DEFAULT_MAX_ANGLE < 120.0,
                "上限 " + SprintA.DEFAULT_MAX_ANGLE + " 高于 120 度："
                        + "向后疾跑（约 180 度）仍会被抓，但纯侧移（约 90 度）就完全放过了");
        assertTrue(SprintA.FLAG_BALANCE >= 5.0,
                "累积线 " + SprintA.FLAG_BALANCE
                        + " 太低：转向与疾跑状态之间的一拍错位就能告警");
        assertTrue(SprintA.MIN_JUDGED_SPEED > 0.0,
                "没有最小速度门槛时，静止玩家的位移方向由浮点噪声决定，夹角没有意义");
        assertTrue(SprintA.MAX_PLAYER_DRIVEN_SPEED > SprintA.MIN_JUDGED_SPEED,
                "外力门槛必须高于最小判定速度，否则两者之间的区间无人处理");
    }

    // ------------------------------------------------------------------ SpeedA

    @Test
    @DisplayName("SpeedA：默认上限高于蓝冰滑行的常见速度（否则冰道玩家会被误判）")
    void speedThresholdIsAboveBlueIce() {
        assertTrue(SpeedA.DEFAULT_MAX_SPEED_PER_TICK > 1.0,
                "上限 " + SpeedA.DEFAULT_MAX_SPEED_PER_TICK
                        + " 不高于蓝冰疾跑跳跃的常见上限（约 1.0 格/tick 且是持续的）："
                        + "没有地表模型时这会成批误报");
        assertTrue(SpeedA.MAX_EXCESS_PER_UPDATE < SpeedA.FLAG_BALANCE,
                "封顶 " + SpeedA.MAX_EXCESS_PER_UPDATE + " >= 累积线 " + SpeedA.FLAG_BALANCE
                        + "：一次激流冲刺（3 格/tick 以上）就能单独触发告警");
    }

    // ------------------------------------------------------------------ InventoryMoveA

    @Test
    @DisplayName("InventoryMoveA：最小位移门槛低于原版行走速度（否则完全检测不到）")
    void inventoryMoveCountsNormalWalking() {
        final double vanillaWalk = 0.1;
        final double vanillaSprint = 0.13;
        assertTrue(InventoryMoveA.MIN_TICK_MOVEMENT < vanillaWalk,
                "门槛 " + InventoryMoveA.MIN_TICK_MOVEMENT + " 不低于原版行走速度 " + vanillaWalk
                        + "：走路会被整体跳过，检测等于没开");
        assertTrue(InventoryMoveA.FLAG_BALANCE >= vanillaSprint * 10,
                "累积线 " + InventoryMoveA.FLAG_BALANCE
                        + " 太低：被撞一下的滑行距离就能告警");
    }

    // ------------------------------------------------------------------ SpeedB

    @Test
    @DisplayName("SpeedB：单次证据封顶小于累积线（一次攒包不可能触发告警）")
    void speedBSingleAnomalyCannotFlag() {
        assertTrue(SpeedB.MAX_EXCESS_PER_UPDATE < SpeedB.FLAG_BALANCE,
                "封顶 " + SpeedB.MAX_EXCESS_PER_UPDATE + " >= 累积线 " + SpeedB.FLAG_BALANCE
                        + "：一次孤立的超速读数就能单独触发告警");
    }

    @Test
    @DisplayName("SpeedB：阈值必须落在实测合法区间之外，又不能放过常见作弊档位")
    void speedBAverageThresholdSitsBetweenCheatingAndLegal() {
        // 实测（真实绕过证据包）：普通地面疾跑窗口平均 0.256（理论值约 0.28），
        // 含飞行类作弊动作的那段也才 0.75 —— 所以 1.2 以下会碰到合法区间；
        // 而常见的持续超速档位在 1.5~2.0 左右，阈值高过 2.5 就等于放过它们。
        assertTrue(SpeedB.DEFAULT_MAX_AVG_SPEED >= 1.2,
                "阈值 " + SpeedB.DEFAULT_MAX_AVG_SPEED
                        + " 太低：会压到冰道交通与攒包噪声所在的合法区间");
        assertTrue(SpeedB.DEFAULT_MAX_AVG_SPEED <= 2.5,
                "阈值 " + SpeedB.DEFAULT_MAX_AVG_SPEED
                        + " 太高：常见的持续超速档位会被放过");
    }

    @Test
    @DisplayName("SpeedB：窗口足够长，能把一次攒包补发摊平")
    void speedBWindowSmoothsBatchedPackets() {
        // 证据包里出现过 2.05 格/tick 的单拍极值（多拍位移被合并成一包送达）。
        // 窗口里混入这样一次尖峰，它对平均值的贡献是 2.0 / WINDOW_TICKS。
        double contamination = 2.0 / SpeedB.WINDOW_TICKS;
        assertTrue(contamination <= SpeedB.MAX_EXCESS_PER_UPDATE,
                "窗口 " + SpeedB.WINDOW_TICKS + " tick 太短：一次攒包就贡献 "
                        + contamination + " 的证据，足以单独推动告警");
        assertTrue(SpeedB.WINDOW_TICKS >= 10,
                "窗口 " + SpeedB.WINDOW_TICKS + " tick 太短：平均值会被单拍尖峰主导，"
                        + "那与 SpeedA 的逐次判据就没有区别了");
    }

    // ------------------------------------------------------------------ NukerA

    @Test
    @DisplayName("NukerA：单 tick 挖掘门槛容忍瞬间破坏 + 高频点击")
    void nukerRateToleratesInstaBreak() {
        assertTrue(NukerA.MAX_STARTS_PER_TICK >= 2,
                "门槛 " + NukerA.MAX_STARTS_PER_TICK
                        + " 低于 2：生存模式挖火把（一击即破）配蝴蝶点击约 20 CPS 会被误判");
        assertTrue(NukerA.MAX_STARTS_PER_TICK <= 5,
                "门槛 " + NukerA.MAX_STARTS_PER_TICK + " 高于 5：nuker 每 tick 下手十几个方块，"
                        + "门槛太高等于放过它");
    }

    @Test
    @DisplayName("NukerA：视角上限大于方块的几何张角")
    void nukerAngleIsAboveTheGeometricLimit() {
        // 方块边长 1 格、最大交互距离 4.5 格 -> 半张角 atan(0.5/4.5) ≈ 6.3 度
        assertTrue(NukerA.DEFAULT_MAX_ANGLE > 20.0,
                "上限 " + NukerA.DEFAULT_MAX_ANGLE + " 太贴近几何极限："
                        + "转身与挖掘之间的一拍错位就会误判");
        assertTrue(NukerA.DEFAULT_MAX_ANGLE < 90.0,
                "上限 " + NukerA.DEFAULT_MAX_ANGLE
                        + " 达到 90 度：不转视角的 nuker 通常夹角在 90 度以上，会被放过");
        assertTrue(NukerA.FLAG_BALANCE > 1.0,
                "视角判据是统计推断，累积线必须大于 1 次");
    }

    @Test
    @DisplayName("新增的移动类检测都参与违规衰减（不会只增不减）")
    void everyNewCheckDecays() {
        // 这是 Check 框架层面的要求：合规路径上必须有 reward()，
        // 否则长时间在线后任何一次偶然抖动都会顶到阈值。
        // decay 注解值 > 0 是它唯一能在离线单测里验证的痕迹。
        assertFalse(FlyA.DECAY_PER_TICK <= 0.0, "FlyA 的余额没有降温通道");
        assertFalse(GroundSpoofA.DECAY_PER_TICK <= 0.0, "GroundSpoofA 的余额没有降温通道");
        assertFalse(SprintA.DECAY_PER_TICK <= 0.0, "SprintA 的余额没有降温通道");
        assertFalse(SpeedA.DECAY_PER_UPDATE <= 0.0, "SpeedA 的余额没有降温通道");
        assertFalse(SpeedB.DECAY_PER_UPDATE <= 0.0, "SpeedB 的余额没有降温通道");
        assertFalse(InventoryMoveA.DECAY_PER_TICK <= 0.0, "InventoryMoveA 的余额没有降温通道");
        assertFalse(NukerA.DECAY_PER_HIT <= 0.0, "NukerA 的余额没有降温通道");
    }
}
