package com.anticheat.ai.forest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 孤立森林（Isolation Forest）—— 纯 Java 轻量实现，无第三方依赖。
 * <p>
 * 适合高维稀疏行为向量：通过随机特征 + 随机切分点递归孤立样本，
 * "少而不同"的样本（作弊者）路径长度短、异常分高。
 * <p>
 * 规模：默认 100 棵树、子采样 256、高度限制 8 → 重建一次 &lt; 20ms（1000 样本），
 * 单次评分 ≈ 100 × 8 次比较，可在异步线程高频调用。
 */
public class IsolationForest {

    private static final double EULER_GAMMA = 0.5772156649;

    private final int numTrees;
    private final int sampleSize;
    private final int heightLimit;
    private final Random random;

    private IsolationNode[] trees;

    public IsolationForest() {
        this(100, 256, 8, System.nanoTime());
    }

    public IsolationForest(int numTrees, int sampleSize, int heightLimit, long seed) {
        this.numTrees = Math.max(1, numTrees);
        this.sampleSize = Math.max(4, sampleSize);
        this.heightLimit = Math.max(3, heightLimit);
        this.random = new Random(seed);
    }

    /** 训练（重建森林）。输入可为任意行数；内部自动子采样。 */
    public void fit(List<double[]> data) {
        if (data == null || data.size() < 8) {
            trees = null;
            return;
        }
        List<double[]> sample = data;
        if (data.size() > sampleSize) {
            sample = new ArrayList<>(sampleSize);
            List<double[]> pool = new ArrayList<>(data);
            for (int i = 0; i < sampleSize && !pool.isEmpty(); i++) {
                sample.add(pool.remove(random.nextInt(pool.size())));
            }
        }
        double c = cFactor(sample.size());
        trees = new IsolationNode[numTrees];
        for (int t = 0; t < numTrees; t++) {
            trees[t] = buildTree(sample, 0, c);
        }
    }

    public boolean isReady() {
        return trees != null;
    }

    /**
     * 异常分 ∈ [0,1]，越高越异常。
     * 返回 -1 表示森林尚未训练。
     */
    public double score(double[] x) {
        if (trees == null) return -1;
        double sum = 0;
        for (IsolationNode tree : trees) {
            sum += pathLength(x, tree, 0);
        }
        double avgLen = sum / trees.length;
        double c = cFactor(sampleSize);
        return Math.pow(2.0, -avgLen / c);
    }

    // ================= 内部 =================

    private IsolationNode buildTree(List<double[]> data, int depth, double c) {
        int n = data.size();
        if (depth >= heightLimit || n <= 1) {
            return new IsolationNode(null, null, 0, 0, depth + adjustC(n, c));
        }
        int dims = data.get(0).length;
        // 随机选一个有区分度的维度
        int dim = -1;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int attempt = 0; attempt < 8 && dim == -1; attempt++) {
            int d = random.nextInt(dims);
            min = Double.MAX_VALUE;
            max = -Double.MAX_VALUE;
            for (double[] row : data) {
                double v = row[d];
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (max - min > 1e-9) {
                dim = d;
            }
        }
        if (dim == -1) {
            return new IsolationNode(null, null, 0, 0, depth + adjustC(n, c));
        }
        double split = min + random.nextDouble() * (max - min);
        List<double[]> left = new ArrayList<>(n / 2 + 1);
        List<double[]> right = new ArrayList<>(n / 2 + 1);
        for (double[] row : data) {
            if (row[dim] < split) left.add(row);
            else right.add(row);
        }
        if (left.isEmpty() || right.isEmpty()) {
            return new IsolationNode(null, null, 0, 0, depth + adjustC(n, c));
        }
        return new IsolationNode(
                buildTree(left, depth + 1, c),
                buildTree(right, depth + 1, c),
                dim, split, 0);
    }

    private double pathLength(double[] x, IsolationNode node, int depth) {
        if (node.left == null && node.right == null) {
            return node.pathLen;
        }
        if (x[node.dim] < node.split) {
            return pathLength(x, node.left, depth + 1);
        }
        return pathLength(x, node.right, depth + 1);
    }

    /** BSTS 平均查找路径失败修正项。 */
    private static double adjustC(int n, double c) {
        return n > 1 ? c : 0;
    }

    /** c(n) = 2·H(n−1) − 2(n−1)/n，H(i) = ln(i) + γ。 */
    public static double cFactor(int n) {
        if (n <= 1) return 1.0;
        double h = Math.log(n - 1) + EULER_GAMMA;
        return 2.0 * h - 2.0 * (n - 1) / n;
    }

    /** 二叉树节点（不可变）。 */
    public static final class IsolationNode {
        final IsolationNode left;
        final IsolationNode right;
        final int dim;
        final double split;
        final double pathLen;

        IsolationNode(IsolationNode left, IsolationNode right, int dim, double split, double pathLen) {
            this.left = left;
            this.right = right;
            this.dim = dim;
            this.split = split;
            this.pathLen = pathLen;
        }
    }
}
