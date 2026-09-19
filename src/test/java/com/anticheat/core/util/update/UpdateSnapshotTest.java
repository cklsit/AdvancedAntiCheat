package com.anticheat.core.util.update;

import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位置 / 朝向更新快照单测。
 *
 * <p>这两个类是所有移动类检测的输入契约：检测只读它们，不再自己解析原始包。
 * 因此「deltaXZ 怎么算」「NaN 到底算不算非法」必须在这里钉死——
 * 一旦判据漂移，上层检测会集体失准。</p>
 */
class UpdateSnapshotTest {

    @Test
    @DisplayName("PositionUpdate：位移分量与水平位移量计算正确")
    void positionDeltas() {
        PositionUpdate u = new PositionUpdate(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(3.0, 4.0 + 64.0, 4.0),
                false);

        assertEquals(3.0, u.getDeltaX(), 1e-9);
        assertEquals(4.0, u.getDeltaY(), 1e-9);
        assertEquals(4.0, u.getDeltaZ(), 1e-9);
        assertEquals(5.0, u.getDeltaXZ(), 1e-9, "水平位移应为 sqrt(3^2+4^2)=5");
        assertEquals(Math.sqrt(9.0 + 16.0 + 16.0), u.getDeltaLength(), 1e-9);
        assertFalse(u.getHasInvalidValue(), "正常位移不应被判为非法");
    }

    @Test
    @DisplayName("PositionUpdate：NaN / Infinity 必须被识别为非法值")
    void positionInvalidValues() {
        assertTrue(new PositionUpdate(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(Double.NaN, 0.0, 0.0),
                false).getHasInvalidValue(), "NaN 坐标必须判非法");

        assertTrue(new PositionUpdate(
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, Double.POSITIVE_INFINITY, 0.0),
                false).getHasInvalidValue(), "Infinity 坐标必须判非法");

        // 起点是 NaN 也算——只要参与差值运算的是非法值，后续所有判据都不可信
        assertTrue(new PositionUpdate(
                new Vector3d(Double.NaN, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                false).getHasInvalidValue(), "起点 NaN 同样必须判非法");
    }

    @Test
    @DisplayName("RotationUpdate：pitch 越界 / NaN / Infinity 判非法，yaw 无界不判")
    void rotationValidity() {
        RotationUpdate ok = new RotationUpdate(0.0f, 90.0f, 0.0f, 45.0f);
        assertFalse(ok.getHasInvalidValue(), "合法朝向不应判非法");
        assertEquals(90.0f, ok.getDeltaYaw(), 1e-5f);
        assertEquals(45.0f, ok.getDeltaPitch(), 1e-5f);

        assertTrue(new RotationUpdate(0.0f, 0.0f, 0.0f, 90.1f).getHasInvalidValue(),
                "pitch > 90 必须判非法");
        assertTrue(new RotationUpdate(0.0f, 0.0f, 0.0f, -90.1f).getHasInvalidValue(),
                "pitch < -90 必须判非法");
        assertTrue(new RotationUpdate(0.0f, Float.NaN, 0.0f, 0.0f).getHasInvalidValue(),
                "yaw NaN 必须判非法");

        // yaw 无界：任意大值都是合法的，若这里判非法会造成大规模假阳性
        assertFalse(new RotationUpdate(0.0f, 99999.0f, 0.0f, 0.0f).getHasInvalidValue(),
                "yaw 无定义域，不应判非法");
    }

    @Test
    @DisplayName("RotationUpdate：恰好 ±90 是边界内合法值（不得 off-by-one 误伤）")
    void rotationBoundariesInclusive() {
        assertFalse(new RotationUpdate(0.0f, 0.0f, 0.0f, 90.0f).getHasInvalidValue(),
                "pitch 恰好 90 是合法的");
        assertFalse(new RotationUpdate(0.0f, 0.0f, 0.0f, -90.0f).getHasInvalidValue(),
                "pitch 恰好 -90 是合法的");
    }
}
