package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.ai.AiMath;
import com.anticheat.ai.AILabManager;
import com.anticheat.ai.FeatureDimensions;
import com.anticheat.ai.FeatureVector;
import com.anticheat.ai.PlayerAIState;
import com.anticheat.ai.cluster.AnomalyCluster;
import com.anticheat.ai.feedback.LabeledSample;
import com.anticheat.ai.supervised.LogisticModel;
import com.anticheat.managers.AuditManager;
import com.anticheat.web.auth.Permission;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 实验室 REST 端点（全部 /api/ailab/*，需 ailab:read / ailab:manage 权限）：
 * <ul>
 *   <li>GET  /api/ailab/overview            —— 模型状态仪表盘</li>
 *   <li>GET  /api/ailab/players/{uuid}      —— 单玩家 AI 评分明细（可解释性）</li>
 *   <li>GET  /api/ailab/clusters            —— 异常集群浏览器</li>
 *   <li>POST /api/ailab/clusters/{id}/resolve —— 处置集群（dismiss/rule）</li>
 *   <li>GET  /api/ailab/labels              —— 标签列表（筛选+分页）</li>
 *   <li>POST /api/ailab/labels              —— 添加标签（强标签入口）</li>
 *   <li>POST /api/ailab/labels/{id}/correct —— 修正标签</li>
 *   <li>DELETE /api/ailab/labels/{id}       —— 删除标签</li>
 *   <li>POST /api/ailab/train               —— 手动触发训练</li>
 *   <li>GET  /api/ailab/models              —— 模型版本历史</li>
 *   <li>POST /api/ailab/models/{v}/rollback —— 回滚模型版本</li>
 *   <li>POST /api/ailab/simulate            —— 评分模拟器（特征值 → 各模型评分）</li>
 *   <li>GET  /api/ailab/settings            —— 学习开关/权重/阈值</li>
 *   <li>POST /api/ailab/settings            —— 更新设置（运行时内存）</li>
 *   <li>GET  /api/ailab/thresholds          —— 自适应阈值 + 历史曲线</li>
 *   <li>POST /api/ailab/players/{uuid}/reset-baseline —— 重置玩家个人基线</li>
 *   <li>GET  /api/ailab/features            —— 特征字典（48 维描述）</li>
 * </ul>
 */
public class AILabHandler extends AbstractHandler {

    private static final Gson GSON = new Gson();

    public AILabHandler(AdvancedAntiCheat plugin, AuditManager auditManager) {
        super(plugin, auditManager);
    }

    public void register(Javalin app) {
        app.get("/api/ailab/overview", this::overview);
        app.get("/api/ailab/players/{uuid}/scores", this::playerScores);
        app.post("/api/ailab/players/{uuid}/reset-baseline", this::resetBaseline);
        app.get("/api/ailab/clusters", this::clusters);
        app.post("/api/ailab/clusters/{id}/resolve", this::resolveCluster);
        app.get("/api/ailab/labels", this::labels);
        app.post("/api/ailab/labels", this::addLabel);
        app.post("/api/ailab/labels/{id}/correct", this::correctLabel);
        app.delete("/api/ailab/labels/{id}", this::deleteLabel);
        app.post("/api/ailab/train", this::train);
        app.get("/api/ailab/models", this::models);
        app.post("/api/ailab/models/{version}/rollback", this::rollback);
        app.post("/api/ailab/simulate", this::simulate);
        app.get("/api/ailab/settings", this::getSettings);
        app.post("/api/ailab/settings", this::updateSettings);
        app.get("/api/ailab/thresholds", this::thresholds);
        app.get("/api/ailab/features", this::featureDict);
    }

    // ================= 仪表盘 =================

