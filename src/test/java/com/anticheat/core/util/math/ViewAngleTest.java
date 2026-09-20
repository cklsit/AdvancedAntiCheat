package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视线夹角单测。
 *
 * <p>这是"射线类"检测的判据内核。阈值（默认 75 度）之所以能开到这么大，
 * 全靠这里"多次朝向 × 多次目标位置取最小"的容差；一旦这个取最小写错，
 * 检测要么形同关闭，要么开始误报。所以用手算过的几何关系把它钉死。</p>
 *
 * <p>朝向约定（与 Minecraft 一致）：yaw 0 面向 +Z，yaw 90 面向 -X，
 * pitch -90 朝正上方。</p>
 */
class ViewAngleTest {

    private static final double EYE_Y = 1.62;
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;

    /** 单点历史，方便构造。 */
    private static PointHistory one(double x, double y, double z) {
        PointHistory history = new PointHistory(1);
        history.add(x, y, z);
        return history;
    }

    private static double angle(FloatArrayLike rotations, PointHistory targets) {
        return ViewAngle.minAngleOffBox(
                0.0, EYE_Y, 0.0,
                rotations.yaws, rotations.pitches, rotations.count,
                new double[]{targets.x(0)}, new double[]{targets.y(0)}, new double[]{targets.z(0)}, targets.getSize(),
                PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
    }

    private static final class FloatArrayLike {
        final float[] yaws;
        final float[] pitches;
        final int count;

        FloatArrayLike(float[] yaws, float[] pitches) {
            this.yaws = yaws;
            this.pitches = pitches;
            this.count = yaws.length;
        }
    }

    @Test
    @DisplayName("正前方目标 + 水平朝向：夹角为 0（最近点候选正好落在视线上）")
    void targetInFrontIsZero() {
        double value = angle(new FloatArrayLike(new float[]{0f}, new float[]{0f}), one(0, 0, 3));
        assertEquals(0.0, value, 1e-6,
                "最近点 (0,1.62,2.7) 与视线 (0,0,1) 同向，夹角必须是 0");
    }

    @Test
    @DisplayName("目标在正后方：夹角接近 180（这是最该被抓的情形）")
    void targetBehindIsNearMax() {
        double value = angle(new FloatArrayLike(new float[]{0f}, new float[]{0f}), one(0, 0, -3));
        assertTrue(value > 160.0, "背后目标的夹角应接近 180，实际 " + value);
    }

    @Test
    @DisplayName("目标在正侧面：夹角约 90")
    void targetToTheSideIsNinety() {
        double value = angle(new FloatArrayLike(new float[]{0f}, new float[]{0f}), one(3, 0, 0));
        assertEquals(90.0, value, 1e-6);
    }

    @Test
    @DisplayName("俯仰：朝正上方时正前方的目标夹角为 90")
    void lookingStraightUp() {
        double value = angle(new FloatArrayLike(new float[]{0f}, new float[]{-90f}), one(0, 0, 3));
        assertEquals(90.0, value, 1e-6);
    }

    @Test
    @DisplayName("yaw 90 面向 -X：目标在 -X 侧夹角为 0")
    void yawNinetyFacesNegativeX() {
        double value = angle(new FloatArrayLike(new float[]{90f}, new float[]{0f}), one(-3, 0, 0));
        assertEquals(0.0, value, 1e-6);
    }

    @Test
    @DisplayName("多个朝向之间取最小：只要有一次看起来对准就放过（容差的关键）")
    void takesMinimumAcrossRotations() {
        // 第 0 个朝向背对目标，第 1 个朝向正对目标。
        // "取最小"意味着结果由第 1 个决定 —— 这正是快速甩鼠标时的真实情况。
        double value = angle(
                new FloatArrayLike(new float[]{180f, 0f}, new float[]{0f, 0f}),
                one(0, 0, 3));
        assertEquals(0.0, value, 1e-6,
                "必须取最小；若取最大或取平均，正常转身就会被打成违规");
    }

    @Test
    @DisplayName("所有朝向都背对目标时给出大夹角")
    void allRotationsFacingAway() {
        double value = angle(
                new FloatArrayLike(new float[]{180f, 175f}, new float[]{0f, 0f}),
                one(0, 0, 3));
        assertTrue(value > 160.0, "全部背对时夹角应接近 180，实际 " + value);
    }

    @Test
    @DisplayName("样本不足返回负数哨兵（绝不能用 180 当哨兵）")
    void undecidableUsesNegativeSentinel() {
        assertTrue(ViewAngle.UNDECIDABLE_ANGLE < 0.0,
                "180 度是'目标在正后方'的真实取值，拿它当哨兵会让最该抓的行为被跳过");

        double noRotation = ViewAngle.minAngleOffBox(
                0, EYE_Y, 0,
                new float[1], new float[1], 0,
                new double[]{0}, new double[]{0}, new double[]{3}, 1,
                PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
        assertEquals(ViewAngle.UNDECIDABLE_ANGLE, noRotation, 1e-9);

        double noTarget = ViewAngle.minAngleOffBox(
                0, EYE_Y, 0,
                new float[]{0f}, new float[]{0f}, 1,
                new double[1], new double[1], new double[1], 0,
                PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
        assertEquals(ViewAngle.UNDECIDABLE_ANGLE, noTarget, 1e-9);
    }

    @Test
    @DisplayName("眼睛位于盒子内部时夹角为 0（退化情形也必须放行）")
    void eyeInsideBoxIsZero() {
        PointHistory targets = one(0, 0, 0);
        double value = ViewAngle.minAngleOffBox(
                0.0, 0.9, 0.0,
                new float[]{180f}, new float[]{30f}, 1,
                new double[]{targets.x(0)}, new double[]{targets.y(0)}, new double[]{targets.z(0)}, 1,
                PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
        assertEquals(0.0, value, 1e-9, "眼睛在盒子内时方向向量退化，必须视为对准而不是异常");
    }
}
