package com.anticheat.core.check.impl.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReachTolerance} 的回归测试——阈值按延迟分段的形状。
 *
 * <p>这个曲线是"低延迟玩家不被误报、也不被白送作弊空间"的唯一保障，
 * 形状一旦被改坏（例如某处写成常数、或端点接不上），
 * 表现是"某一段延迟的玩家阈值突然变宽/变窄"——线上只会表现为误报或漏判，
 * 不会以任何形式报错。</p>
 */
class ReachToleranceTest {

    private static final double BASE = ReachTolerance.BASE;

    private static final double SLACK = ReachTolerance.MAX_PING_SLACK;

    @Test
    @DisplayName("低延迟档：到 PING_FREE_MS 为止不额外放宽")
    void lowPingGetsBaseOnly() {
        assertEquals(BASE, ReachTolerance.effective(0), 1e-9);
        assertEquals(BASE, ReachTolerance.effective(50), 1e-9);
        assertEquals(BASE, ReachTolerance.effective(200), 1e-9);
    }

    @Test
    @DisplayName("高延迟档：从 PING_FULL_MS 起吃满 slack，且与旧版固定容差持平")
    void highPingGetsFullSlack() {
        assertEquals(BASE + SLACK, ReachTolerance.effective(400), 1e-9);
        assertEquals(BASE + SLACK, ReachTolerance.effective(900), 1e-9,
                "超过判定上限的延迟不该继续放宽（那种玩家已经不判定了）");
        assertEquals(0.85, ReachTolerance.effective(400), 0.001,
                "400ms 的有效容差要与历史默认值一致，避免上线后高延迟玩家反而更容易被误报");
    }

    @Test
    @DisplayName("中间段线性过渡且整体单调不减")
    void rampIsMonotonic() {
        double previous = -1.0;
        for (int ping = 0; ping <= 500; ping += 10) {
            double current = ReachTolerance.effective(ping);
            assertTrue(current >= previous, "ping=" + ping + " 处容差回落了："
                    + previous + " -> " + current);
            previous = current;
        }
        assertEquals(BASE + SLACK * 0.5, ReachTolerance.effective(300), 1e-9,
                "300ms 应正好落在 200~400 的中点");
    }

    @Test
    @DisplayName("ping-slack = 0 时退化为固定容差（配置可关掉动态行为）")
    void zeroSlackMeansFixedTolerance() {
        for (int ping : new int[]{0, 100, 250, 400}) {
            assertEquals(BASE, ReachTolerance.effective(ping, BASE, 0.0), 1e-9);
        }
    }

    @Test
    @DisplayName("异常输入不放大阈值：负延迟按 0 处理，负容差被夹到 0")
    void abnormalInputsAreClamped() {
        assertEquals(BASE, ReachTolerance.effective(-1), 1e-9);
        assertEquals(0.0, ReachTolerance.effective(100, -5.0, 0.0), 1e-9);
        assertTrue(ReachTolerance.effective(400, BASE, -1.0) >= 0.0);
    }

    @Test
    @DisplayName("样本年龄上限随延迟增长，且 0 延迟也保留 1 tick 抖动余量")
    void sampleAgeGrowsWithPing() {
        assertEquals(1, ReachTolerance.maxSampleAgeTicks(0),
                "0 延迟也必须留 1 tick：位置包可能丢包/乱序，最近一次采样未必就是出手那一刻");
        assertEquals(2, ReachTolerance.maxSampleAgeTicks(100));
        assertEquals(3, ReachTolerance.maxSampleAgeTicks(200));
        assertEquals(5, ReachTolerance.maxSampleAgeTicks(400));

        int previous = -1;
        for (int ping = 0; ping <= 500; ping += 25) {
            int age = ReachTolerance.maxSampleAgeTicks(ping);
            assertTrue(age >= previous, "样本年龄上限必须单调不减");
            previous = age;
        }
    }

    @Test
    @DisplayName("describe 输出两位小数，供告警文案直接使用")
    void describeIsFormatted() {
        assertEquals("0.40", ReachTolerance.describe(0, BASE, SLACK));
        assertEquals("0.85", ReachTolerance.describe(400, BASE, SLACK));
    }
}
