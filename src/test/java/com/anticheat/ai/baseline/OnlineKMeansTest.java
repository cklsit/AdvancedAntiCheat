package com.anticheat.ai.baseline;

import com.anticheat.ai.FeatureDimensions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人行为基线（Online K-Means）回归测试。
 *
 * <p>业务语义：warmup 期间不产出个人分（避免新人被误伤）；
 * warmup 完成后，与本人历史习惯一致的样本分数低，明显偏离的样本分数高。
 */
class OnlineKMeansTest {

    private static final int DIMS = FeatureDimensions.DIMS;

    private static double[] vector(double base, double noise, Random rnd) {
        double[] v = new double[DIMS];
        for (int i = 0; i < DIMS; i++) {
            v[i] = base + (rnd.nextDouble() - 0.5) * noise;
        }
        return v;
    }

    @Test
    @DisplayName("warmup 未完成时不产出个人分，且 isWarmedUp=false")
    void noScoreDuringWarmup() {
        OnlineKMeans km = new OnlineKMeans(DIMS);
        km.setWarmupTarget(50);
        assertFalse(km.isWarmedUp());
        for (int i = 0; i < 49; i++) {
            assertEquals(-1.0, km.learn(new double[DIMS]), 1e-12,
                    "warmup 未完成必须返回 -1（调用方据此跳过个人分）");
        }
        assertFalse(km.isWarmedUp());
        // 第 50 个样本完成 warmup，之后才开始产分
        assertEquals(-1.0, km.learn(new double[DIMS]), 1e-12);
        assertTrue(km.isWarmedUp(), "达到 warmup 目标后必须置为已就绪");
        assertEquals(50, km.getWarmupProgress());
        assertEquals(50, km.getWarmupTarget());
    }

    @Test
    @DisplayName("维度不匹配的样本被拒绝而不是污染模型")
    void rejectsWrongDimension() {
        OnlineKMeans km = new OnlineKMeans(DIMS);
        km.setWarmupTarget(1);
        assertEquals(-1.0, km.learn(null), 1e-12);
        assertEquals(-1.0, km.learn(new double[DIMS - 1]), 1e-12);
        assertEquals(-1.0, km.learn(new double[DIMS + 5]), 1e-12);
    }

    @Test
    @DisplayName("warmup 完成后：习惯内样本分数低，异常样本分数高")
    void habitualLowAnomalousHigh() {
        OnlineKMeans km = new OnlineKMeans(DIMS);
        km.setWarmupTarget(60);
        Random rnd = new Random(20260915L);

        // 建立个人基线：围绕 base=10 的稳定习惯
        for (int i = 0; i < 400; i++) {
            km.learn(vector(10.0, 0.2, rnd));
        }
        assertTrue(km.isWarmedUp());

        double habitual = 0.0;
        for (int i = 0; i < 30; i++) {
            habitual += km.score(vector(10.0, 0.2, rnd));
        }
        habitual /= 30;

        double anomalous = km.score(vector(60.0, 0.2, rnd));

        assertTrue(habitual >= 0.0 && habitual <= 1.0, "个人分必须在 [0,1]：" + habitual);
        assertTrue(anomalous >= 0.0 && anomalous <= 1.0, "个人分必须在 [0,1]：" + anomalous);
        assertTrue(anomalous > habitual,
                "明显偏离个人习惯的样本分数必须更高（habitual=" + habitual
                        + ", anomalous=" + anomalous + "）");
    }

    @Test
    @DisplayName("warmup 前 score() 返回 -1（模拟器必须识别为未就绪）")
    void scoreBeforeWarmupIsNegativeOne() {
        OnlineKMeans km = new OnlineKMeans(DIMS);
        km.setWarmupTarget(10);
        assertEquals(-1.0, km.score(new double[DIMS]), 1e-12);
    }

    @Test
    @DisplayName("restore：持久化恢复后中心与更新计数完整还原（重启不改变玩家基线）")
    void restoreRoundTrip() {
        OnlineKMeans origin = new OnlineKMeans(DIMS);
        origin.setWarmupTarget(20);
        Random rnd = new Random(7L);
        for (int i = 0; i < 200; i++) {
            origin.learn(vector(5.0, 1.0, rnd));
        }
        assertTrue(origin.isWarmedUp());

        OnlineKMeans restored = new OnlineKMeans(DIMS);
        restored.setWarmupTarget(20);
        restored.restore(origin.getCenters(), origin.getCenterCounts(),
                origin.getWarmupTarget(), origin.getUpdates(),
                1.0, 0.1, 100L);

        assertTrue(restored.isWarmedUp(), "恢复后必须直接处于就绪状态");
        assertEquals(origin.getUpdates(), restored.getUpdates(), "更新计数必须还原");
        double[][] a = origin.getCenters();
        double[][] b = restored.getCenters();
        for (int k = 0; k < OnlineKMeans.K; k++) {
            for (int d = 0; d < DIMS; d++) {
                assertEquals(a[k][d], b[k][d], 1e-12,
                        "中心必须逐维还原 k=" + k + " d=" + d);
            }
        }
        double s = restored.score(vector(5.0, 1.0, rnd));
        assertTrue(s >= 0.0 && s <= 1.0, "恢复后必须能正常产分，实际 " + s);
    }

    @Test
    @DisplayName("restore 对非法输入（null / 维度不符 / 负数计数）必须容错不崩")
    void restoreToleratesBadInput() {
        OnlineKMeans km = new OnlineKMeans(DIMS);
        km.setWarmupTarget(20);
        km.restore(null, null, -5, -5, 0.0, 0.0, 0L);
        assertEquals(0, km.getUpdates());
        assertFalse(km.isWarmedUp());
        // 维度不符的中心必须被忽略，不能 ArrayIndexOutOfBounds
        km.restore(new double[][]{new double[3], new double[3], new double[3]},
                new double[]{1, 1, 1}, 0, 0, 0.0, 0.0, 0L);
    }

    @Test
    @DisplayName("seedCenters：K=3 三个中心都被正确写入")
    void seedCenters() {
        OnlineKMeans km = new OnlineKMeans(DIMS);
        double[][] seeds = new double[OnlineKMeans.K][DIMS];
        for (int k = 0; k < OnlineKMeans.K; k++) {
            for (int d = 0; d < DIMS; d++) {
                seeds[k][d] = k;
            }
        }
        km.seedCenters(seeds);
        double[][] centers = km.getCenters();
        for (int k = 0; k < OnlineKMeans.K; k++) {
            for (int d = 0; d < DIMS; d++) {
                assertEquals(k, centers[k][d], 1e-12);
            }
        }
        assertEquals(OnlineKMeans.K, centers.length, "个人基线固定 K=3 个中心");
    }
}
