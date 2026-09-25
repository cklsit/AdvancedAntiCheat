package com.anticheat.core.check.impl.aim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 瞬转指纹判定单测。
 *
 * <p>这个判据最怕两件事，测试就是围绕它们写的：</p>
 * <ol>
 *   <li>**把连续甩鼠标当成瞬转**。真人 flick 时手腕运动会被分成连续数拍，
 *       如果只看"某拍转了很多度"就会把 PVP 基本功判成作弊。
 *       因此必须验证"前后两拍也在动"时**不**成立。</li>
 *   <li>**漏掉真正的瞬转**。瞬转的特征是"没有中间帧"，
 *       前后两拍必须都处于静止。</li>
 * </ol>
 */
class RotationSnapTest {

    @Test
    @DisplayName("静止 → 突跳 → 静止：成立（这是瞬转的指纹）")
    void detectsQuietSnapQuiet() {
        assertTrue(RotationSnap.isSnap(0.0, 97.1, 0.0));
        assertTrue(RotationSnap.isSnap(2.5, 145.0, 1.2));
        assertTrue(RotationSnap.isSnap(8.9, 41.0, 0.0),
                "8.9 度仍在'没动'的容差内，40 度已越过下界");
    }

    @Test
    @DisplayName("前一拍也在大幅转动：不成立（那是连续甩鼠标，不是瞬转）")
    void rejectsWhenPreviousTickMoving() {
        assertFalse(RotationSnap.isSnap(45.0, 90.0, 0.0),
                "前拍 45 度说明手腕在连续运动，属于正常 flick");
        assertFalse(RotationSnap.isSnap(9.0, 90.0, 0.0),
                "9 度已达到'没在转'的上界（>= QUIET_DEGREES）");
    }

    @Test
    @DisplayName("后一拍还在转：不成立（瞬转之后准星应当已经停住）")
    void rejectsWhenNextTickMoving() {
        assertFalse(RotationSnap.isSnap(0.0, 90.0, 30.0));
        assertFalse(RotationSnap.isSnap(0.0, 90.0, 9.0));
    }

    @Test
    @DisplayName("中间那一拍没到 40 度、且前后并非完全静止：不成立")
    void rejectsSmallMotionWithoutZeroFrames() {
        assertFalse(RotationSnap.isSnap(3.0, 39.0, 3.0),
                "39 度未达 40 度下界，且前后拍并非完全静止（不属于放宽档）");
        assertFalse(RotationSnap.isSnap(3.0, 30.0, 3.0));
    }

    @Test
    @DisplayName("前后两拍完全静止时，中间那一拍放宽到 25 度即成立")
    void relaxedThresholdRequiresTwoZeroFrames() {
        assertTrue(RotationSnap.isSnap(0.0, 26.0, 0.0),
                "前后拍差值为 0（采样期间完全未变化），按变体放宽到 25 度");
        assertFalse(RotationSnap.isSnap(0.0, 24.9, 0.0), "24.9 度未达放宽后的下界");
        assertFalse(RotationSnap.isSnap(0.5, 30.0, 0.0),
                "前拍有 0.5 度变化，不属于'完全静止'，回到 40 度门槛");
    }

    @Test
    @DisplayName("权重按角度分级，且必须单调不降")
    void weightsAreMonotonicAcrossBuckets() {
        assertEquals(50.0, RotationSnap.weight(179.0), "半圈以上");
        assertEquals(50.0, RotationSnap.weight(180.0));
        assertEquals(20.0, RotationSnap.weight(178.0), "恰好 178 未越过半圈档");
        assertEquals(20.0, RotationSnap.weight(90.1));
        assertEquals(10.0, RotationSnap.weight(90.0));
        assertEquals(10.0, RotationSnap.weight(50.1));
        assertEquals(7.0, RotationSnap.weight(50.0));
        assertEquals(7.0, RotationSnap.weight(40.1));

        double[] ascending = {40.5, 55.0, 95.0, 179.5};
        for (int i = 1; i < ascending.length; i++) {
            assertTrue(RotationSnap.weight(ascending[i]) >= RotationSnap.weight(ascending[i - 1]),
                    "角度更大时权重不得变小：" + ascending[i - 1] + " -> " + ascending[i]);
        }
    }
}
