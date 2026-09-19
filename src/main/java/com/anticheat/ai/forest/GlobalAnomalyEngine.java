package com.anticheat.ai.forest;

import com.anticheat.ai.AiMath;
import com.anticheat.ai.FeatureDimensions;
import com.anticheat.ai.FeatureVector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局无监督异常检测引擎。
 * <p>
 * 维护：
 * <ul>
 *   <li><b>特征标准化器</b>：全局 Welford 在线均值/方差（每维），所有模型共享；</li>
 *   <li><b>样本历史库</b>：最近 24h 内玩家标准化特征向量（上限默认 2000 条，FIFO）；</li>
 *   <li><b>孤立森林</b>：默认每 10 分钟重建一次（由 AILabManager 调度），评分每 30 秒一批。</li>
 * </ul>
 * 线程约定：历史库/标准化器/森林仅由异步调度线程访问（fit/scorePass），
 * {@link #normalize(double[])} 供 Web 线程模拟器只读使用（数组拷贝后写入）。
 */
public class GlobalAnomalyEngine {

    /** 历史库容量上限。 */
    private final int historyCapacity;
    /** 每维标准化统计。 */
    private final AiMath.OnlineStats[] dimStats;
    /** 特征历史（标准化后）。 */
    private final ArrayDeque<double[]> history;
    /** 森林。构造时按配置装配（树数 / 子采样 / 树高）。 */
    private IsolationForest forest;
    /** 历史标准化统计（持久化用）。 */
    private final Map<String, double[]> persistedStats = new ConcurrentHashMap<>();

    private volatile long lastRebuildAt;
    private volatile long lastScorePassAt;
    private volatile int lastTrainSize;

    /** 使用与该类历史默认值一致的森林参数（100 棵树 / 每棵子采样 256 / 树高上限 8）。 */
    public GlobalAnomalyEngine(int historyCapacity) {
        this(historyCapacity, 100, 256, 8);
    }

    /**
     * 按显式森林参数构造。
     *
     * <p>这些参数在 config.yml 里以 {@code ailab.forest.trees / sample-size / height-limit}
     * 暴露，此前构造点始终走无参默认值，导致三个配置项写了不生效（运维调不动模型复杂度）。</p>
     */
    public GlobalAnomalyEngine(int historyCapacity, int forestTrees, int forestSampleSize,
                               int forestHeightLimit) {
        this.historyCapacity = Math.max(200, historyCapacity);
        this.dimStats = new AiMath.OnlineStats[FeatureDimensions.DIMS];
        for (int i = 0; i < dimStats.length; i++) {
            dimStats[i] = new AiMath.OnlineStats();
        }
        this.history = new ArrayDeque<>(historyCapacity);
        this.forest = new IsolationForest(forestTrees, forestSampleSize, forestHeightLimit, System.nanoTime());
    }

    // ================= 标准化 =================

    /** 就地标准化（异步调度线程用）。 */
    public void normalizeInPlace(double[] v) {
        for (int i = 0; i < v.length && i < dimStats.length; i++) {
            double std = dimStats[i].getStd();
            double mean = dimStats[i].getMean();
            v[i] = std > 1e-6 ? (v[i] - mean) / std : 0.0;
        }
    }

    /** 拷贝标准化（Web 线程模拟器用）。 */
    public double[] normalizeCopy(double[] v) {
        double[] out = Arrays.copyOf(v, v.length);
        normalizeInPlace(out);
        return out;
    }

    /** 更新全局统计并入库（原始向量 → 标准化）。 */
    public synchronized void ingest(FeatureVector fv) {
        double[] raw = fv.getValues();
        double[] norm = new double[raw.length];
        for (int i = 0; i < raw.length && i < dimStats.length; i++) {
            dimStats[i].add(raw[i]);
            double std = dimStats[i].getStd();
            norm[i] = std > 1e-6 ? (raw[i] - dimStats[i].getMean()) / std : 0.0;
        }
        history.addLast(norm);
        while (history.size() > historyCapacity) {
            history.pollFirst();
        }
    }

    // ================= 训练 / 评分 =================

    /** 重建森林（AILabManager 每 10 分钟调度一次）。 */
    public boolean rebuild() {
        List<double[]> data;
        synchronized (this) {
            if (history.size() < 30) return false;
            data = new ArrayList<>(history);
        }
        forest.fit(data);
        lastRebuildAt = System.currentTimeMillis();
        lastTrainSize = data.size();
        return forest.isReady();
    }

    /** 批量评分：标准化向量数组 → 森林异常分数组；未就绪返回 -1。 */
    public double[] scoreBatch(List<double[]> normalizedVectors) {
        if (!forest.isReady()) {
            double[] out = new double[normalizedVectors.size()];
            Arrays.fill(out, -1);
            return out;
        }
        double[] out = new double[normalizedVectors.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = forest.score(normalizedVectors.get(i));
        }
        lastScorePassAt = System.currentTimeMillis();
        return out;
    }

    public boolean isReady() {
        return forest.isReady();
    }

    public double[] getGlobalMean() {
        double[] m = new double[dimStats.length];
        for (int i = 0; i < m.length; i++) m[i] = dimStats[i].getMean();
        return m;
    }

    public double[] getGlobalStd() {
        double[] s = new double[dimStats.length];
        for (int i = 0; i < s.length; i++) s[i] = Math.max(dimStats[i].getStd(), 1e-6);
        return s;
    }

    // ================= 持久化 =================

    public void save(Path file) {
        try {
            double[] means = new double[dimStats.length];
            double[] stds = new double[dimStats.length];
            long[] counts = new long[dimStats.length];
            for (int i = 0; i < dimStats.length; i++) {
                means[i] = dimStats[i].getMean();
                stds[i] = dimStats[i].getStd();
                counts[i] = dimStats[i].getCount();
            }
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(Map.of(
                        "mean", means,
                        "std", stds,
                        "count", counts,
                        "lastRebuildAt", lastRebuildAt,
                        "lastTrainSize", lastTrainSize
                ), w);
            }
        } catch (Exception e) {
            // 持久化失败不影响运行
        }
    }

    public void load(Path file) {
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            Map<?, ?> m = gson.fromJson(r, Map.class);
            if (m == null) return;
            List<?> mean = (List<?>) m.get("mean");
            List<?> std = (List<?>) m.get("std");
            List<?> count = (List<?>) m.get("count");
            if (mean == null || std == null || count == null) return;
            for (int i = 0; i < dimStats.length && i < mean.size(); i++) {
                dimStats[i].merge(
                        toDouble(mean.get(i)),
                        Math.max(toDouble(std.get(i)), 1e-6),
                        (long) toDouble(count.get(i)));
            }
            lastRebuildAt = (long) toDouble(m.get("lastRebuildAt"));
            lastTrainSize = (int) toDouble(m.get("lastTrainSize"));
        } catch (Exception ignored) {
        }
    }

    private static double toDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    public long getLastRebuildAt() {
        return lastRebuildAt;
    }

    public long getLastScorePassAt() {
        return lastScorePassAt;
    }

    public int getHistorySize() {
        return history.size();
    }
}
