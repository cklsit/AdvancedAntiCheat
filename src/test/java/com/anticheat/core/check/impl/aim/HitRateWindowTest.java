package com.anticheat.core.check.impl.aim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HitRateWindow} 的回归测试。
 *
 * <p>两处容易写错、且错了就静默失准的地方：</p>
 * - **淘汰时要把被淘汰那一次的命中与位移一起扣掉**（漏扣会让命中率只涨不跌，
 *   长时间在线后人人都是"百发百中"）；
 * - **换目标那一步的位移必须不计**（否则两个实体之间的空间距离会被当成
 *   "目标移动了多远"，位移虚高 → 门槛虚过 → 误报）。
 */
class HitRateWindowTest {

    @Test
    @DisplayName("空窗口的命中率是 0 而不是 1（没有证据 ≠ 百发百中）")
    void emptyWindowRateIsZero() {
        HitRateWindow window = new HitRateWindow(8);
        assertEquals(0, window.attacks());
        assertEquals(0.0, window.hitRate(), 1e-9);
        assertEquals(0.0, window.travelled(), 1e-9);
        assertFalse(window.isFull());
    }

    @Test
    @DisplayName("命中率按窗口内样本统计")
    void rateReflectsWindow() {
        HitRateWindow window = new HitRateWindow(8);
        window.onAttack(true, 1, 0, 0, 0);
        window.onAttack(true, 1, 1, 0, 0);
        window.onAttack(false, 1, 2, 0, 0);
        assertEquals(3, window.attacks());
        assertEquals(2, window.hits());
        assertEquals(2.0 / 3.0, window.hitRate(), 1e-9);
    }

    @Test
    @DisplayName("环绕后淘汰最旧样本：命中计数与命中率同步下降")
    void evictionUpdatesCounts() {
        HitRateWindow window = new HitRateWindow(3);
        window.onAttack(true, 1, 0, 0, 0);
        window.onAttack(true, 1, 0, 0, 0);
        window.onAttack(false, 1, 0, 0, 0);
        assertEquals(2.0 / 3.0, window.hitRate(), 1e-9);

        // 再进一次命中：最旧的那次命中被淘汰，命中数应保持不变
        window.onAttack(true, 1, 0, 0, 0);
        assertEquals(3, window.attacks());
        assertEquals(2, window.hits(), "淘汰掉的命中必须从计数里扣掉，否则命中率只涨不跌");
        assertEquals(2.0 / 3.0, window.hitRate(), 1e-9);

        // 再进一次未命中：淘汰掉一次命中，命中数下降
        window.onAttack(false, 1, 0, 0, 0);
        assertEquals(1, window.hits());
        assertEquals(1.0 / 3.0, window.hitRate(), 1e-9);
    }

    @Test
    @DisplayName("位移按相邻两次攻击的目标移动量累加")
    void travelAccumulates() {
        HitRateWindow window = new HitRateWindow(8);
        window.onAttack(true, 7, 0.0, 0.0, 0.0);
        window.onAttack(true, 7, 3.0, 0.0, 0.0);
        window.onAttack(true, 7, 6.0, 0.0, 0.0);
        assertEquals(6.0, window.travelled(), 1e-9);
        assertEquals(2.0, window.travelPerAttack(), 1e-9);
    }

    @Test
    @DisplayName("换目标的那一步不计位移（两个实体的空间距离不是目标的位移）")
    void targetSwitchDoesNotCountTravel() {
        HitRateWindow window = new HitRateWindow(8);
        window.onAttack(true, 1, 0.0, 0.0, 0.0);
        window.onAttack(true, 2, 100.0, 0.0, 0.0);
        assertEquals(0.0, window.travelled(), 1e-9,
                "跨目标相减会把 100 格的空间距离算成位移，直接让位移门槛虚过");

        // 回到同一目标后继续累加
        window.onAttack(true, 2, 103.0, 0.0, 0.0);
        assertEquals(3.0, window.travelled(), 1e-9);
    }

    @Test
    @DisplayName("淘汰时同步扣减被淘汰那一步的位移")
    void evictionAlsoReducesTravel() {
        HitRateWindow window = new HitRateWindow(2);
        window.onAttack(true, 1, 0.0, 0.0, 0.0);
        window.onAttack(true, 1, 5.0, 0.0, 0.0);
        assertEquals(5.0, window.travelled(), 1e-9);

        // 第三次：容量 2 已满，淘汰最旧那一步（步长 0），再计入 5->7 的 2 格
        window.onAttack(true, 1, 7.0, 0.0, 0.0);
        assertEquals(7.0, window.travelled(), 1e-9,
                "应等于 5->7 的 2 格加上第二次攻击自身的 5 格步长；漏扣则会是 5+5+2=12");
    }

    @Test
    @DisplayName("reset 清空一切状态（换世界/传送/让路后必须重置）")
    void resetClearsState() {
        HitRateWindow window = new HitRateWindow(8);
        window.onAttack(true, 1, 0, 0, 0);
        window.onAttack(true, 1, 10, 0, 0);
        window.reset();

        assertEquals(0, window.attacks());
        assertEquals(0, window.hits());
        assertEquals(0.0, window.travelled(), 1e-9);
        assertEquals(0.0, window.hitRate(), 1e-9);

        // 重置后第一步不应把"重置前的位置"算成位移
        window.onAttack(true, 1, 500.0, 0.0, 0.0);
        assertEquals(0.0, window.travelled(), 1e-9);
    }

    @Test
    @DisplayName("容量必须为正")
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new HitRateWindow(0));
    }

    @Test
    @DisplayName("全命中窗口的命中率恰好是 1.0（判定门槛靠它）")
    void allHitsIsExactlyOne() {
        HitRateWindow window = new HitRateWindow(60);
        for (int i = 0; i < 60; i++) {
            window.onAttack(true, 1, i, 0, 0);
        }
        assertTrue(window.isFull());
        assertEquals(1.0, window.hitRate(), 1e-12);
        assertEquals(59.0, window.travelled(), 1e-9);
    }
}
