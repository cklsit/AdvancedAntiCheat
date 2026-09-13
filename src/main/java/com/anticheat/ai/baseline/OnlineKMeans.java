package com.anticheat.ai.baseline;

import com.anticheat.ai.AiMath;
import com.anticheat.ai.FeatureDimensions;

import java.util.Arrays;

/**
 * 在线 K-Means 个人行为基线（轻量版）。
 * <p>
 * 每个玩家一个实例，K 个中心（默认 3）学习其"正常状态"分布：
 * <ul>
 *   <li><b>warmup 阶段</b>（默认前 300 个样本 ≈ 5 分钟）：仅累积样本，不产出异常分，
 *       样本均值作为初始中心；</li>
 *   <li><b>在线阶段</b>：新样本就近归属中心，中心按学习率逐步迁移，
 *       学习率随更新次数衰减（lr = lr0 / (1 + updates/decay)）；</li>
 *   <li><b>异常分</b>：样本到最近中心的欧氏距离，经玩家自身距离分布
 *       （OnlineStats）标准化为 z 值，映射到 [0,1]。</li>
 * </ul>
 * 非线程安全 —— 仅由 AI 实验室异步特征线程访问。
 * 内存占用：K × DIMS × 8B ≈ 1.2KB。
 */
public class OnlineKMeans {

    public static final int K = 3;
    private static final double LR0 = 0.08;
    private static final int LR_DECAY = 200;
    /** 判定"离群"的 z 值起点。 */
    private static final double OUTLIER_Z = 3.0;

    private final int dims;
    private final double[][] centers;
    private final long[] centerCounts;

    /** warmup 样本累计（用于初始化中心）。 */
    private final double[] warmupSum;
    private long warmupCount;
    /** warmup 目标样本数（默认 300 ≈ 5 分钟 @1Hz）。 */
    private long warmupTarget = 300;

    /** 已执行的在线更新次数（驱动学习率衰减）。 */
    private long updates;
    /** 距离分布在线统计（个体异常分标准化）。 */
    private final AiMath.OnlineStats distStats = new AiMath.OnlineStats();

    public OnlineKMeans() {
        this(FeatureDimensions.DIMS);
    }

    public OnlineKMeans(int dims) {
        this.dims = dims;
        this.centers = new double[K][dims];
        this.centerCounts = new long[K];
        this.warmupSum = new double[dims];
        // 初始中心分散在不同位置，避免全部坍缩到原点
        for (int k = 0; k < K; k++) {
            centers[k][0] = k * 10.0;
        }
    }

    /** 用外部初始中心（如全局基线）预热。 */
    public void seedCenters(double[][] seeds) {
        if (seeds == null) return;
        for (int k = 0; k < K && k < seeds.length; k++) {
            if (seeds[k] != null && seeds[k].length == dims) {
                System.arraycopy(seeds[k], 0, centers[k], 0, dims);
                centerCounts[k] = 1;
            }
        }
    }

    /**
     * 喂入一个新样本（标准化后的特征向量）。
     *
     * @return 本次样本的个体异常分 ∈ [0,1]；warmup 未完成返回 -1
     */
    public double learn(double[] x) {
        if (x == null || x.length != dims) return -1;

        // ---- warmup：累积 ----
        if (warmupCount < warmupTarget) {
            for (int i = 0; i < dims; i++) warmupSum[i] += x[i];
            warmupCount++;
            if (warmupCount == warmupTarget) {
                for (int i = 0; i < dims; i++) {
                    double m = warmupSum[i] / warmupCount;
                    for (int k = 0; k < K; k++) {
                        // 三个中心从均值 + 少量扰动开始，扰动幅度递增
                        centers[k][i] = m + (k - 1) * 0.05 * Math.abs(m);
                    }
                }
                for (int k = 0; k < K; k++) {
                    centerCounts[k] = warmupCount / K;
                }
            }
            return -1;
        }

        // ---- 找最近中心 ----
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int k = 0; k < K; k++) {
            double d = AiMath.euclidean(x, centers[k]);
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }

        // ---- 更新中心（在线迁移） ----
        double lr = LR0 / (1.0 + updates / (double) LR_DECAY);
        double[] c = centers[best];
        for (int i = 0; i < dims; i++) {
            c[i] += lr * (x[i] - c[i]);
        }
        centerCounts[best]++;
        updates++;

        // ---- 个体异常分 ----
        distStats.add(bestDist);
        return anomalyFromDistance(bestDist);
    }

    /** 直接评分（不更新模型），用于模拟器。 */
    public double score(double[] x) {
        if (warmupCount < warmupTarget) return -1;
        double bestDist = Double.MAX_VALUE;
        for (int k = 0; k < K; k++) {
            bestDist = Math.min(bestDist, AiMath.euclidean(x, centers[k]));
        }
        return anomalyFromDistance(bestDist);
    }

    private double anomalyFromDistance(double dist) {
        double mean = distStats.getMean();
        double std = distStats.getStd();
        if (distStats.getCount() < 30 || std < 1e-9) {
            // 早期：与基线中心绝对距离兜底
            return AiMath.clamp(dist / 8.0, 0.0, 1.0) * 0.5;
        }
        double z = (dist - mean) / std;
        if (z <= 0) return 0.0;
        // z=3 → ~0.5；z=6 → ~0.86；z>=9 → 1
        return AiMath.clamp(0.5 * AiMath.tanh((z - OUTLIER_Z) / 3.0) + 0.5, 0.0, 1.0)
                * (z > OUTLIER_Z ? 1.0 : 0.35);
    }

    /** 仅统计距离（不更新中心），用于距离分布回填。 */
    public double observeDistance(double[] x) {
        double bestDist = Double.MAX_VALUE;
        for (int k = 0; k < K; k++) {
            bestDist = Math.min(bestDist, AiMath.euclidean(x, centers[k]));
        }
        distStats.add(bestDist);
        return bestDist;
    }

    public boolean isWarmedUp() {
        return warmupCount >= warmupTarget;
    }

    public long getUpdates() {
        return updates;
    }

    public long getWarmupProgress() {
        return warmupCount;
    }

    public long getWarmupTarget() {
        return warmupTarget;
    }

    public void setWarmupTarget(long target) {
        if (target > 0) this.warmupTarget = target;
    }

    public double[][] getCenters() {
        double[][] out = new double[K][];
        for (int k = 0; k < K; k++) {
            out[k] = Arrays.copyOf(centers[k], dims);
        }
        return out;
    }

    public double[] getCenterCounts() {
        double[] out = new double[K];
        for (int k = 0; k < K; k++) out[k] = centerCounts[k];
        return out;
    }

    /** 从持久化状态恢复。 */
    public void restore(double[][] centers, double[] counts, long warmupCount, long updates,
                        double distMean, double distStd, long distCount) {
        if (centers != null) {
            for (int k = 0; k < K && k < centers.length; k++) {
                if (centers[k] != null && centers[k].length == dims) {
                    System.arraycopy(centers[k], 0, this.centers[k], 0, dims);
                }
            }
        }
        if (counts != null) {
            for (int k = 0; k < K && k < counts.length; k++) {
                centerCounts[k] = (long) counts[k];
            }
        }
        this.warmupCount = Math.max(warmupCount, 0);
        this.updates = Math.max(updates, 0);
        distStats.merge(distMean, distStd, distCount);
    }
}
