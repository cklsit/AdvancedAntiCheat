package com.anticheat.ai;

/**
 * AI 数学工具：熵、统计量、相似度、激活函数。
 * 全部为纯函数，无状态，可在任意线程调用。
 */
public final class AiMath {

    private static final double LN2 = Math.log(2.0);

    private AiMath() {
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(max, v);
    }

    public static double sigmoid(double x) {
        if (x >= 0) {
            double z = Math.exp(-x);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(x);
        return z / (1.0 + z);
    }

    public static double tanh(double x) {
        return Math.tanh(x);
    }

    /** Shannon 熵（bit），对已归一化概率分布。 */
    public static double entropy(double[] probs) {
        double h = 0.0;
        for (double p : probs) {
            if (p > 1e-12) {
                h -= p * (Math.log(p) / LN2);
            }
        }
        return h;
    }

    /**
     * 将原始数值序列分桶后计算归一化 Shannon 熵 ∈ [0,1]。
     * 序列全相同（最规律）→ 0；完全均匀分布（最随机）→ 1。
     *
     * @param values  原始样本
     * @param bins    桶数（建议 6~10）
     * @param min     桶范围下界
     * @param max     桶范围上界
     */
    public static double normalizedEntropy(double[] values, int bins, double min, double max) {
        if (values == null || values.length < 2 || bins <= 1) return 0.0;
        double range = max - min;
        if (range <= 1e-9) return 0.0;
        int[] hist = new int[bins];
        int n = 0;
        for (double v : values) {
            if (Double.isNaN(v)) continue;
            int b = (int) ((v - min) / range * bins);
            if (b < 0) b = 0;
            if (b >= bins) b = bins - 1;
            hist[b]++;
            n++;
        }
        if (n < 2) return 0.0;
        double[] probs = new double[bins];
        for (int i = 0; i < bins; i++) probs[i] = hist[i] / (double) n;
        double maxH = Math.log(bins) / LN2;
        return clamp(entropy(probs) / maxH, 0.0, 1.0);
    }

    public static double mean(double[] a) {
        if (a == null || a.length == 0) return 0.0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    public static double variance(double[] a) {
        if (a == null || a.length < 2) return 0.0;
        double m = mean(a);
        double s = 0;
        for (double v : a) {
            double d = v - m;
            s += d * d;
        }
        return s / (a.length - 1);
    }

    /** 峰度（标准化四阶矩），衡量急动/突发性。 */
    public static double kurtosis(double[] a) {
        if (a == null || a.length < 3) return 0.0;
        double m = mean(a);
        double m2 = 0, m4 = 0;
        for (double v : a) {
            double d = v - m;
            m2 += d * d;
            m4 += d * d * d * d;
        }
        m2 /= a.length;
        m4 /= a.length;
        if (m2 < 1e-12) return 0.0;
        return clamp(m4 / (m2 * m2), 0.0, 50.0);
    }

    public static double standardDeviation(double[] a) {
        return Math.sqrt(variance(a));
    }

    /** 欧氏距离。 */
    public static double euclidean(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }

    /** 余弦相似度 ∈ [-1,1]；零向量返回 0。 */
    public static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na < 1e-12 || nb < 1e-12) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Welford 在线统计（线程内单线程使用）。 */
    public static final class OnlineStats {
        private long count;
        private double mean;
        private double m2;

        public void add(double x) {
            count++;
            double d = x - mean;
            mean += d / count;
            m2 += d * (x - mean);
        }

        public long getCount() {
            return count;
        }

        public double getMean() {
            return count == 0 ? 0.0 : mean;
        }

        public double getStd() {
            return count < 2 ? 0.0 : Math.sqrt(m2 / (count - 1));
        }

        public void merge(double mean, double std, long count) {
            if (count <= 0) return;
            if (this.count == 0) {
                this.mean = mean;
                this.m2 = std * std * count;
                this.count = count;
                return;
            }
            long n = this.count + count;
            double delta = mean - this.mean;
            this.mean += delta * count / n;
            this.m2 += std * std * count + delta * delta * count * this.count / n;
            this.count = n;
        }
    }
}
