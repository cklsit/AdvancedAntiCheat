package com.anticheat.core.check.impl.movement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版垂直运动模型单测 —— 飞行检测（{@link FlyA}）的全部判据来源。
 *
 * <p>为什么它值得单测：这条判据的**方向**只要写反一次，后果就是两种极端之一——
 * 要么所有跳跃与坠落都被判成飞行（把正常玩家全踢了），
 * 要么所有飞行都不触发（线上完全静默）。两种故障在真机上都不容易复现与定位，
 * 但都可以用十几行纯数值仿真钉死。</p>
 *
 * <p>测试里的每一条"原版序列"都是用 {@link VerticalMotion#expectedDelta(double)}
 * 自己递推出来的，因此它们同时验证了两件事：模型自洽，且判据不会命中自洽的序列。</p>
 */
class VerticalMotionTest {

    private static final double TOLERANCE = VerticalMotion.DEFAULT_TOLERANCE;

    @Test
    @DisplayName("原版跳跃的整条弧线都不会被判为飞行")
    void vanillaJumpArcIsNeverFlagged() {
        double delta = VerticalMotion.JUMP_VELOCITY;
        for (int tick = 1; tick <= 40; tick++) {
            double next = VerticalMotion.expectedDelta(delta);
            assertFalse(VerticalMotion.isFallingTooSlowly(delta, next, TOLERANCE),
                    "跳跃第 " + tick + " 拍被误判：上一拍=" + delta + " 本拍=" + next);
            delta = next;
        }
    }

    @Test
    @DisplayName("走出悬崖的自由落体不会被判为飞行")
    void freeFallIsNeverFlagged() {
        // 走出悬崖的第一拍初速度是 0（没有起跳冲量）
        double delta = 0.0;
        for (int tick = 1; tick <= 400; tick++) {
            double next = VerticalMotion.expectedDelta(delta);
            assertFalse(VerticalMotion.isFallingTooSlowly(delta, next, TOLERANCE),
                    "自由落体第 " + tick + " 拍被误判：上一拍=" + delta + " 本拍=" + next);
            delta = next;
        }
        // 收敛到终端速度的速率是每拍 0.98，从 0 出发需要约 220 拍才能进到 0.05 以内
        assertEquals(VerticalMotion.TERMINAL_VELOCITY, delta, 0.01,
                "长时间自由落体应收敛到终端速度");
    }

    @Test
    @DisplayName("悬停（增量恒为 0）持续命中判据")
    void hoveringIsFlagged() {
        double delta = 0.0;
        for (int tick = 1; tick <= 10; tick++) {
            assertTrue(VerticalMotion.isFallingTooSlowly(delta, 0.0, TOLERANCE),
                    "悬停第 " + tick + " 拍没有被检出");
            // 每拍的 shortfall 就是"应有的下落量"，悬停时它是常数
            assertEquals(0.0784, VerticalMotion.shortfall(delta, 0.0), 1e-6);
        }
    }

    @Test
    @DisplayName("匀速上升式飞行持续命中判据")
    void constantAscentIsFlagged() {
        // 参考实现 FlyGeneric 的默认垂直速度是 0.44 格/tick
        double delta = 0.44;
        for (int tick = 1; tick <= 10; tick++) {
            assertTrue(VerticalMotion.isFallingTooSlowly(delta, 0.44, TOLERANCE),
                    "匀速上升第 " + tick + " 拍没有被检出");
        }
    }

    @Test
    @DisplayName("缓降式滑翔（增量恒为一个小负值）持续命中判据")
    void slowGlideIsFlagged() {
        double delta = -0.05;
        for (int tick = 1; tick <= 10; tick++) {
            assertTrue(VerticalMotion.isFallingTooSlowly(delta, -0.05, TOLERANCE),
                    "缓降滑翔第 " + tick + " 拍没有被检出");
        }
    }

    @Test
    @DisplayName("缓降药水效果的运动与作弊悬停在数值上无法区分（必须靠上下文让路）")
    void slowFallingEffectIsIndistinguishableFromCheating() {
        // 缓降效果把每 tick 的重力从 0.08 降到 0.01，阻力不变
        double delta = 0.0;
        int flagged = 0;
        for (int tick = 0; tick < 20; tick++) {
            double next = (delta - 0.01) * VerticalMotion.DRAG;
            if (VerticalMotion.isFallingTooSlowly(delta, next, TOLERANCE)) {
                flagged++;
            }
            delta = next;
        }
        assertTrue(flagged >= 15,
                "缓降效果应当几乎每一拍都命中判据（实测 " + flagged + "/20）——"
                        + "这正是 ServerSnapshot.movementEffectActive 让路分支存在的原因；"
                        + "少了它，每个喝缓降药水的玩家都会被判成飞行");
    }

    @Test
    @DisplayName("爆炸冲量只影响一拍，随后立刻回到重力递推上")
    void explosionImpulseIsASingleTickEvent() {
        double previous = 0.0;
        double impulse = 1.5;
        assertTrue(VerticalMotion.isFallingTooSlowly(previous, impulse, TOLERANCE),
                "冲量发生的那一拍确实会命中判据");

        // 之后每一拍都必须干净：这就是"余额法能吸收一次性事件"的物理依据
        double delta = impulse;
        for (int tick = 1; tick <= 20; tick++) {
            double next = VerticalMotion.expectedDelta(delta);
            assertFalse(VerticalMotion.isFallingTooSlowly(delta, next, TOLERANCE),
                    "冲量之后第 " + tick + " 拍仍被误判");
            delta = next;
        }
    }

    @Test
    @DisplayName("判据是单侧的：下落得比应有的更快永远不命中")
    void fallingFasterThanExpectedIsNeverFlagged() {
        // 攒包（一 tick 内补发多个位置包）会让服务端这一拍的位移是多拍之和，
        // 也就是"下落得比应有的更快"。判据必须对这种情况完全免疫，
        // 否则每一次网络抖动都会变成飞行告警。
        double[] previousDeltas = {0.0, -0.5, -1.0, -3.92};
        for (double previous : previousDeltas) {
            double expected = VerticalMotion.expectedDelta(previous);
            for (double extra = 0.1; extra <= 5.0; extra += 0.1) {
                assertFalse(VerticalMotion.isFallingTooSlowly(previous, expected - extra, TOLERANCE),
                        "previous=" + previous + " 时多掉 " + extra + " 格被误判了");
            }
        }
    }

    @Test
    @DisplayName("原版跳跃高度约 1.25 格、上升段 6 拍（宽限期的取值依据）")
    void vanillaJumpGeometry() {
        int apex = VerticalMotion.apexTick(VerticalMotion.JUMP_VELOCITY);
        assertEquals(6, apex,
                "原版起跳的上升段拍数变了，FlyA 的 grace-ticks 取值需要重新论证");

        // 最高点就在上升段的最后一拍：第 7 拍的增量已经转负，累计高度开始回落
        double height = VerticalMotion.displacementAfter(VerticalMotion.JUMP_VELOCITY, apex);
        assertEquals(1.25, height, 0.02, "累计上升高度应约等于原版的 1.25 格");
        assertTrue(VerticalMotion.displacementAfter(VerticalMotion.JUMP_VELOCITY, apex + 1) < height,
                "越过上升段之后累计高度应当回落");
        assertEquals(VerticalMotion.JUMP_VELOCITY,
                VerticalMotion.displacementAfter(VerticalMotion.JUMP_VELOCITY, 1), 1e-9,
                "第一拍的位移就应当是起跳初速度本身");
    }

    @Test
    @DisplayName("终端速度是递推的不动点（常量之间自洽）")
    void terminalVelocityIsAFixedPoint() {
        assertEquals(VerticalMotion.TERMINAL_VELOCITY,
                VerticalMotion.expectedDelta(VerticalMotion.TERMINAL_VELOCITY), 1e-9,
                "终端速度必须是 expectedDelta 的不动点，否则常量写错了");
    }
}
