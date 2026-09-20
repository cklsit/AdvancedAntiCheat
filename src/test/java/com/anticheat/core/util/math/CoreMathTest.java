package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 纯数学工具单测。
 *
 * <p>这里的每个函数都被至少一条检测直接依赖，且都属于"写错了不会崩、
 * 只会静默给出错误结论"的类型——正是最需要单测的地方。</p>
 */
class CoreMathTest {

    @Test
    @DisplayName("角度差必须走最短路径，处理 ±180 环绕")
    void deltaDegreesWrapsAround() {
        // 179 -> -179 实际只转了 2 度，而不是 358 度
        assertEquals(2.0f, CoreMath.deltaDegrees(179.0f, -179.0f), 1e-4f);
        assertEquals(-2.0f, CoreMath.deltaDegrees(-179.0f, 179.0f), 1e-4f);
        assertEquals(90.0f, CoreMath.deltaDegrees(0.0f, 90.0f), 1e-4f);
        assertEquals(-90.0f, CoreMath.deltaDegrees(0.0f, -90.0f), 1e-4f);
        // 边界：180 度落在取值区间内（区间是 (-180, 180]）
        assertEquals(180.0f, CoreMath.deltaDegrees(0.0f, 180.0f), 1e-4f);
    }

    @Test
    @DisplayName("角度距离恒为非负且不超过 180")
    void distanceInDegreesIsAbsolute() {
        assertEquals(2.0f, CoreMath.distanceInDegrees(179.0f, -179.0f), 1e-4f);
        assertEquals(2.0f, CoreMath.distanceInDegrees(-179.0f, 179.0f), 1e-4f);
        assertEquals(180.0f, CoreMath.distanceInDegrees(0.0f, 180.0f), 1e-4f);
        assertEquals(0.0f, CoreMath.distanceInDegrees(37.5f, 37.5f), 1e-4f);
    }

    @Test
    @DisplayName("香农熵：单一取值 0 bit，四个等概率取值 2 bit")
    void shannonEntropyKnownValues() {
        // 只有一个取值 -> 0 bit（这正是"恒定点击间隔"的特征）
        assertEquals(0.0, CoreMath.shannonEntropyBits(new double[]{100, 100, 100, 100}), 1e-9);

        // 四个等概率取值 -> -4 * 0.25 * log2(0.25) = 2 bit
        assertEquals(2.0, CoreMath.shannonEntropyBits(new double[]{1, 2, 3, 4}), 1e-9);

        // 两个等概率取值 -> 1 bit
        assertEquals(1.0, CoreMath.shannonEntropyBits(new double[]{5, 5, 9, 9}), 1e-9);

        // 分布越集中熵越低：三个 1 与一个 2 应低于 2 bit
        double skew = CoreMath.shannonEntropyBits(new double[]{1, 1, 1, 2});
        org.junit.jupiter.api.Assertions.assertTrue(skew > 0.0 && skew < 1.0,
                "偏斜分布的熵应落在 (0,1)，实际 " + skew);
    }

    @Test
    @DisplayName("香农熵：样本不足 2 个时返回 0 而不是抛异常")
    void shannonEntropyHandlesTinyInput() {
        assertEquals(0.0, CoreMath.shannonEntropyBits(new double[]{}), 1e-9);
        assertEquals(0.0, CoreMath.shannonEntropyBits(new double[]{7.0}), 1e-9);
    }

    @Test
    @DisplayName("clamp 上下界都生效（上界为负数也能用）")
    void clampWorks() {
        assertEquals(5.0, CoreMath.clamp(5.0, 0.0, 10.0), 1e-9);
        assertEquals(0.0, CoreMath.clamp(-3.0, 0.0, 10.0), 1e-9);
        assertEquals(10.0, CoreMath.clamp(99.0, 0.0, 10.0), 1e-9);
        assertEquals(-1.0, CoreMath.clamp(-1.0, -1.0, -0.5), 1e-9);
    }

    @Test
    @DisplayName("TimerBalance 的构造参数校验会把非法值挡在启动期")
    void timerBalanceRejectsBadConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new com.anticheat.core.check.impl.timer.TimerBalance(0.0, 100.0, 0.75, 1000.0));
        assertThrows(IllegalArgumentException.class,
                () -> new com.anticheat.core.check.impl.timer.TimerBalance(50.0, 0.0, 0.75, 1000.0));
    }
}
