package com.anticheat.ai.supervised;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监督分类器（逻辑回归）回归测试。
 *
 * <p>业务语义：用人工确认过的标签（Web 强标签 + 验证码弱标签）训练，
 * 标签不足时绝不能产出模型（否则会用噪声把误报率打飞）。
 * AUC 是模型热切换/回滚的判据，必须可信。
 */
class LogisticModelTest {

    private static final int DIMS = 6;

    /** 构造线性可分数据：正类在 y 轴上偏移。 */
    private static void separable(List<double[]> xs, List<Integer> ys, int n, long seed) {
        Random rnd = new Random(seed);
        for (int i = 0; i < n; i++) {
            boolean cheat = i % 2 == 0;
            double[] x = new double[DIMS];
            for (int d = 0; d < DIMS; d++) {
                x[d] = rnd.nextGaussian() * 0.3 + (cheat ? 2.0 : -2.0);
            }
            xs.add(x);
            ys.add(cheat ? 1 : 0);
        }
    }

    private static double[] mean(int n) {
        double[] m = new double[DIMS];
        java.util.Arrays.fill(m, n);
        return m;
    }

    private static double[] one() {
        double[] s = new double[DIMS];
        java.util.Arrays.fill(s, 1.0);
        return s;
    }

    @Test
    @DisplayName("样本不足 20 条不训练（返回 null，不产垃圾模型）")
    void notEnoughSamples() {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        separable(xs, ys, 18, 1L);
        assertNull(LogisticModel.train(1, xs, ys, mean(0), one(), 0.1, 50, 1e-4));
        assertNull(LogisticModel.train(1, null, null, mean(0), one(), 0.1, 50, 1e-4));
    }

    @Test
    @DisplayName("单类别标签不训练（全是作弊或全不是作弊都无信息）")
    void singleClassIsRefused() {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        Random rnd = new Random(2L);
        for (int i = 0; i < 60; i++) {
            double[] x = new double[DIMS];
            for (int d = 0; d < DIMS; d++) {
                x[d] = rnd.nextGaussian();
            }
            xs.add(x);
            ys.add(1);
        }
        assertNull(LogisticModel.train(1, xs, ys, mean(0), one(), 0.1, 50, 1e-4));
    }

    @Test
    @DisplayName("可分离数据：训练成功、AUC 高、正类概率高于负类")
    void trainsOnSeparableData() {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        separable(xs, ys, 300, 3L);

        LogisticModel model = LogisticModel.train(7, xs, ys, mean(0), one(), 0.2, 200, 1e-5);
        assertNotNull(model, "300 条双类别样本必须能训练出模型");
        assertEquals(7, model.version);
        assertEquals(DIMS, model.weights.length);
        assertTrue(model.sampleCount > 0);
        assertTrue(model.trainedAt > 0, "必须记录训练时间（Web 面板据此显示模型年龄）");
        assertTrue(model.auc > 0.9, "线性可分数据的 AUC 应接近 1，实际 " + model.auc);

        double[] cheater = new double[DIMS];
        double[] clean = new double[DIMS];
        java.util.Arrays.fill(cheater, 2.0);
        java.util.Arrays.fill(clean, -2.0);
        double pCheat = model.predictRaw(cheater);
        double pClean = model.predictRaw(clean);
        assertTrue(pCheat > pClean, "正类概率必须高于负类");
        assertTrue(pCheat > 0.5, "正类样本概率应大于 0.5，实际 " + pCheat);
        assertTrue(pClean < 0.5, "负类样本概率应小于 0.5，实际 " + pClean);
    }

    @Test
    @DisplayName("AUC：完美排序=1、反向=0、并列=0.5、样本不足=0.5")
    void aucSemantics() {
        assertEquals(1.0, LogisticModel.auc(new double[]{0.9, 0.8, 0.2, 0.1},
                new int[]{1, 1, 0, 0}), 1e-9);
        assertEquals(0.0, LogisticModel.auc(new double[]{0.1, 0.2, 0.8, 0.9},
                new int[]{1, 1, 0, 0}), 1e-9);
        assertEquals(0.5, LogisticModel.auc(new double[]{0.5, 0.5, 0.5, 0.5},
                new int[]{1, 1, 0, 0}), 1e-9);
        // 单类别 / 空输入 → 0.5（中性，不能让热切换逻辑误判为"变好"）
        assertEquals(0.5, LogisticModel.auc(new double[]{0.9, 0.8}, new int[]{1, 1}), 1e-9);
        assertEquals(0.5, LogisticModel.auc(new double[]{}, new int[]{}), 1e-9);
        assertEquals(0.5, LogisticModel.auc(null, null), 1e-9);
    }

