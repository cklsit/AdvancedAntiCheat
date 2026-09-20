package com.anticheat.core.check.impl.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReachSampler} 的回归测试。
 *
 * <p>它是"把阈值定成多少"这件事在真实服务器上的眼睛：分位数算错不会让插件崩溃，
 * 只会让管理员**照着错的数去调阈值**——这是最难排查的一类问题（结论看起来有理有据），
 * 所以分位数的口径必须被钉死。</p>
 */
class ReachSamplerTest {

    @Test
    @DisplayName("空样本：返回 NaN 而不是 0，避免把'没数据'当成'距离为 0'")
    void emptySampler() {
        ReachSampler sampler = new ReachSampler(64);
        assertEquals(0, sampler.size());
        assertTrue(sampler.isEmpty());
        assertTrue(Double.isNaN(sampler.percentile(0.5)));
        assertTrue(Double.isNaN(sampler.max()));
        assertTrue(sampler.summary(3.4, 3.0).contains("无有效样本"));
    }

    @Test
    @DisplayName("最近秩法分位数：n=100 时 p50=50、p95=95、p99=99")
    void percentilesUseNearestRank() {
        ReachSampler sampler = new ReachSampler(128);
        for (int i = 1; i <= 100; i++) {
            sampler.add(i);
        }
        assertEquals(100, sampler.size());
        assertEquals(50.0, sampler.percentile(0.50), 1e-9);
        assertEquals(95.0, sampler.percentile(0.95), 1e-9);
        assertEquals(99.0, sampler.percentile(0.99), 1e-9);
        assertEquals(100.0, sampler.max(), 1e-9);
        assertEquals(100.0, sampler.percentile(1.0), 1e-9);
    }

    @Test
    @DisplayName("环绕后只保留最近的样本，累计计数不丢")
    void ringWrapsAndKeepsTotal() {
        ReachSampler sampler = new ReachSampler(4);
        for (int i = 1; i <= 6; i++) {
            sampler.add(i);
        }
        assertEquals(4, sampler.size(), "环形缓冲只能保留容量内的样本");
        assertEquals(6, sampler.getTotalCount());
        assertEquals(6.0, sampler.max(), 1e-9);
        // 留存 {3,4,5,6}；最近秩法 p50 -> ceil(0.5*4)=2 -> 排序后第 2 个 = 4
        assertEquals(4.0, sampler.percentile(0.5), 1e-9,
                "留存的样本应是 3,4,5,6，中位取排序后第 2 个");
    }

    @Test
    @DisplayName("NaN 与无穷哨兵不进样本（否则分位数会被污染成 NaN）")
    void rubbishSamplesAreIgnored() {
        ReachSampler sampler = new ReachSampler(8);
        sampler.add(Double.NaN);
        sampler.add(Double.MAX_VALUE);
        sampler.add(3.0);
        assertEquals(1, sampler.size());
        assertEquals(3.0, sampler.max(), 1e-9);
        assertEquals(3.0, sampler.percentile(0.99), 1e-9);
    }

    @Test
    @DisplayName("countAbove 与 summary 的文案参与调参，必须与样本一致")
    void countAboveMatchesSamples() {
        ReachSampler sampler = new ReachSampler(16);
        for (int i = 0; i < 10; i++) {
            sampler.add(3.0);
        }
        for (int i = 0; i < 2; i++) {
            sampler.add(4.0);
        }
        assertEquals(2, sampler.countAbove(3.4));
        assertEquals(0, sampler.countAbove(10.0));

        String summary = sampler.summary(3.4, 3.0);
        assertTrue(summary.contains("n=12"), "汇总必须报出样本量：" + summary);
        assertTrue(summary.contains("超阈值=2"), "汇总必须报出超阈值个数：" + summary);
        assertTrue(summary.contains("4.00"), "汇总必须报出最大值：" + summary);
    }

    @Test
    @DisplayName("reset 清空缓冲与累计计数（标定按周期输出，周期之间必须互不影响）")
    void resetClearsEverything() {
        ReachSampler sampler = new ReachSampler(4);
        sampler.add(3.5);
        sampler.reset();
        assertEquals(0, sampler.size());
        assertEquals(0, sampler.getTotalCount());
        assertFalse(sampler.summary(3.4, 3.0).contains("n="));
    }

    @Test
    @DisplayName("容量必须为正（0 容量会让所有 add 静默丢弃）")
    void capacityMustBePositive() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReachSampler(0));
    }
}
