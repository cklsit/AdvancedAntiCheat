package com.anticheat.ai.supervised;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 监督模型版本注册表：版本管理 / 热切换 / 回滚。
 * <p>
 * 目录布局（plugin data folder /ailab/models/）：
 * <pre>
 *   registry.json          —— 版本元数据列表 + activeVersion
 *   model_v1.json ...      —— 各版本权重文件
 * </pre>
 * 新模型 AUC 高于当前活跃版本 + 提升门槛时自动热切换；
 * 管理员可随时回滚到任意历史版本（保留全部旧版本文件）。
 */
public class ModelRegistry {

    private static final double PROMOTE_AUC_DELTA = 0.01;
    private static final double MIN_PROMOTE_AUC = 0.60;

    private final Path dir;
    private final List<Map<String, Object>> versions = new ArrayList<>();
    private volatile LogisticModel active;
    private volatile int nextVersion = 1;

    public ModelRegistry(Path dir) {
        this.dir = dir;
    }

    public void load() {
        Path reg = dir.resolve("registry.json");
        if (!Files.isRegularFile(reg)) return;
        try (Reader r = Files.newBufferedReader(reg, StandardCharsets.UTF_8)) {
            Map<?, ?> m = new Gson().fromJson(r, Map.class);
            if (m == null) return;
            Object ver = m.get("nextVersion");
            if (ver instanceof Number) nextVersion = Math.max(1, (int) ((Number) ver).doubleValue());
            Object vs = m.get("versions");
            if (vs instanceof List) {
                for (Object o : (List<?>) vs) {
                    if (o instanceof Map) {
                        versions.add(castMap(o));
                    }
                }
            }
            int activeVersion = -1;
            Object av = m.get("activeVersion");
            if (av instanceof Number) activeVersion = (int) ((Number) av).doubleValue();
            if (activeVersion > 0) {
                LogisticModel loaded = LogisticModel.load(dir.resolve("model_v" + activeVersion + ".json"));
                if (loaded != null) active = loaded;
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * 尝试晋升新模型为活跃版本（热切换）。
     *
     * @return 晋升后的活跃模型；未达标准返回 null（模型仍保存为历史版本）
     */
    public LogisticModel promote(LogisticModel candidate) {
        if (candidate == null) return null;
        candidate.save(dir.resolve("model_v" + candidate.version + ".json"));

        LogisticModel cur = active;
        boolean promote;
        if (cur == null) {
            promote = candidate.auc >= MIN_PROMOTE_AUC;
        } else {
            promote = candidate.auc >= cur.auc + PROMOTE_AUC_DELTA;
        }
        if (promote) {
            active = candidate;
            versions.removeIf(v -> (int) (double) (v.get("version")) == candidate.version);
            versions.add(candidate.meta());
            saveRegistry();
            return candidate;
        }
        // 未晋升也记录为候选版本
        versions.removeIf(v -> (int) (double) (v.get("version")) == candidate.version);
        Map<String, Object> meta = candidate.meta();
        meta.put("active", false);
        versions.add(meta);
        saveRegistry();
        return null;
    }

    /** 回滚到指定版本，返回该模型；失败返回 null。 */
    public LogisticModel rollback(int version) {
        LogisticModel m = LogisticModel.load(dir.resolve("model_v" + version + ".json"));
        if (m == null) return null;
        active = m;
        saveRegistry();
        return m;
    }

    /** 版本历史快照（Web 用）。 */
    public List<Map<String, Object>> historySnapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        int act = active != null ? active.version : -1;
        for (Map<String, Object> v : versionsDesc()) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(v);
            copy.put("active", (int) (double) (v.get("version")) == act);
            out.add(copy);
        }
        return out;
    }

    private List<Map<String, Object>> versionsDesc() {
        List<Map<String, Object>> out = new ArrayList<>(versions);
        out.sort((a, b) -> Integer.compare(
                (int) (double) (b.get("version")), (int) (double) (a.get("version"))));
        return out;
    }

    public LogisticModel getActive() {
        return active;
    }

    public int getNextVersion() {
        return nextVersion;
    }

    public int allocateVersion() {
        return nextVersion++;
    }

    public void saveRegistry() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.createDirectories(dir);
            Map<String, Object> reg = Map.of(
                    "nextVersion", nextVersion,
                    "activeVersion", active != null ? active.version : -1,
                    "versions", versions
            );
            try (Writer w = Files.newBufferedWriter(dir.resolve("registry.json"), StandardCharsets.UTF_8)) {
                gson.toJson(reg, w);
            }
        } catch (Exception ignored) {
        }
    }
}
