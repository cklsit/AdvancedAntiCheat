package com.anticheat.captcha.dtw;

/**
 * 动态时间扭曲（Dynamic Time Warping, DTW）匹配器。
 *
 * <p>用途：把"玩家在若干秒内做出的动作输入序列"与"模板动作序列"对齐比较。
 * 人类复现一串动作时，节奏必然忽快忽慢、中间还有反应延迟——DTW 允许序列在时间轴上
 * 非线性拉伸/压缩，因此比"逐点比对"更适合这类"动作序列相似度"判定。
 *
 * <p>本类是纯函数式的，不持有任何状态，也不依赖 Bukkit，方便单独做单元验证。
 */
public final class DtwMatcher {

    private DtwMatcher() {
    }

    /** 两个索引之间的代价值，返回 0（完全一致）~ 1（完全不同）。 */
    public interface Cost {
        double cost(int templateIndex, int observedIndex);
    }

    /**
     * 计算归一化 DTW 距离（缺做 / 多做使用不同罚分）。
     *
     * <p>递推（gap 步只加罚分，不再叠加代价值，语义更直观）：
     * <pre>
     *   D[i][j] = min( D[i-1][j-1] + cost(i,j),        // 两个动作对上
     *                  D[i-1][j]   + missingPenalty,   // 模板动作玩家没做（漏做）
     *                  D[i][j-1]   + extraPenalty )    // 玩家多做了一个动作
     * </pre>
     * 最终返回值 = D[n-1][m-1] / max(n, m)，落在 0 ~ 1+，越小越相似。
     * 用 max(n, m) 而不是路径长度做归一化，是为了让"漏做一个动作"的惩罚不会被长路径摊薄。
     *
     * <p>为什么两个罚分要分开：短序列（3~4 步）下，"少做一步"和"多做一步"在数学上
     * 是同一量级（都是 1/n），但两者的实际含义完全不同——
     * 漏做/做错说明玩家没看懂或没照着做，是强作弊信号；
     * 多做一步往往只是玩家手抖误触了一次潜行键，误杀代价远大于收益。
     * 所以多做给低罚分（容忍），漏做给高罚分（严判）。
     *
     * @param templateSize   模板序列长度 n
     * @param observedSize   玩家序列长度 m
     * @param cost           代价函数
     * @param missingPenalty 漏做一个模板动作的罚分，建议 1.0
     * @param extraPenalty   玩家多做一个动作的罚分，建议 0.5
     * @return 归一化距离；任一序列为空时返回 1.0（视为完全不像）
     */
    public static double distance(int templateSize, int observedSize, Cost cost,
                                  double missingPenalty, double extraPenalty) {
        if (templateSize <= 0 || observedSize <= 0) {
            return 1.0;
        }

        double[] prev = new double[observedSize];
        double[] cur = new double[observedSize];

        prev[0] = cost.cost(0, 0);
        for (int j = 1; j < observedSize; j++) {
            prev[j] = prev[j - 1] + extraPenalty;
        }

        for (int i = 1; i < templateSize; i++) {
            cur[0] = prev[0] + missingPenalty;
            for (int j = 1; j < observedSize; j++) {
                double diagonal = prev[j - 1] + cost.cost(i, j);
                double missing = prev[j] + missingPenalty;
                double extra = cur[j - 1] + extraPenalty;
                cur[j] = Math.min(diagonal, Math.min(missing, extra));
            }
            double[] swap = prev;
            prev = cur;
            cur = swap;
        }

        return prev[observedSize - 1] / Math.max(templateSize, observedSize);
    }
}
