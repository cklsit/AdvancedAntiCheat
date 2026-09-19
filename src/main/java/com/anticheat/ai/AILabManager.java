package com.anticheat.ai;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.ai.adaptive.AdaptiveThresholdController;
import com.anticheat.ai.baseline.OnlineKMeans;
import com.anticheat.ai.cluster.AnomalyCluster;
import com.anticheat.ai.cluster.ClusterDetector;
import com.anticheat.ai.feedback.FeedbackStore;
import com.anticheat.ai.feedback.LabeledSample;
import com.anticheat.ai.forest.GlobalAnomalyEngine;
import com.anticheat.ai.forest.IsolationForest;
import com.anticheat.ai.supervised.LogisticModel;
import com.anticheat.ai.supervised.ModelRegistry;
import com.anticheat.profiles.PlayerProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 实验室总管理器。
 * <p>
 * 组装并调度以下子系统（全部异步，不阻塞主线程）：
 * <ol>
 *   <li>{@link AILabFeatureCollector} —— 主线程快照 + 异步 48 维特征计算（1Hz）；</li>
 *   <li>{@link OnlineKMeans} 个人基线 —— 每玩家增量学习，5 分钟 warmup，
 *       产出"个体异常分"（账号共享/突然开挂/盗号检测）；</li>
 *   <li>{@link GlobalAnomalyEngine}（孤立森林）—— 30 秒评分批次，
 *       10 分钟重建，产出"全局异常分"（未知作弊发现）；</li>
 *   <li>{@link ClusterDetector} —— 新作弊集群发现 + 特征指纹；</li>
 *   <li>{@link AdaptiveThresholdController} —— PID 自适应阈值（误报率闭环）；</li>
 *   <li>{@link LogisticModel} + {@link ModelRegistry} —— 监督分类器，
 *       标签回流（管理员判决/Captcha/赏金）驱动训练，AUC 达标自动热切换。</li>
 * </ol>
 * 融合分 = w1·个人 + w2·全局 + w3·监督（权重可配，缺项自动重归一），
 * 经 {@code AdvancedDetectionManager#updatePlayerRCP} 注入既有 RCP 融合链路。
 */
public class AILabManager {

    private final AdvancedAntiCheat plugin;
    private final AILabFeatureCollector collector;

    /** 每玩家状态。 */
    private final Map<UUID, PlayerAIState> states = new ConcurrentHashMap<>();

    // ===== 子系统 =====
    private GlobalAnomalyEngine globalEngine;
    private ClusterDetector clusterDetector;
    private AdaptiveThresholdController thresholdController;
    private FeedbackStore feedbackStore;
    private ModelRegistry modelRegistry;

    // ===== 配置 =====
    private volatile boolean enabled = true;
    private volatile boolean baselineEnabled = true;
    private volatile boolean forestEnabled = true;
    private volatile boolean supervisedEnabled = true;
    private volatile boolean clusterEnabled = true;
    private volatile boolean learningEnabled = true;
    private volatile double wPersonal = 0.35;
    private volatile double wGlobal = 0.35;
    private volatile double wSupervised = 0.30;
    private volatile double watchThreshold = 0.50;
    private volatile double alertThreshold = 0.90;
    private volatile int minLabelsForTraining = 100;
    private volatile int minNewLabelsForTraining = 20;

    // ===== 调度任务 =====
    private BukkitTask globalScoreTask;
    private BukkitTask forestRebuildTask;
    private BukkitTask thresholdAdjustTask;
    private BukkitTask maintenanceTask;
    private BukkitTask weeklyTrainTask;

    private volatile double currentTps = 20.0;
    private volatile boolean running = false;

    public AILabManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.collector = new AILabFeatureCollector(plugin, this);
        loadConfig();
    }

    // ================= 初始化 / 关闭 =================

    public void initialize() {
        if (!enabled) {
            plugin.getLogger().info("[AILab] 已在配置中禁用，跳过初始化（纯规则模式）");
            return;
        }
        Path dir = plugin.getDataFolder().toPath().resolve("ailab");

        globalEngine = new GlobalAnomalyEngine(
                plugin.getConfig().getInt("ailab.forest.history-capacity", 2000),
                plugin.getConfig().getInt("ailab.forest.trees", 100),
                plugin.getConfig().getInt("ailab.forest.sample-size", 256),
                plugin.getConfig().getInt("ailab.forest.height-limit", 8));
        globalEngine.load(dir.resolve("global_stats.json"));

        clusterDetector = new ClusterDetector(
                plugin.getConfig().getDouble("ailab.cluster.score-threshold", 0.75),
                plugin.getConfig().getDouble("ailab.cluster.cosine-threshold", 0.85),
                plugin.getConfig().getLong("ailab.cluster.ttl-minutes", 120) * 60_000L);

        thresholdController = new AdaptiveThresholdController(
                plugin.getConfig().getInt("ailab.adaptive.window-size", 50));

        feedbackStore = new FeedbackStore(
                dir.resolve("labels.json"),
                plugin.getConfig().getInt("ailab.supervised.max-labels", 20000));
        feedbackStore.load();

        modelRegistry = new ModelRegistry(dir.resolve("models"));
        modelRegistry.load();

        startTasks();
        running = true;
        plugin.getLogger().info("[AILab] AI 实验室已启动：特征 48 维 / 森林树 100 / 融合权重 "
                + wPersonal + "/" + wGlobal + "/" + wSupervised);
    }

    private void startTasks() {
        collector.start();

        // 全局评分批次：30 秒
        globalScoreTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (enabled && forestEnabled) globalScorePass();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 35, 20L * 30);

        // 森林重建 + 集群发现：10 分钟
        forestRebuildTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled || !forestEnabled) return;
                if (globalEngine.rebuild()) {
                    clusterDetector.setGlobalMean(globalEngine.getGlobalMean());
                    if (clusterEnabled) clusterDetectPass();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60 * 3, 20L * 60 * 10);

        // 自适应阈值调节：10 分钟
        thresholdAdjustTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (enabled) thresholdController.adjustAll();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60 * 5, 20L * 60 * 10);

        // 每日维护（低峰假设 04:00 附近由周期触发近似）：24 小时
        maintenanceTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (enabled) dailyMaintenance();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60 * 60, 20L * 60L * 60 * 24);

        // 每周重训练
        weeklyTrainTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (enabled && supervisedEnabled && learningEnabled) trainSupervised();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60 * 30, 20L * 60L * 60 * 24 * 7);

        // TPS 采样（主线程每 5 秒）
        new BukkitRunnable() {
            @Override
            public void run() {
                currentTps = readTps();
            }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    public void shutdown() {
        running = false;
        collector.stop();
        cancelTask(globalScoreTask);
        cancelTask(forestRebuildTask);
        cancelTask(thresholdAdjustTask);
        cancelTask(maintenanceTask);
        cancelTask(weeklyTrainTask);

        if (globalEngine != null) {
            globalEngine.save(plugin.getDataFolder().toPath().resolve("ailab/global_stats.json"));
        }
        // 保存全部在线玩家基线
        for (PlayerAIState st : states.values()) {
            saveBaseline(st);
        }
        if (feedbackStore != null) feedbackStore.save();
        if (modelRegistry != null) modelRegistry.saveRegistry();
        states.clear();
    }

    private void cancelTask(BukkitTask t) {
        if (t != null) t.cancel();
    }

    private void loadConfig() {
        enabled = plugin.getConfig().getBoolean("ailab.enabled", true);
        baselineEnabled = plugin.getConfig().getBoolean("ailab.baseline.enabled", true);
        forestEnabled = plugin.getConfig().getBoolean("ailab.forest.enabled", true);
        supervisedEnabled = plugin.getConfig().getBoolean("ailab.supervised.enabled", true);
        clusterEnabled = plugin.getConfig().getBoolean("ailab.cluster.enabled", true);
        learningEnabled = plugin.getConfig().getBoolean("ailab.supervised.auto-train", true);
        wPersonal = plugin.getConfig().getDouble("ailab.fusion.personal-weight", 0.35);
        wGlobal = plugin.getConfig().getDouble("ailab.fusion.global-weight", 0.35);
        wSupervised = plugin.getConfig().getDouble("ailab.fusion.supervised-weight", 0.30);
        watchThreshold = plugin.getConfig().getDouble("ailab.decision.watch-threshold", 0.50);
        alertThreshold = plugin.getConfig().getDouble("ailab.decision.alert-threshold", 0.90);
        minLabelsForTraining = plugin.getConfig().getInt("ailab.supervised.min-labels", 100);
        minNewLabelsForTraining = plugin.getConfig().getInt("ailab.supervised.min-new-labels", 20);
    }

    // ================= 玩家生命周期 =================

    @SuppressWarnings("UnstableApiUsage")
    public void handleJoin(Player player) {
        if (!running) return;
        PlayerAIState st = new PlayerAIState(player.getUniqueId().toString(), player.getName());
        st.joinedAt = System.currentTimeMillis();
        states.put(player.getUniqueId(), st);
        // 异步恢复基线
        java.util.concurrent.CompletableFuture.runAsync(() -> loadBaseline(st));
    }

    public void handleQuit(UUID uuid) {
        if (!running) return;
        PlayerAIState st = states.remove(uuid);
        if (st != null && baselineEnabled) {
            saveBaseline(st);
        }
    }

    // ================= 特征计算（异步，1Hz，collector 调用） =================

    public void computeFeaturesPass() {
        if (!running || !enabled) return;
        double tps = currentTps;

        // 每 30 秒全局评分一次的节流
        boolean scoreNow = (System.currentTimeMillis() / 1000) % 30 == 0;

        List<UUID> batchUuids = scoreNow && forestEnabled ? new ArrayList<>() : null;
        List<String> batchNames = scoreNow && forestEnabled ? new ArrayList<>() : null;
        List<double[]> batchVecs = scoreNow && forestEnabled ? new ArrayList<>() : null;

        for (Map.Entry<UUID, PlayerAIState> e : states.entrySet()) {
            PlayerAIState st = e.getValue();
            try {
                PlayerProfile profile = plugin.getProfileManager().getProfile(e.getKey());
                FeatureVector fv = collector.buildFeatureVector(st, profile, tps);
                st.setLastFeatures(fv);

                // 全局引擎入库（原始向量）
                globalEngine.ingest(fv);

                // 个人基线
                if (baselineEnabled && st.getKmeans() != null && st.getKmeans().isWarmedUp()) {
                    double[] normalized = globalEngine.normalizeCopy(fv.getValues());
                    double personal = st.getKmeans().learn(normalized);
                    st.setPersonalScore(personal);
                }

                if (batchUuids != null) {
                    batchUuids.add(e.getKey());
                    batchNames.add(st.getName());
                    batchVecs.add(globalEngine.normalizeCopy(fv.getValues()));
                }
            } catch (Throwable ignored) {
                // 单玩家特征失败不影响整批
            }
        }

        if (batchUuids != null && !batchUuids.isEmpty()) {
            scorePass(batchUuids, batchNames, batchVecs);
        }
    }

    /** 全局评分批次：对当前全部在线玩家状态执行一次评分（异步调度入口）。 */
    private void globalScorePass() {
        List<UUID> uuids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<double[]> vecs = new ArrayList<>();
        for (Map.Entry<UUID, PlayerAIState> e : states.entrySet()) {
            FeatureVector fv = e.getValue().getLastFeatures();
            if (fv == null) continue;
            uuids.add(e.getKey());
            names.add(e.getValue().getName());
            vecs.add(globalEngine.normalizeCopy(fv.getValues()));
        }
        if (!uuids.isEmpty()) {
            scorePass(uuids, names, vecs);
        }
    }

    /** 全局评分批次（异步线程）。 */
    private void scorePass(List<UUID> uuids, List<String> names, List<double[]> vecs) {
        double[] globalScores = globalEngine.scoreBatch(vecs);
        LogisticModel model = supervisedEnabled ? modelRegistry.getActive() : null;

        for (int i = 0; i < uuids.size(); i++) {
            PlayerAIState st = states.get(uuids.get(i));
            if (st == null) continue;
            double g = globalScores[i];
            st.setGlobalScore(g);
            double sup = model != null ? model.predictNormalized(vecs.get(i)) : -1.0;
            st.setSupervisedScore(sup);
            double fused = fuse(st.getPersonalScore(), g, sup);
            st.setFusedScore(fused);
            st.setWatchlisted(fused >= watchThreshold && fused < alertThreshold);
            st.setLastScoredAt(System.currentTimeMillis());
        }
    }

    /** 融合评分：可用分量自动重归一。 */
    private double fuse(double personal, double global, double supervised) {
        double wp = wPersonal, wg = wGlobal, ws = wSupervised;
        double sum = 0;
        double acc = 0;
        if (personal >= 0) {
            sum += wp;
            acc += wp * personal;
        }
        if (global >= 0) {
            sum += wg;
            acc += wg * global;
        }
        if (supervised >= 0) {
            sum += ws;
            acc += ws * supervised;
        }
        if (sum <= 0) return 0.0;
        return Math.min(1.0, acc / sum);
    }

    // ================= 集群发现 =================

    private void clusterDetectPass() {
        List<UUID> uuids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<double[]> vecs = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (Map.Entry<UUID, PlayerAIState> e : states.entrySet()) {
            PlayerAIState st = e.getValue();
            FeatureVector fv = st.getLastFeatures();
            if (fv == null || st.getGlobalScore() <= 0) continue;
            uuids.add(e.getKey());
            names.add(st.getName());
            vecs.add(globalEngine.normalizeCopy(fv.getValues()));
            scores.add(st.getGlobalScore());
        }
        if (uuids.size() < 2) return;
        double[] gs = new double[scores.size()];
        for (int i = 0; i < gs.length; i++) gs[i] = scores.get(i);
        List<AnomalyCluster> found = clusterDetector.detect(uuids, names, vecs, gs);
        for (AnomalyCluster c : found) {
            if (c.getMemberUuids().size() >= 2) {
                plugin.getLogger().warning("[AILab] 发现疑似新作弊集群 " + c.getId()
                        + " 成员=" + c.getMemberNames() + " 平均异常分=" + c.getAvgGlobalScore());
            }
        }
    }

    // ================= 监督训练管线 =================

    /**
     * 训练监督模型：标签样本 → 留出评估 → AUC 达标自动热切换。
     * 由"新增标签 ≥ 阈值"或"每周定时"触发。
     */
    public synchronized void trainSupervised() {
        if (!running || !supervisedEnabled) return;
        List<LabeledSample> samples = feedbackStore.allWithFeatures();
        if (samples.size() < minLabelsForTraining) {
            plugin.getLogger().info("[AILab] 标签不足（" + samples.size() + "/" + minLabelsForTraining
                    + "），跳过训练");
            return;
        }

        List<double[]> xs = new ArrayList<>(samples.size());
        List<Integer> ys = new ArrayList<>(samples.size());
        for (LabeledSample s : samples) {
            if (s.features == null || s.features.length != FeatureDimensions.DIMS) continue;
            xs.add(globalEngine.normalizeCopy(s.features));
            ys.add(s.label);
        }

        int version = modelRegistry.allocateVersion();
        LogisticModel candidate = LogisticModel.train(version, xs, ys,
                globalEngine.getGlobalMean(), globalEngine.getGlobalStd(),
                0.05, 200, 1e-4);
        feedbackStore.resetNewSinceTrain();

        if (candidate == null) {
            plugin.getLogger().info("[AILab] 训练样本类别不平衡或不足，本轮未产出模型");
            return;
        }
        LogisticModel promoted = modelRegistry.promote(candidate);
        if (promoted != null) {
            plugin.getLogger().info("[AILab] 新模型 v" + promoted.version + " 已热切换：AUC="
                    + String.format("%.3f", promoted.auc) + " 样本=" + promoted.sampleCount);
        } else {
            plugin.getLogger().info("[AILab] 新模型 v" + candidate.version + " 未超越当前版本（AUC="
                    + String.format("%.3f", candidate.auc) + "），保留为候选");
        }
    }

    /** 每日维护：过期基线清理 + 持久化。 */
    private void dailyMaintenance() {
        globalEngine.save(plugin.getDataFolder().toPath().resolve("ailab/global_stats.json"));
        feedbackStore.save();
        modelRegistry.saveRegistry();
        // 清理 30 天未更新的基线文件
        Path baseDir = plugin.getDataFolder().toPath().resolve("ailab/baselines");
        try {
            if (Files.isDirectory(baseDir)) {
                long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
                try (var files = Files.list(baseDir)) {
                    files.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().endsWith(".json"))
                            .filter(f -> {
                                try {
                                    return Files.getLastModifiedTime(f).toMillis() < cutoff;
                                } catch (Exception e) {
                                    return false;
                                }
                            })
                            .forEach(f -> {
                                try {
                                    Files.deleteIfExists(f);
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }
        plugin.getLogger().info("[AILab] 每日维护完成：历史=" + globalEngine.getHistorySize()
                + " 标签=" + feedbackStore.countTotal()
                + " 集群=" + clusterDetector.getOpenClusterCount());
    }

    // ================= 反馈回流（标签闭环入口） =================

    /**
     * 报告一条标签反馈（各系统挂钩点调用）。
     *
     * @param uuid      玩家
     * @param source    {@link LabeledSample} 来源常量
     * @param isCheat   true = 正样本（作弊）/ false = 负样本（误报）
     * @param relatedModule 关联检测模块（驱动自适应阈值，可为 null）
     */
    public void reportFeedback(UUID uuid, String source, boolean isCheat, String relatedModule) {
        if (!running || feedbackStore == null) return;
        PlayerAIState st = states.get(uuid);
        double[] features = st != null && st.getLastFeatures() != null
                ? st.getLastFeatures().copyValues() : null;
        String name = st != null ? st.getName() : resolveName(uuid);

        feedbackStore.add(new LabeledSample(0, uuid.toString(), name,
                isCheat ? 1 : 0, source, features, System.currentTimeMillis(), ""));

        if (relatedModule != null) {
            if (isCheat) {
                thresholdController.recordConfirm(relatedModule);
            } else {
                thresholdController.recordPardon(relatedModule);
            }
        }

        // 自动训练触发
        if (learningEnabled && supervisedEnabled
                && feedbackStore.getNewSinceTrain() >= minNewLabelsForTraining
                && feedbackStore.countTotal() >= minLabelsForTraining) {
            java.util.concurrent.CompletableFuture.runAsync(this::trainSupervised);
        }
    }

    /** 重置某玩家个人基线（确认盗号后使用）。 */
    public boolean resetPlayerBaseline(UUID uuid) {
        PlayerAIState st = states.get(uuid);
        if (st == null) return false;
        st.setKmeans(null);
        st.setPersonalScore(-1);
        try {
            Files.deleteIfExists(baselineFile(uuid.toString()));
        } catch (Exception ignored) {
        }
        // 重新创建（异步 warmup 重新开始）
        if (baselineEnabled) {
            OnlineKMeans km = new OnlineKMeans();
            km.setWarmupTarget(plugin.getConfig().getLong("ailab.baseline.warmup-samples", 300));
            st.setKmeans(km);
        }
        return true;
    }

    // ================= 基线持久化 =================

    private Path baselineFile(String uuid) {
        return plugin.getDataFolder().toPath().resolve("ailab/baselines/" + uuid + ".json");
    }

    private void saveBaseline(PlayerAIState st) {
        OnlineKMeans km = st.getKmeans();
        if (km == null || !km.isWarmedUp()) return;
        try {
            Gson gson = new GsonBuilder().create();
            Files.createDirectories(baselineFile(st.getUuid()).getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", st.getUuid());
            m.put("name", st.getName());
            m.put("savedAt", System.currentTimeMillis());
            m.put("updates", km.getUpdates());
            m.put("warmupCount", km.getWarmupProgress());
            m.put("centers", km.getCenters());
            try (Writer w = Files.newBufferedWriter(baselineFile(st.getUuid()), StandardCharsets.UTF_8)) {
                gson.toJson(m, w);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadBaseline(PlayerAIState st) {
        if (!baselineEnabled) return;
        try {
            UUID uuid = UUID.fromString(st.getUuid());
            Path f = baselineFile(uuid.toString());
            if (!Files.isRegularFile(f)) return;
            Gson gson = new Gson();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Map<?, ?> m = gson.fromJson(r, Map.class);
                if (m == null) return;
                OnlineKMeans km = new OnlineKMeans();
                km.setWarmupTarget(plugin.getConfig().getLong("ailab.baseline.warmup-samples", 300));
                List<?> centers = (List<?>) m.get("centers");
                double[][] cs = new double[OnlineKMeans.K][FeatureDimensions.DIMS];
                if (centers != null) {
                    for (int k = 0; k < OnlineKMeans.K && k < centers.size(); k++) {
                        List<?> c = (List<?>) centers.get(k);
                        for (int d = 0; d < FeatureDimensions.DIMS && d < c.size(); d++) {
                            cs[k][d] = ((Number) c.get(d)).doubleValue();
                        }
                    }
                }
                km.seedCenters(cs);
                km.restore(cs, null,
                        m.get("warmupCount") instanceof Number ? ((Number) m.get("warmupCount")).longValue() : 0,
                        m.get("updates") instanceof Number ? ((Number) m.get("updates")).longValue() : 0,
                        0, 0, 0);
                // restore 后若 warmupCount 已达目标，标记为可用
                st.setKmeans(km);
            }
        } catch (Exception ignored) {
        }
    }

    private String resolveName(UUID uuid) {
        Player p = org.bukkit.Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : uuid.toString().substring(0, 8);
    }

    // ================= RCP 融合接入（AdvancedDetectionManager 调用） =================

    /** 个人异常分 ∈ [0,1]（warmup 中返回 -1，调用方应跳过）。 */
    public double getPersonalAnomaly(UUID uuid) {
        if (!running || !enabled || !baselineEnabled) return -1;
        PlayerAIState st = states.get(uuid);
        return st != null ? st.getPersonalScore() : -1;
    }

    /** 全局异常分 ∈ [0,1]（森林未就绪返回 -1）。 */
    public double getGlobalAnomaly(UUID uuid) {
        if (!running || !enabled || !forestEnabled) return -1;
        PlayerAIState st = states.get(uuid);
        return st != null && globalEngine.isReady() ? st.getGlobalScore() : -1;
    }

    /** 监督模型作弊概率 ∈ [0,1]（无活跃模型返回 -1）。 */
    public double getSupervisedProbability(UUID uuid) {
        if (!running || !enabled || !supervisedEnabled) return -1;
        PlayerAIState st = states.get(uuid);
        return st != null && modelRegistry.getActive() != null ? st.getSupervisedScore() : -1;
    }

    /** 融合 AI 分 ∈ [0,1]。 */
    public double getFusedScore(UUID uuid) {
        if (!running || !enabled) return 0;
        PlayerAIState st = states.get(uuid);
        return st != null ? st.getFusedScore() : 0;
    }

    /** 静默观察名单（融合分 0.5~0.9）。 */
    public boolean isWatchlisted(UUID uuid) {
        PlayerAIState st = states.get(uuid);
        return st != null && st.isWatchlisted();
    }

    // ================= 状态访问 =================

    public PlayerAIState getState(UUID uuid) {
        return states.get(uuid);
    }

    /** 全部玩家状态快照（Web 线程安全读取）。 */
    public Map<UUID, PlayerAIState> statesSnapshot() {
        return new ConcurrentHashMap<>(states);
    }

    public boolean isRunning() {
        return running;
    }

    public GlobalAnomalyEngine getGlobalEngine() {
        return globalEngine;
    }

    public ClusterDetector getClusterDetector() {
        return clusterDetector;
    }

    public AdaptiveThresholdController getThresholdController() {
        return thresholdController;
    }

    public FeedbackStore getFeedbackStore() {
        return feedbackStore;
    }

    public ModelRegistry getModelRegistry() {
        return modelRegistry;
    }

    // ================= 设置（Web 用） =================

    public Map<String, Object> settingsSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("baselineEnabled", baselineEnabled);
        m.put("forestEnabled", forestEnabled);
        m.put("supervisedEnabled", supervisedEnabled);
        m.put("clusterEnabled", clusterEnabled);
        m.put("learningEnabled", learningEnabled);
        m.put("weights", Map.of("personal", wPersonal, "global", wGlobal, "supervised", wSupervised));
        m.put("watchThreshold", watchThreshold);
        m.put("alertThreshold", alertThreshold);
        m.put("minLabelsForTraining", minLabelsForTraining);
        m.put("minNewLabelsForTraining", minNewLabelsForTraining);
        return m;
    }

    /** 更新设置（仅运行时内存；持久化由 config.yml + 管理员手动 reload 负责）。 */
    public void updateSettings(Map<String, Object> body) {
        if (body.containsKey("enabled")) enabled = Boolean.TRUE.equals(body.get("enabled"));
        if (body.containsKey("baselineEnabled")) baselineEnabled = Boolean.TRUE.equals(body.get("baselineEnabled"));
        if (body.containsKey("forestEnabled")) forestEnabled = Boolean.TRUE.equals(body.get("forestEnabled"));
        if (body.containsKey("supervisedEnabled")) supervisedEnabled = Boolean.TRUE.equals(body.get("supervisedEnabled"));
        if (body.containsKey("clusterEnabled")) clusterEnabled = Boolean.TRUE.equals(body.get("clusterEnabled"));
        if (body.containsKey("learningEnabled")) learningEnabled = Boolean.TRUE.equals(body.get("learningEnabled"));
        if (body.get("weights") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> w = (Map<String, Object>) body.get("weights");
            if (w.get("personal") instanceof Number) wPersonal = ((Number) w.get("personal")).doubleValue();
            if (w.get("global") instanceof Number) wGlobal = ((Number) w.get("global")).doubleValue();
            if (w.get("supervised") instanceof Number) wSupervised = ((Number) w.get("supervised")).doubleValue();
        }
    }

    // ================= 工具 =================

    /** TPS 读取（反射兼容 1.8~1.21）。 */
    private double readTps() {
        try {
            Class<?> mc = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = mc.getMethod("getServer").invoke(null);
            java.lang.reflect.Field f = mc.getDeclaredField("recentTps");
            f.setAccessible(true);
            double[] tps = (double[]) f.get(server);
            if (tps != null && tps.length > 0) return Math.min(20.0, tps[0]);
        } catch (Throwable ignored) {
            // Paper remapped / 低版本失败 → 20.0 兜底
        }
        return 20.0;
    }

    /** join/quit 监听（随管理器注册）。 */
    public Listener lifecycleListener() {
        return new LifecycleListener();
    }

    public class LifecycleListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent e) {
            handleJoin(e.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) {
            handleQuit(e.getPlayer().getUniqueId());
        }
    }
}