    private void overview(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        Map<String, Object> data = new LinkedHashMap<>();
        if (lab == null || !lab.isRunning()) {
            data.put("enabled", false);
            ok(ctx, data);
            return;
        }
        data.put("enabled", true);
        data.put("settings", lab.settingsSnapshot());

        // 活跃监督模型
        LogisticModel active = lab.getModelRegistry().getActive();
        Map<String, Object> model = new LinkedHashMap<>();
        if (active != null) {
            model.put("version", active.version);
            model.put("trainedAt", active.trainedAt);
            model.put("sampleCount", active.sampleCount);
            model.put("auc", round3(active.auc));
        }
        model.put("exists", active != null);
        data.put("activeModel", model);

        // 模型健康度
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("forestReady", lab.getGlobalEngine().isReady());
        health.put("forestHistory", lab.getGlobalEngine().getHistorySize());
        health.put("forestLastRebuildAt", lab.getGlobalEngine().getLastRebuildAt());
        health.put("openClusters", lab.getClusterDetector().getOpenClusterCount());
        health.put("labelsTotal", lab.getFeedbackStore().countTotal());
        health.put("labelsCheat", lab.getFeedbackStore().countByLabel(1));
        health.put("labelsBenign", lab.getFeedbackStore().countByLabel(0));
        health.put("labelsNewSinceTrain", lab.getFeedbackStore().getNewSinceTrain());
        health.put("trackedPlayers", countTracked(lab));
        data.put("health", health);

        // 在线玩家 AI 评分 Top10
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map.Entry<UUID, PlayerAIState> e : new HashMap<>(playerStates(lab)).entrySet()) {
            PlayerAIState st = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", e.getKey().toString());
            m.put("name", st.getName());
            m.put("personal", round3(st.getPersonalScore()));
            m.put("global", round3(st.getGlobalScore()));
            m.put("supervised", round3(st.getSupervisedScore()));
            m.put("fused", round3(st.getFusedScore()));
            m.put("watchlisted", st.isWatchlisted());
            top.add(m);
        }
        top.sort((a, b) -> Double.compare((double) b.get("fused"), (double) a.get("fused")));
        data.put("topPlayers", top.subList(0, Math.min(10, top.size())));
        ok(ctx, data);
    }

    private void playerScores(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (IllegalArgumentException e) {
            fail(ctx, 400, "非法 UUID");
            return;
        }
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        PlayerAIState st = lab.getState(uuid);
        if (st == null) {
            notFound(ctx, "玩家当前无 AI 状态（不在线）");
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uuid", uuid.toString());
        data.put("name", st.getName());
        data.put("personal", round3(st.getPersonalScore()));
        data.put("global", round3(st.getGlobalScore()));
        data.put("supervised", round3(st.getSupervisedScore()));
        data.put("fused", round3(st.getFusedScore()));
        data.put("watchlisted", st.isWatchlisted());

        FeatureVector fv = st.getLastFeatures();
        if (fv != null && lab.getGlobalEngine() != null) {
            // 可解释性：与全局基线的偏差 Top8
            double[] mean = lab.getGlobalEngine().getGlobalMean();
            double[] std = lab.getGlobalEngine().getGlobalStd();
            List<Map<String, Object>> contributors = new ArrayList<>();
            Integer[] order = new Integer[FeatureDimensions.DIMS];
            for (int i = 0; i < order.length; i++) order[i] = i;
            double[] z = new double[FeatureDimensions.DIMS];
            for (int i = 0; i < FeatureDimensions.DIMS; i++) {
                z[i] = std[i] > 1e-6 ? (fv.get(i) - mean[i]) / std[i] : 0;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(Math.abs(z[b]), Math.abs(z[a])));
            for (int i = 0; i < Math.min(8, order.length); i++) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("dim", order[i]);
                c.put("name", FeatureDimensions.NAMES[order[i]]);
                c.put("desc", FeatureDimensions.DESCRIPTIONS[order[i]]);
                c.put("value", round3(fv.get(order[i])));
                c.put("zDelta", round3(z[order[i]]));
                contributors.add(c);
            }
            data.put("topDeviations", contributors);
        }
        PlayerAIState.KmeansSnapshot ks = kmeansSnapshot(st);
        if (ks != null) {
            data.put("baseline", ks.toMap());
        }
        ok(ctx, data);
    }

    private void resetBaseline(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (IllegalArgumentException e) {
            fail(ctx, 400, "非法 UUID");
            return;
        }
        boolean done = lab.resetPlayerBaseline(uuid);
        audit(ctx, "ailab", "reset-baseline", done ? "success" : "fail", "uuid=" + uuid);
        ok(ctx, Map.of("reset", done));
    }

    // ================= 集群 =================

    private void clusters(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            ok(ctx, List.of());
            return;
        }
        ok(ctx, lab.getClusterDetector().snapshot());
    }

    private void resolveCluster(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        Map<String, Object> body = readBody(ctx);
        String action = String.valueOf(body.getOrDefault("action", "dismiss"));
        String note = String.valueOf(body.getOrDefault("note", ""));
        String id = ctx.pathParam("id");
        AnomalyCluster cluster = lab.getClusterDetector().getCluster(id);
        if (cluster == null) {
            notFound(ctx, "集群不存在或已过期");
            return;
        }
        boolean done;
        if ("rule".equals(action)) {
            done = lab.getClusterDetector().resolve(id, "ruled", note);
            // 生成临时规则签名：将集群指纹投递到自适应阈值模块（提高 aiGlobal 灵敏度记忆）
            lab.getThresholdController().recordConfirm("aiGlobal");
            plugin.getLogger().info("[AILab] 集群 " + id + " 已由管理员转为临时规则指纹");
        } else {
            done = lab.getClusterDetector().resolve(id, "dismissed", note);
            lab.getThresholdController().recordPardon("aiGlobal");
        }
        audit(ctx, "ailab", "cluster-resolve", done ? "success" : "fail",
                "cluster=" + id + " action=" + action);
        ok(ctx, Map.of("resolved", done));
    }

    // ================= 标签 =================

    private void labels(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            ok(ctx, Map.of("items", List.of(), "total", 0));
            return;
        }
        int label = intParam(ctx, "label", -1);
        String source = ctx.queryParam("source");
        String name = ctx.queryParam("name");
        long start = longParam(ctx, "start", 0);
        long end = longParam(ctx, "end", 0);
        int page = intParam(ctx, "page", 0);
        int size = Math.min(100, Math.max(1, intParam(ctx, "size", 20)));

        List<LabeledSample> items = lab.getFeedbackStore().query(label, source, name, start, end, page, size);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LabeledSample s : items) {
            out.add(s.toMap());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", out);
        data.put("total", lab.getFeedbackStore().countTotal());
        data.put("cheat", lab.getFeedbackStore().countByLabel(1));
        data.put("benign", lab.getFeedbackStore().countByLabel(0));
        ok(ctx, data);
    }

    private void addLabel(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        Map<String, Object> body = readBody(ctx);
        String uuidStr = String.valueOf(body.getOrDefault("uuid", ""));
        boolean cheat = Boolean.TRUE.equals(body.get("cheat"))
                || Integer.valueOf(1).equals(toInt(body.get("label")));
        String note = String.valueOf(body.getOrDefault("note", ""));
        String source = String.valueOf(body.getOrDefault("source", LabeledSample.SRC_ADMIN));

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            fail(ctx, 400, "需要合法的玩家 UUID");
            return;
        }
        lab.reportFeedback(uuid, source, cheat, "behavior");
        if (!note.isEmpty()) {
            // 备注：追加到最新样本
            List<LabeledSample> recent = lab.getFeedbackStore().query(-1, null, null, 0, 0, 0, 1);
            if (!recent.isEmpty()) {
                recent.get(0).note = note;
            }
        }
        audit(ctx, "ailab", "label-add", "success",
                "uuid=" + uuid + " cheat=" + cheat + " source=" + source);
        ok(ctx, Map.of("added", true));
    }

    private void correctLabel(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        Map<String, Object> body = readBody(ctx);
        int label = toInt(body.get("label"));
        String note = String.valueOf(body.getOrDefault("note", ""));
        long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            fail(ctx, 400, "非法样本 ID");
            return;
        }
        boolean done = lab.getFeedbackStore().correct(id, label, note);
        audit(ctx, "ailab", "label-correct", done ? "success" : "fail", "id=" + id + " label=" + label);
        ok(ctx, Map.of("corrected", done));
    }

    private void deleteLabel(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            fail(ctx, 400, "非法样本 ID");
            return;
        }
        boolean done = lab.getFeedbackStore().delete(id);
        audit(ctx, "ailab", "label-delete", done ? "success" : "fail", "id=" + id);
        ok(ctx, Map.of("deleted", done));
    }

    // ================= 模型 =================

    private void train(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        // 训练在异步线程执行，立即返回受理状态
        java.util.concurrent.CompletableFuture.runAsync(lab::trainSupervised);
        audit(ctx, "ailab", "train", "accepted", "手动触发训练");
        ok(ctx, Map.of("accepted", true));
    }

    private void models(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            ok(ctx, List.of());
            return;
        }
        ok(ctx, lab.getModelRegistry().historySnapshot());
    }

    private void rollback(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        int version;
        try {
            version = Integer.parseInt(ctx.pathParam("version"));
        } catch (NumberFormatException e) {
            fail(ctx, 400, "非法版本号");
            return;
        }
        LogisticModel m = lab.getModelRegistry().rollback(version);
        audit(ctx, "ailab", "model-rollback", m != null ? "success" : "fail", "version=" + version);
        if (m == null) {
            notFound(ctx, "版本不存在: v" + version);
            return;
        }
        ok(ctx, m.meta());
    }

    // ================= 模拟器 =================

    private void simulate(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        Map<String, Object> body = readBody(ctx);

        // 输入 1：指定在线玩家 → 用其当前特征
        // 输入 2：特征名→值映射 → 缺失维度用全局均值填充
        double[] raw = new double[FeatureDimensions.DIMS];
        double[] mean = lab.getGlobalEngine().getGlobalMean();
        System.arraycopy(mean, 0, raw, 0, FeatureDimensions.DIMS);

        String playerRef = String.valueOf(body.getOrDefault("player", ""));
        boolean fromPlayer = false;
        if (!playerRef.isEmpty()) {
            for (PlayerAIState st : new HashMap<>(playerStates(lab)).values()) {
                if (st.getName().equalsIgnoreCase(playerRef) || st.getUuid().equals(playerRef)) {
                    FeatureVector fv = st.getLastFeatures();
                    if (fv != null) {
                        System.arraycopy(fv.getValues(), 0, raw, 0, FeatureDimensions.DIMS);
                        fromPlayer = true;
                    }
                    break;
                }
            }
            if (!fromPlayer) {
                fail(ctx, 404, "找不到该玩家的当前特征（需在线）");
                return;
            }
        }
        if (body.get("features") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> feats = (Map<String, Object>) body.get("features");
            for (Map.Entry<String, Object> e : feats.entrySet()) {
                int dim = FeatureDimensions.indexOf(e.getKey());
                if (dim >= 0 && e.getValue() instanceof Number) {
                    raw[dim] = ((Number) e.getValue()).doubleValue();
                }
            }
        }

        double[] normalized = lab.getGlobalEngine().normalizeCopy(raw);
        Map<String, Object> data = new LinkedHashMap<>();
        double global = lab.getGlobalEngine().isReady()
                ? lab.getGlobalEngine().scoreBatch(List.of(normalized))[0] : -1;
        LogisticModel model = lab.getModelRegistry().getActive();
        double supervised = model != null ? model.predictNormalized(normalized) : -1;

        data.put("global", round3(global));
        data.put("supervised", round3(supervised));
        data.put("personal", -1.0); // 模拟无个人历史 → 无个人分
        data.put("fused", round3(fuseSim(lab, global, supervised)));
        data.put("fromPlayer", fromPlayer);

        // 主要贡献维度
        List<Map<String, Object>> top = new ArrayList<>();
        Integer[] order = new Integer[FeatureDimensions.DIMS];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(Math.abs(normalized[b]), Math.abs(normalized[a])));
        for (int i = 0; i < Math.min(6, order.length); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", FeatureDimensions.NAMES[order[i]]);
            c.put("desc", FeatureDimensions.DESCRIPTIONS[order[i]]);
            c.put("value", round3(raw[order[i]]));
            c.put("z", round3(normalized[order[i]]));
            top.add(c);
        }
        data.put("topFactors", top);
        ok(ctx, data);
    }

    private double fuseSim(AILabManager lab, double global, double supervised) {
        Map<String, Object> w = labWeights(lab);
        double wp = toDoubleOr(w.get("personal"), 0.35);
        double wg = toDoubleOr(w.get("global"), 0.35);
        double ws = toDoubleOr(w.get("supervised"), 0.30);
        double sum = 0, acc = 0;
        if (global >= 0) {
            sum += wg;
            acc += wg * global;
        }
        if (supervised >= 0) {
            sum += ws;
            acc += ws * supervised;
        }
        return sum > 0 ? acc / sum : 0.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> labWeights(AILabManager lab) {
        Object w = lab.settingsSnapshot().get("weights");
        return w instanceof Map ? (Map<String, Object>) w : Map.of();
    }

    // ================= 设置 / 阈值 / 字典 =================

    private void getSettings(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            ok(ctx, Map.of("enabled", false));
            return;
        }
        ok(ctx, lab.settingsSnapshot());
    }

    private void updateSettings(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_MANAGE)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            fail(ctx, 503, "AI 实验室未启用");
            return;
        }
        Map<String, Object> body = readBody(ctx);
        lab.updateSettings(body);
        audit(ctx, "ailab", "settings-update", "success", String.valueOf(body.keySet()));
        ok(ctx, lab.settingsSnapshot());
    }

    private void thresholds(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        AILabManager lab = plugin.getAILabManager();
        if (lab == null || !lab.isRunning()) {
            ok(ctx, Map.of("current", Map.of(), "history", Map.of()));
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetFpr", 0.005);
        data.put("current", lab.getThresholdController().snapshot());
        data.put("history", lab.getThresholdController().historySnapshot());
        ok(ctx, data);
    }

    private void featureDict(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.AILAB_READ)) return;
        List<Map<String, Object>> dims = new ArrayList<>();
        List<String[]> all = FeatureDimensions.describeAll();
        for (int i = 0; i < all.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("name", all.get(i)[0]);
            m.put("desc", all.get(i)[1]);
            m.put("group", all.get(i)[2]);
            dims.add(m);
        }
        ok(ctx, dims);
    }

    // ================= 辅助 =================

    private Map<UUID, PlayerAIState> playerStates(AILabManager lab) {
        return lab.statesSnapshot();
    }

    private int countTracked(AILabManager lab) {
        return playerStates(lab).size();
    }

    private PlayerAIState.KmeansSnapshot kmeansSnapshot(PlayerAIState st) {
        com.anticheat.ai.baseline.OnlineKMeans km = st.getKmeans();
        if (km == null) return null;
        return new PlayerAIState.KmeansSnapshot(
                km.isWarmedUp(), km.getWarmupProgress(), km.getWarmupTarget(), km.getUpdates());
    }

    private Map<String, Object> readBody(Context ctx) {
        try {
            String body = ctx.body();
            if (body == null || body.isEmpty()) return new HashMap<>();
            JsonObject o = GSON.fromJson(body, JsonObject.class);
            return GSON.fromJson(o, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static int intParam(Context ctx, String name, int def) {
        String v = ctx.queryParam(name);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longParam(Context ctx, String name, long def) {
        String v = ctx.queryParam(name);
        if (v == null || v.isEmpty()) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    private static double toDoubleOr(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
