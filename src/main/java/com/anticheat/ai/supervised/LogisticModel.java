package com.anticheat.ai.supervised;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 轻量级监督分类器 —— 逻辑回归（SGD，L2 正则）。
 * <p>
 * 输入为标准化特征向量，输出作弊概率 ∈ [0,1]。
 * 训练与评分均为 O(D)，可在异步线程高频执行；
 * 模型文件为 JSON（权重 + 偏置 + 训练元数据）。
 */
public class LogisticModel {

    public int version;
    public long trainedAt;
    public int sampleCount;
    /** 训练集 AUC。 */
    public double auc;
    public double[] weights;
    public double bias;
    /** 训练时使用的标准化器快照（推理必须与训练一致）。 */
    public double[] normMean;
    public double[] normStd;

    public LogisticModel() {
    }

    public LogisticModel(int version, double[] weights, double bias,
                         double[] normMean, double[] normStd, int sampleCount, double auc) {
        this.version = version;
        this.weights = weights;
        this.bias = bias;
        this.normMean = normMean;
        this.normStd = normStd;
        this.sampleCount = sampleCount;
        this.auc = auc;
        this.trainedAt = System.currentTimeMillis();
    }

    /**
     * 推理：先按训练时标准化器归一化，再线性 + sigmoid。
     *
     * @param rawFeatures 原始特征向量
     * @return 作弊概率 ∈ [0,1]
     */
    public double predictRaw(double[] rawFeatures) {
        double[] z = new double[rawFeatures.length];
        for (int i = 0; i < rawFeatures.length && i < normMean.length; i++) {
            double std = Math.max(normStd[i], 1e-6);
            z[i] = (rawFeatures[i] - normMean[i]) / std;
        }
        return predictNormalized(z);
    }

    /** 已标准化输入的推理。 */
    public double predictNormalized(double[] z) {
        double s = bias;
        for (int i = 0; i < z.length && i < weights.length; i++) {
            s += weights[i] * z[i];
        }
        return com.anticheat.ai.AiMath.sigmoid(s);
    }

    /**
     * 训练：L2 正则 SGD，内置 80/20 分层留出评估 AUC。
     *
     * @return 训练好的模型；样本不足或单类别时返回 null
     */
    public static LogisticModel train(int version, List<double[]> xs, List<Integer> ys,
                                      double[] normMean, double[] normStd,
                                      double learningRate, int epochs, double l2) {
        if (xs == null || ys == null || xs.size() < 20) return null;
        long pos = ys.stream().filter(y -> y == 1).count();
        long neg = ys.size() - pos;
        if (pos < 5 || neg < 5) return null; // 单类别无法训练

        int n = xs.size();
        int dims = xs.get(0).length;

        // 留出集：每第 5 条作为验证
        List<Integer> trainIdx = new java.util.ArrayList<>();
        List<Integer> evalIdx = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % 5 == 4) evalIdx.add(i);
            else trainIdx.add(i);
        }
        if (trainIdx.size() < 16 || evalIdx.isEmpty()) {
            trainIdx.clear();
            evalIdx.clear();
            for (int i = 0; i < n; i++) trainIdx.add(i);
        }

        double[] w = new double[dims];
        double b = 0.0;
        java.util.Random rnd = new java.util.Random(42);

        for (int epoch = 0; epoch < epochs; epoch++) {
            java.util.Collections.shuffle(trainIdx, rnd);
            for (int idx : trainIdx) {
                double[] x = xs.get(idx);
                int y = ys.get(idx);
                double s = b;
                for (int i = 0; i < dims; i++) s += w[i] * x[i];
                double p = com.anticheat.ai.AiMath.sigmoid(s);
                double err = p - y;
                for (int i = 0; i < dims; i++) {
                    w[i] -= learningRate * (err * x[i] + l2 * w[i]);
                }
                b -= learningRate * err;
            }
        }

        // 评估 AUC
        double[] scores = new double[evalIdx.size()];
        int[] labels = new int[evalIdx.size()];
        for (int i = 0; i < evalIdx.size(); i++) {
            double[] x = xs.get(evalIdx.get(i));
            double s = b;
            for (int j = 0; j < dims && j < x.length; j++) s += w[j] * x[j];
            scores[i] = com.anticheat.ai.AiMath.sigmoid(s);
            labels[i] = ys.get(evalIdx.get(i));
        }
        double auc = auc(scores, labels);

        return new LogisticModel(version, w, b,
                normMean.clone(), normStd.clone(), trainIdx.size(), auc);
    }

    /** AUC（Mann-Whitney U 统计量）。 */
    public static double auc(double[] scores, int[] labels) {
        if (scores == null || labels == null || scores.length < 2) return 0.5;
        long pos = 0, neg = 0;
        for (int l : labels) {
            if (l == 1) pos++;
            else neg++;
        }
        if (pos == 0 || neg == 0) return 0.5;
        // 按分数排序并处理并列（平均秩）
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(scores[a], scores[b]));
        double[] ranks = new double[scores.length];
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && scores[order[j + 1]] == scores[order[i]]) j++;
            double avgRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) ranks[order[k]] = avgRank;
            i = j + 1;
        }
        double rankSumPos = 0;
        for (int idx = 0; idx < labels.length; idx++) {
            if (labels[idx] == 1) rankSumPos += ranks[idx];
        }
        double u = rankSumPos - pos * (pos + 1) / 2.0;
        return u / (pos * (double) neg);
    }

    // ================= 持久化 =================

    public void save(Path file) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(this, w);
            }
        } catch (Exception ignored) {
        }
    }

    public static LogisticModel load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, LogisticModel.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 元数据摘要（不回传权重）。 */
    public Map<String, Object> meta() {
        return Map.of(
                "version", version,
                "trainedAt", trainedAt,
                "sampleCount", sampleCount,
                "auc", Math.round(auc * 1000.0) / 1000.0,
                "active", true
        );
    }
}
