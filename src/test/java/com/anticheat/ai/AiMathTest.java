package com.anticheat.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 实验室数值工具库回归测试。
 *
 * <p>这些函数是所有 AI 分值的底座（sigmoid 决定融合分、euclidean 决定基线与森林、
 * cosine 决定集群归并），一旦静默改坏，上层所有检测都会"看起来正常但判定全错"，
 * 所以这里对边界与单调性都做硬断言。
 */
class AiMathTest {

    @Test
    @DisplayName("clamp 边界与越界")
    void clamp() {
        assertEquals(0.0, AiMath.clamp(-1.0, 0.0, 1.0), 1e-12);
        assertEquals(1.0, AiMath.clamp(2.0, 0.0, 1.0), 1e-12);
        assertEquals(0.4, AiMath.clamp(0.4, 0.0, 1.0), 1e-12);
        assertEquals(0.0, AiMath.clamp(0.0, 0.0, 1.0), 1e-12);
        assertEquals(1.0, AiMath.clamp(1.0, 0.0, 1.0), 1e-12);
    }

    @Test
    @DisplayName("sigmoid 中心值、值域、单调性")
    void sigmoid() {
        assertEquals(0.5, AiMath.sigmoid(0.0), 1e-12);
        // 极值必须仍然落在开区间内（x 大到一定程度 double 会真的取到 1.0，所以用 20 做边界探针）
        assertTrue(AiMath.sigmoid(-20.0) > 0.0, "负向极值必须严格大于 0");
        assertTrue(AiMath.sigmoid(20.0) < 1.0, "正向极值必须严格小于 1");
        assertTrue(AiMath.sigmoid(-20.0) < 1e-6, "sigmoid(-20) 应极小");
        assertTrue(AiMath.sigmoid(20.0) > 1 - 1e-6, "sigmoid(20) 应极接近 1");
        double prev = -1.0;
        for (double x = -6.0; x <= 6.0; x += 0.25) {
            double v = AiMath.sigmoid(x);
            assertTrue(v > prev, "sigmoid 必须严格单调递增，x=" + x);
            assertTrue(v >= 0.0 && v <= 1.0, "sigmoid 必须落在 [0,1]，x=" + x);
            prev = v;
        }
    }

    @Test
    @DisplayName("tanh 奇对称且值域 (-1,1)")
    void tanh() {
        assertEquals(0.0, AiMath.tanh(0.0), 1e-12);
        assertEquals(-AiMath.tanh(1.234), AiMath.tanh(-1.234), 1e-12);
        assertTrue(Math.abs(AiMath.tanh(15.0)) < 1.0, "tanh 必须严格小于 1");
        assertTrue(AiMath.tanh(15.0) > 0.999999, "tanh(15) 应接近 1");
    }

    @Test
    @DisplayName("entropy：均匀分布最大，单点分布为 0")
    void entropy() {
        assertEquals(0.0, AiMath.entropy(new double[]{1.0}), 1e-12);
        assertEquals(0.0, AiMath.entropy(new double[]{1.0, 0.0, 0.0}), 1e-12);
        double uniform = AiMath.entropy(new double[]{0.25, 0.25, 0.25, 0.25});
        assertEquals(2.0, uniform, 1e-9); // log2(4) = 2
        double skewed = AiMath.entropy(new double[]{0.7, 0.1, 0.1, 0.1});
        assertTrue(skewed < uniform, "越集中熵越低");
    }

    @Test
    @DisplayName("normalizedEntropy 落在 [0,1]")
    void normalizedEntropy() {
        double[] constant = new double[64];
        double[] ramp = new double[64];
        for (int i = 0; i < 64; i++) {
            ramp[i] = i;
        }
        double flat = AiMath.normalizedEntropy(constant, 8, 0.0, 63.0);
        double spread = AiMath.normalizedEntropy(ramp, 8, 0.0, 63.0);
        assertTrue(flat >= 0.0 && flat <= 1.0, "flat=" + flat);
        assertTrue(spread >= 0.0 && spread <= 1.0, "spread=" + spread);
        assertTrue(spread > flat, "均匀铺满所有 bin 的分布熵更高");
    }

