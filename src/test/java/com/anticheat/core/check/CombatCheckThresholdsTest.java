package com.anticheat.core.check;

import com.anticheat.core.check.impl.aim.AimC;
import com.anticheat.core.check.impl.aim.RotationSnap;
import com.anticheat.core.check.impl.autoclicker.AutoClickerD;
import com.anticheat.core.check.impl.autoclicker.ClickStreaks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗类新检测的**阈值不变量**。
 *
 * <p>与 {@code com.anticheat.core.check.impl.movement.MovementCheckThresholdsTest} 同一路数：
 * 守的不是"某个数等于多少"，而是几条一旦写反就会**静默失效或成批误报**的性质。
 * 这些性质在离线单测里就能验证，比"到真机上看看有没有误报"便宜得多。</p>
 *
 * <h3>三条性质</h3>
 * <ol>
 *   <li>**一次最强证据不能单独告警**：瞬转的大角度甩枪是 PVP 基本功，
 *       若累积线低于单次最强权重，一次正常的 180 度回头就能把人踢下线；</li>
 *   <li>**证据必须有降温通道，且尺度合理**：只增不减的余额会让长时间在线后
 *       任何一次偶然抖动顶到阈值；而降温太快则连续作战里的瞬转永远攒不起来；</li>
 *   <li>**窗口长度与判定线要匹配**：连击段的长度被窗口截断，
 *       窗口太短会让再长的连击也达不到判定线，检测退化成"永不触发"。</li>
 * </ol>
 */
class CombatCheckThresholdsTest {

    // ------------------------------------------------------------------ AimC

    @Test
    @DisplayName("AimC：一次最强的瞬转证据也不能单独触发告警")
    void aimCSingleStrongestSnapCannotFlagAlone() {
        double strongest = RotationSnap.weight(RotationSnap.IMPOSSIBLE_DEGREES + 1.0);

        assertTrue(strongest < AimC.FLAG_BALANCE,
                "单次最强证据 " + strongest + " >= 累积线 " + AimC.FLAG_BALANCE
                        + "：一次甩枪（PVP 基本功）就能单独把人踢下线");
        assertTrue(AimC.FLAG_BALANCE / strongest <= 2.0,
                "累积线需要 " + (AimC.FLAG_BALANCE / strongest) + " 次最强证据才触发："
                        + "灵敏度过低，连续的大角度瞬转也会被放过");
    }

    @Test
    @DisplayName("AimC：证据的存活时间是秒级（既要能攒起来，也不能赖着不走）")
    void aimCBalanceDecaysWithinSeconds() {
        assertTrue(AimC.DECAY_PER_TICK > 0.0, "余额没有降温通道");

        double secondsToZero = AimC.FLAG_BALANCE / AimC.DECAY_PER_TICK / 20.0;
        assertTrue(secondsToZero >= 5.0,
                "证据 " + secondsToZero + " 秒就散光了：连续作战中的瞬转攒不到判定线");
        assertTrue(secondsToZero <= 30.0,
                "证据能活 " + secondsToZero + " 秒：会把一局游戏里零散的几次甩枪算成一次持续作弊");
    }

    @Test
    @DisplayName("AimC：战斗时间窗要覆盖「瞬转后立刻挥臂」，但不能宽到把平时甩头包进来")
    void aimCCombatWindowIsTight() {
        assertTrue(AimC.COMBAT_WINDOW_MILLIS >= 100L,
                "时间窗 " + AimC.COMBAT_WINDOW_MILLIS + "ms 太短："
                        + "挥手包与朝向包不同步到达时会漏掉真实的瞬转");
        assertTrue(AimC.COMBAT_WINDOW_MILLIS <= 1000L,
                "时间窗 " + AimC.COMBAT_WINDOW_MILLIS + "ms 太宽："
                        + "追赶、环视时的快速甩头都会被算进战斗状态");
    }

    // ------------------------------------------------------------------ AutoClickerD

    @Test
    @DisplayName("AutoClickerD：窗口必须容得下一个超长段（否则连击永远达不到判定线）")
    void autoClickerDWindowFitsLongStreaks() {
        assertTrue(AutoClickerD.WINDOW_TICKS > ClickStreaks.MIN_STREAK * 2,
                "窗口 " + AutoClickerD.WINDOW_TICKS + " tick 相对门槛 " + ClickStreaks.MIN_STREAK
                        + " 太短：长段会被窗口边界截断，检测退化成永不触发");

        double bestSingleStreak = AutoClickerD.WINDOW_TICKS + ClickStreaks.ADJUST_WITHOUT_MULTI;
        assertTrue(ClickStreaks.FLAG_VL <= bestSingleStreak,
                "判定线 " + ClickStreaks.FLAG_VL + " 高于单段最高分 " + bestSingleStreak
                        + "：窗口里只出现一个长段时永远判不出来");
    }

    @Test
    @DisplayName("AutoClickerD：门槛本身必须是「原版不可能」的密度")
    void autoClickerDStreakThresholdIsImpossibleForVanilla() {
        // 原版 1.8 客户端左键冷却 10 tick，正常出手间隔约 10 拍。
        // 门槛若降到 3 以下，按住左键的蝴蝶点击就可能命中。
        assertTrue(ClickStreaks.MIN_STREAK >= 3,
                "门槛 " + ClickStreaks.MIN_STREAK + " tick 太低："
                        + "人类高频点击（蝴蝶点击）也能连成这么长的段");
        assertTrue(ClickStreaks.MIN_STREAK <= 10,
                "门槛 " + ClickStreaks.MIN_STREAK + " tick 太高："
                        + "接近原版出手间隔（10 tick），作弊者每 10 拍出一次手就永远躲得过去");
    }
}
