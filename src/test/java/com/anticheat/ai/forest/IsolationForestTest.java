package com.anticheat.ai.forest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局孤立森林回归测试。
 *
 * <p>业务语义：无监督识别"没见过的作弊行为"。分数 ∈ [0,1]，越高越异常；
 * 未训练时返回 -1，调用方据此跳过全局分（不能把 -1 当 0 用）。
 */
class IsolationForestTest {

    private static final int DIMS = 48;

    /** 生成一簇围绕 center 的正常样本。 */
    private static List<double[]> cloud(double center, int n, long seed) {
        Random rnd = new Random(seed);
        List<double[]> data = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double[] v = new double[DIMS];
            for (int d = 0; d < DIMS; d++) {
                v[d] = center + rnd.nextGaussian() * 0.5;
            }
            data.add(v);
        }
        return data;
    }

    @Test
    @DisplayName("未训练时 score 返回 -1（不是 0）")
    void notReadyReturnsMinusOne() {
        IsolationForest forest = new IsolationForest(20, 64, 6, 1L);
        assertFalse(forest.isReady());
        assertEquals(-1.0, forest.score(new double[DIMS]), 1e-12);
    }

    @Test
    @DisplayName("样本不足 8 条时不训练（保持未就绪）")
    void tooFewSamplesKeepsUnready() {
        IsolationForest forest = new IsolationForest(20, 64, 6, 1L);
        forest.fit(cloud(0.0, 7, 2L));
        assertFalse(forest.isReady(), "少于 8 条样本必须保持未就绪，避免用垃圾样本建树");
    }

    @Test
    @DisplayName("训练后就绪，且分数落在 [0,1]")
    void scoresInUnitRange() {
        IsolationForest forest = new IsolationForest(50, 128, 8, 3L);
        forest.fit(cloud(0.0, 300, 4L));
        assertTrue(forest.isReady());
        Random rnd = new Random(5L);
        for (int i = 0; i < 50; i++) {
            double[] v = new double[DIMS];
            for (int d = 0; d < DIMS; d++) {
                v[d] = rnd.nextGaussian();
            }
            double s = forest.score(v);
            assertTrue(s >= 0.0 && s <= 1.0, "异常分必须在 [0,1]，实际 " + s);
        }
    }

    @Test
    @DisplayName("远离正常簇的样本分数必须高于簇内样本（无监督作弊识别核心语义）")
    void outlierScoresHigherThanInlier() {
        IsolationForest forest = new IsolationForest(100, 256, 8, 20260915L);
        forest.fit(cloud(0.0, 400, 11L));

        Random rnd = new Random(12L);
        double inlierSum = 0.0;
        for (int i = 0; i < 30; i++) {
            double[] v = new double[DIMS];
            for (int d = 0; d < DIMS; d++) {
                v[d] = rnd.nextGaussian() * 0.5;
            }
            inlierSum += forest.score(v);
        }
        double inlierAvg = inlierSum / 30;

        double[] outlier = new double[DIMS];
        for (int d = 0; d < DIMS; d++) {
            outlier[d] = 8.0; // 全部 48 维都远离正常簇 → 必然被孤立
        }
        double outlierScore = forest.score(outlier);

        assertTrue(outlierScore > inlierAvg,
                "离群样本分数应更高（inlierAvg=" + inlierAvg + ", outlier=" + outlierScore + "）");
        assertTrue(outlierScore > 0.6,
                "48 维全部偏离的样本应给出高异常分，实际 " + outlierScore);
    }

    @Test
    @DisplayName("同 seed 训练 → 同分数（可复现，便于 CI 断言与回滚对比）")
    void deterministicWithSameSeed() {
        IsolationForest a = new IsolationForest(40, 128, 8, 99L);
        IsolationForest b = new IsolationForest(40, 128, 8, 99L);
        List<double[]> data = cloud(0.0, 200, 13L);
        a.fit(data);
        b.fit(data);
        Random rnd = new Random(14L);
        for (int i = 0; i < 20; i++) {
            double[] v = new double[DIMS];
            for (int d = 0; d < DIMS; d++) {
                v[d] = rnd.nextGaussian();
            }
            assertEquals(a.score(v), b.score(v), 1e-12, "同 seed 必须完全可复现");
        }
    }

    @Test
    @DisplayName("重新 fit 会替换旧森林（模型热更新不残留旧分布）")
    void refitReplacesModel() {
        IsolationForest forest = new IsolationForest(60, 128, 8, 21L);

        forest.fit(cloud(0.0, 300, 22L));
        double zeroNormal = forest.score(constant(0.0));
        double hundredOdd = forest.score(constant(100.0));
        assertTrue(hundredOdd > zeroNormal,
                "以 0 为正常簇时，100 应为异常（" + hundredOdd + " vs " + zeroNormal + "）");

        // 全局分布整体迁移（例如服务器换了玩法/换了一群玩家）
        forest.fit(cloud(100.0, 300, 23L));
        double zeroAfter = forest.score(constant(0.0));
        double hundredAfter = forest.score(constant(100.0));
        assertTrue(zeroAfter > hundredAfter,
                "重构后正常簇已迁移到 100，原正常点 0 必须变为异常（"
                        + zeroAfter + " vs " + hundredAfter + "）");
    }

    private static double[] constant(double v) {
        double[] x = new double[DIMS];
        for (int d = 0; d < DIMS; d++) {
            x[d] = v;
        }
        return x;
    }

    private static double[] farthest() {
        double[] v = new double[DIMS];
        for (int d = 0; d < DIMS; d++) {
            v[d] = 50.0;
        }
        return v;
    }

    @Test
    @DisplayName("cFactor：n<=1 归一化为 1，随 n 单调递增")
    void cFactorMonotone() {
        assertEquals(1.0, IsolationForest.cFactor(0), 1e-12);
        assertEquals(1.0, IsolationForest.cFactor(1), 1e-12);
        double prev = IsolationForest.cFactor(2);
        for (int n = 3; n <= 500; n += 7) {
            double c = IsolationForest.cFactor(n);
            assertTrue(c > prev, "cFactor 必须随 n 单调递增，n=" + n);
            prev = c;
        }
    }

    @Test
    @DisplayName("构造参数越界被夹到安全下限（不抛异常、不产生 0 棵树）")
    void constructorClampsArguments() {
        IsolationForest forest = new IsolationForest(0, 1, 0, 1L);
        forest.fit(cloud(0.0, 50, 31L));
        assertTrue(forest.isReady(), "非法参数应被夹到下限而不是产生空森林");
        double s = forest.score(farthest());
        assertTrue(s >= 0.0 && s <= 1.0);
    }
}
