package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RayBox} 的回归测试——"准星是否真的落在命中盒上"这一判据的唯一实现。
 *
 * <p>这里最重要的不是几个 hit/miss 用例，而是
 * {@link #angleIsNotEnoughToDecideHit()}：它把"为什么不能用夹角阈值替代射线求交"
 * 钉成可执行的证据。改用夹角近似会同时引入两个方向的错误
 * （近处漏判、远处误判），而且改动的后果不会以任何形式报错。</p>
 */
class RayBoxTest {

    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    @Test
    @DisplayName("正面命中：返回值就是到盒子前表面的距离")
    void straightHit() {
        double t = RayBox.intersect(
                0.0, 1.62, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT);
        // 盒子 x 范围 [2.7, 3.3]，从 x=0 出发沿 +x 命中前表面
        assertEquals(2.7, t, 1e-9);
    }

    @Test
    @DisplayName("从盒子内部出发：t = 0，视为命中（贴脸攻击）")
    void originInsideBox() {
        assertTrue(RayBox.hits(
                3.0, 0.9, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("背对目标：未命中（t 全为负，不能把反向的交点算进来）")
    void pointingAway() {
        assertFalse(RayBox.hits(
                10.0, 0.9, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("从盒子上方平行掠过：未命中")
    void parallelAbove() {
        assertFalse(RayBox.hits(
                0.0, 5.0, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("与某轴平行且落在该轴范围内：命中（平行不应直接判 miss）")
    void parallelInside() {
        assertTrue(RayBox.hits(
                3.0, 0.5, -5.0,
                0.0, 0.0, 1.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("平行但在该轴范围外：未命中")
    void parallelOutside() {
        assertFalse(RayBox.hits(
                5.0, 0.5, -5.0,
                0.0, 0.0, 1.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("恰好擦着盒子边缘：仍算命中（边界不能算漏）")
    void grazingEdgeCounts() {
        assertTrue(RayBox.hits(
                0.0, HEIGHT, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT));
    }

    /**
     * 这条是给"能不能用夹角阈值替代射线求交"这个问题钉的钉子。
     *
     * <p>同样 3 度的偏差：2 格处横向只偏 0.105 格（仍在半宽 0.3 的盒内，命中）；
     * 8 格处横向偏 0.42 格（已飞出盒外，未命中）。夹角阈值无论定多少，
     * 都必然在近处或远处错一边。</p>
     */
    @Test
    @DisplayName("角度不是命中：同样 3 度偏差，近处命中、远处脱靶")
    void angleIsNotEnoughToDecideHit() {
        double threeDegrees = Math.toRadians(3.0);

        boolean near = RayBox.hits(
                0.0, 0.9, 0.0,
                Math.cos(threeDegrees), 0.0, Math.sin(threeDegrees),
                2.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT);
        boolean far = RayBox.hits(
                0.0, 0.9, 0.0,
                Math.cos(threeDegrees), 0.0, Math.sin(threeDegrees),
                8.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT);

        assertTrue(near, "2 格处 3 度偏差仍在盒内，必须判为命中");
        assertFalse(far, "8 格处 3 度偏差已经脱靶，必须判为未命中");
    }

    @Test
    @DisplayName("未命中返回 NaN 而不是 0（0 是合法的'贴在盒内'）")
    void missIsNaN() {
        double t = RayBox.intersect(
                0.0, 5.0, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                HALF_WIDTH, HEIGHT);
        assertTrue(Double.isNaN(t));
    }
}
