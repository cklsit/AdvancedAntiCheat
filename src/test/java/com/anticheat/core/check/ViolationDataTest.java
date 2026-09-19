package com.anticheat.core.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 违规分账本单测。
 *
 * <p>这是核心层里唯一真正可离线验证的逻辑——{@code Check} 依赖 Bukkit {@code Player}，
 * 单测里造不出来；但「flag 加分 / reward 衰减 / 阈值判定」这套语义一旦写错，
 * 表现是**线上长期误封或永不触发**，两种都很难事后定位。所以在这里钉死。</p>
 */
class ViolationDataTest {

    @Test
    @DisplayName("flag 每次 +1，并刷新最后违规时间")
    void flagIncrementsAndStampsTime() {
        ViolationData data = new ViolationData(0.05, 0.0);
        assertEquals(0.0, data.getViolations(), 1e-9, "初始违规分应为 0");

        long before = System.currentTimeMillis();
        assertEquals(1.0, data.flag(), 1e-9);
        assertEquals(2.0, data.flag(), 1e-9);
        assertEquals(3.0, data.flag(), 1e-9);
        assertTrue(data.getLastViolationTime() >= before,
                "flag 后应刷新 lastViolationTime（否则「多久没再违规」类判据永远为真）");
    }

    @Test
    @DisplayName("reward 按 decay 扣分，且不会扣到负数")
    void rewardDecaysAndFloorsAtZero() {
        ViolationData data = new ViolationData(0.25, 0.0);
        for (int i = 0; i < 4; i++) {
            data.flag();
        }
        assertEquals(4.0, data.getViolations(), 1e-9);

        data.reward();
        assertEquals(3.75, data.getViolations(), 1e-9, "reward 应按 decay=0.25 扣分");

        // 连续 reward 必须能回落到 0，且停在那里——负分会让阈值判定彻底失真
        for (int i = 0; i < 50; i++) {
            data.reward();
        }
        assertEquals(0.0, data.getViolations(), 1e-9, "违规分下限必须是 0，不能变负");
    }

    @Test
    @DisplayName("setback 阈值 <=0 表示不参与拉回，>0 时才在超过阈值后触发")
    void shouldSetbackRespectsThreshold() {
        ViolationData passive = new ViolationData(0.05, 0.0);
        for (int i = 0; i < 100; i++) {
            passive.flag();
        }
        assertFalse(passive.shouldSetback(),
                "setbackVl=0 表示本检测不参与拉回，分数再高也不应触发");

        ViolationData active = new ViolationData(0.05, 5.0);
        for (int i = 0; i < 5; i++) {
            active.flag();
        }
        assertFalse(active.shouldSetback(), "正好等于阈值不应触发（判定为 strictly greater）");
        active.flag();
        assertTrue(active.shouldSetback(), "超过 setbackVl 必须触发拉回");
    }

    @Test
    @DisplayName("reset 清零违规分，但不影响 decay / setback 配置")
    void resetZeroesViolationsOnly() {
        ViolationData data = new ViolationData(0.1, 3.0);
        for (int i = 0; i < 10; i++) {
            data.flag();
        }
        data.reset();

        assertEquals(0.0, data.getViolations(), 1e-9);
        assertEquals(3.0, data.getSetbackVl(), 1e-9, "reset 不应改动 setback 阈值");
        assertEquals(0.1, data.getDecay(), 1e-9, "reset 不应改动 decay");
        assertFalse(data.shouldSetback(), "清零后不应再触发拉回");
    }

    @Test
    @DisplayName("decay 过大时单次 reward 可跨过多个违规分（不得出现中间态残留）")
    void largeDecayClampsCleanly() {
        ViolationData data = new ViolationData(10.0, 5.0);
        data.flag();
        data.reward();
        assertEquals(0.0, data.getViolations(), 1e-9, "decay 大于当前分时应直接归零而非变负");
    }
}