    @Test
    @DisplayName("predictRaw 必须按训练时的标准化器换算（推理/训练不一致是静默错判）")
    void predictRawUsesNormalizer() {
        double[] weights = {1.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        double[] normMean = {10.0, 0, 0, 0, 0, 0};
        double[] normStd = {2.0, 1, 1, 1, 1, 1};
        LogisticModel model = new LogisticModel(1, weights, 0.0, normMean, normStd, 100, 0.9);

        // z = (12 - 10) / 2 = 1 → sigmoid(1)
        double expected = com.anticheat.ai.AiMath.sigmoid(1.0);
        assertEquals(expected, model.predictRaw(new double[]{12.0, 0, 0, 0, 0, 0}), 1e-9);
        // z = 0 → 0.5
        assertEquals(0.5, model.predictRaw(new double[]{10.0, 0, 0, 0, 0, 0}), 1e-9);
        // std=0 时必须走 1e-6 下限而不是除零产生 Infinity/NaN
        LogisticModel zeroStd = new LogisticModel(1, weights, 0.0, normMean,
                new double[]{0, 0, 0, 0, 0, 0}, 100, 0.9);
        double v = zeroStd.predictRaw(new double[]{10.0, 0, 0, 0, 0, 0});
        assertTrue(v >= 0.0 && v <= 1.0, "零方差维度不能产生 NaN/Inf，实际 " + v);
    }

    @Test
    @DisplayName("save/load 往返：权重、偏置、标准化器、AUC 全部一致")
    void saveLoadRoundTrip(@TempDir Path dir) throws Exception {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        separable(xs, ys, 200, 5L);
        LogisticModel model = LogisticModel.train(3, xs, ys, mean(0), one(), 0.2, 120, 1e-5);
        assertNotNull(model);

        Path file = dir.resolve("model-v3.json");
        model.save(file);
        assertTrue(java.nio.file.Files.exists(file), "模型文件必须落盘");
        assertTrue(java.nio.file.Files.size(file) > 0);

        LogisticModel loaded = LogisticModel.load(file);
        assertNotNull(loaded);
        assertEquals(model.version, loaded.version);
        assertEquals(model.auc, loaded.auc, 1e-9);
        assertEquals(model.bias, loaded.bias, 1e-9);
        assertEquals(model.sampleCount, loaded.sampleCount);
        for (int i = 0; i < DIMS; i++) {
            assertEquals(model.weights[i], loaded.weights[i], 1e-9);
            assertEquals(model.normMean[i], loaded.normMean[i], 1e-9);
            assertEquals(model.normStd[i], loaded.normStd[i], 1e-9);
        }
        double[] probe = new double[DIMS];
        java.util.Arrays.fill(probe, 1.5);
        assertEquals(model.predictRaw(probe), loaded.predictRaw(probe), 1e-9,
                "热加载后推理结果必须完全一致");
    }

    @Test
    @DisplayName("load 遇到的坏文件必须返回 null 而不是抛异常（不能因模型文件损坏阻止插件启动）")
    void loadCorruptFileReturnsNull(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("broken.json");
        java.nio.file.Files.write(bad, "{not json at all".getBytes("UTF-8"));
        assertNull(LogisticModel.load(bad));
        assertNull(LogisticModel.load(dir.resolve("does-not-exist.json")));
    }

    @Test
    @DisplayName("meta() 暴露给 Web 的字段齐全")
    void metaExposesFields() {
        LogisticModel model = new LogisticModel(5, new double[DIMS], 0.1,
                new double[DIMS], one(), 250, 0.87);
        java.util.Map<String, Object> meta = model.meta();
        assertTrue(meta.containsKey("version"));
        assertTrue(meta.containsKey("trainedAt"));
        assertTrue(meta.containsKey("sampleCount"));
        assertTrue(meta.containsKey("auc"));
    }

    @Test
    @DisplayName("训练可复现：同数据同参数 → 同 AUC（CI 断言与回滚对比依赖这点）")
    void trainingIsReproducible() {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        separable(xs, ys, 240, 17L);
        LogisticModel a = LogisticModel.train(1, xs, ys, mean(0), one(), 0.2, 100, 1e-5);
        LogisticModel b = LogisticModel.train(1, xs, ys, mean(0), one(), 0.2, 100, 1e-5);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.auc, b.auc, 1e-12);
        assertEquals(a.bias, b.bias, 1e-12);
    }
}