    @Test
    @DisplayName("mean / variance / standardDeviation / kurtosis")
    void descriptives() {
        double[] a = {2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0};
        assertEquals(5.0, AiMath.mean(a), 1e-12);
        // 样本方差（n-1），与 OnlineStats 的 Welford 实现保持一致
        assertEquals(32.0 / 7.0, AiMath.variance(a), 1e-9);
        assertEquals(Math.sqrt(32.0 / 7.0), AiMath.standardDeviation(a), 1e-9);
        // 单元素 / 空数组不应抛异常
        assertEquals(0.0, AiMath.variance(new double[]{3.0}), 1e-12);
        assertEquals(0.0, AiMath.mean(new double[0]), 1e-12);
        // 峰度是标准化四阶矩 m4/m2²（非超额峰度），并被 clamp 到 [0,50]
        assertEquals(0.0, AiMath.kurtosis(new double[]{0.0, 0.0, 0.0, 0.0}), 1e-9);
        double flat = AiMath.kurtosis(new double[]{1, 2, 3, 4, 5, 6, 7, 8});
        double spiky = AiMath.kurtosis(new double[]{0, 0, 0, 0, 0, 0, 0, 10});
        assertTrue(spiky > flat, "突发/急动数据的峰度必须高于平稳数据");
        assertTrue(AiMath.kurtosis(new double[]{0, 0, 0, 0, 0, 0, 0, 10}) <= 50.0, "峰度必须封顶");
        assertEquals(0.0, AiMath.kurtosis(new double[]{1, 2}), 1e-9);
    }

    @Test
    @DisplayName("euclidean 距离")
    void euclidean() {
        assertEquals(0.0, AiMath.euclidean(new double[]{1, 2, 3}, new double[]{1, 2, 3}), 1e-12);
        assertEquals(5.0, AiMath.euclidean(new double[]{0, 0}, new double[]{3, 4}), 1e-12);
        assertEquals(0.0, AiMath.euclidean(new double[0], new double[0]), 1e-12);
        // 长度不一致时按较短长度对齐（不抛异常）——显式固化这个行为，
        // 避免以后有人"顺手"改成抛异常而打断 1Hz 特征链路
        assertEquals(0.0, AiMath.euclidean(new double[]{1, 2}, new double[]{1}), 1e-12);
    }

    @Test
    @DisplayName("cosine 相似度：同向=1、正交=0、反向=-1")
    void cosine() {
        assertEquals(1.0, AiMath.cosine(new double[]{1, 2, 3}, new double[]{2, 4, 6}), 1e-9);
        assertEquals(0.0, AiMath.cosine(new double[]{1, 0}, new double[]{0, 1}), 1e-9);
        assertEquals(-1.0, AiMath.cosine(new double[]{1, 0}, new double[]{-1, 0}), 1e-9);
        // 零向量不能产生 NaN（集群归并会直接用它做阈值比较）
        double zero = AiMath.cosine(new double[]{0, 0}, new double[]{1, 1});
        assertTrue(!Double.isNaN(zero), "零向量余弦不能是 NaN");
    }

    @ParameterizedTest(name = "cosine({0},{1}) = {2}")
    @CsvSource({
            "1, 1, 1.0",
            "1, 0.5, 1.0",
            "2, 5, 1.0",
    })
    @DisplayName("cosine 与模长无关")
    void cosineScaleInvariant(double a, double b, double expected) {
        assertEquals(expected, AiMath.cosine(new double[]{a, a}, new double[]{b, b}), 1e-9);
    }

    @Test
    @DisplayName("OnlineStats：增量均值/标准差与批量一致")
    void onlineStatsMatchesBatch() {
        double[] data = {1.0, 2.0, 3.0, 4.0, 5.0, 12.0, 0.5, 3.5};
        AiMath.OnlineStats stats = new AiMath.OnlineStats();
        for (double d : data) {
            stats.add(d);
        }
        assertEquals(data.length, stats.getCount());
        assertEquals(AiMath.mean(data), stats.getMean(), 1e-9);
        assertEquals(AiMath.standardDeviation(data), stats.getStd(), 1e-9);
        assertEquals(0.0, new AiMath.OnlineStats().getStd(), 1e-12);
        assertEquals(0.0, new AiMath.OnlineStats().getMean(), 1e-12);
    }

    @Test
    @DisplayName("OnlineStats.merge：合并后统计量等价于整体喂入")
    void onlineStatsMerge() {
        double[] partA = {1.0, 2.0, 3.0};
        double[] partB = {10.0, 20.0};

        AiMath.OnlineStats merged = new AiMath.OnlineStats();
        for (double d : partA) {
            merged.add(d);
        }
        AiMath.OnlineStats other = new AiMath.OnlineStats();
        for (double d : partB) {
            other.add(d);
        }
        merged.merge(other.getMean(), other.getStd(), other.getCount());

        double[] all = {1.0, 2.0, 3.0, 10.0, 20.0};
        assertEquals(AiMath.mean(all), merged.getMean(), 1e-9);
        assertEquals(AiMath.standardDeviation(all), merged.getStd(), 1e-9);
        assertEquals(all.length, merged.getCount());
    }
}
