package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.AuditManager;
import com.anticheat.replay.ReplaySettings;
import com.anticheat.web.BukkitBridge;
import com.anticheat.web.auth.Permission;
import com.anticheat.web.dto.ConfigModuleDTO;
import com.anticheat.web.util.JsonMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置中心 REST 端点：
 * <ul>
 *   <li>GET /api/config/modules - 模块配置列表</li>
 *   <li>PUT /api/config/modules/{id} - 修改模块配置</li>
 * </ul>
 * 模块 id 即 config.yml 中 detection.{module}.enabled 等键。
 */
public class ConfigHandler extends AbstractHandler {

    public ConfigHandler(AdvancedAntiCheat plugin, AuditManager auditManager) {
        super(plugin, auditManager);
    }

    public void register(Javalin app) {
        app.get("/api/config/modules", this::list);
        app.put("/api/config/modules/{id}", this::update);
        app.get("/api/config/replay", this::replayGet);
        app.put("/api/config/replay", this::replayUpdate);
    }

    private static final String[][] MODULES = {
            {"fly",       "飞行检测"},
            {"speed",     "速度作弊"},
            {"esp",       "透视 ESP"},
            {"killaura",  "KillAura"},
            {"reach",     "攻击距离"},
            {"scaffold",  "脚手架"},
            {"fastbreak", "快速破坏"},
            {"noslow",    "无减速挖掘"}
    };

    private void list(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.CONFIG_READ)) return;

        List<ConfigModuleDTO> list = new ArrayList<>();
        for (String[] m : MODULES) {
            String key = m[0];
            String name = m[1];
            var cm = plugin.getConfigManager();
            boolean enabled = cm.isDetectionEnabled(key);
            int maxViolations = cm.getMaxViolations(key);
            String banTime = cm.getBanTime(key);
            int kickThreshold = cm.getKickThreshold(key, 20);
            int humanReviewThreshold = cm.getHumanReviewThreshold(key, 15);
            int warningCooldownSecs = cm.getWarningCooldownSecs(key, 2);
            long notifyCooldownMs = cm.getNotifyCooldownMs(key, 5000L);

            // 派生字段（向后兼容旧前端 UI）
            int autoBan = 50 + maxViolations * 10;
            if (autoBan > 100) autoBan = 100;
            int humanReview = Math.max(40, autoBan - 20);

            ConfigModuleDTO dto = new ConfigModuleDTO(key, name, key, enabled, autoBan, humanReview);
            dto.maxViolations = maxViolations;
            dto.banTime = banTime;
            dto.kickThreshold = kickThreshold;
            dto.humanReviewThreshold = humanReviewThreshold;
            dto.warningCooldownSecs = warningCooldownSecs;
            dto.notifyCooldownMs = notifyCooldownMs;
            list.add(dto);
        }
        ok(ctx, list);
    }

    private void update(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.CONFIG_UPDATE)) return;
        String id = ctx.pathParam("id");
        // 校验 id 合法
        boolean validId = false;
        for (String[] m : MODULES) {
            if (m[0].equals(id)) { validId = true; break; }
        }
        if (!validId) {
            fail(ctx, 400, "未知检测模块 id: " + id);
            return;
        }
        ConfigModuleDTO payload = JsonMapper.fromJson(ctx.body(), ConfigModuleDTO.class);
        if (payload == null) {
            fail(ctx, 400, "请求体非法");
            return;
        }
        // 切回主线程保存 config（避免 Bukkit API 警告）
        BukkitBridge.syncRun(plugin, () -> {
            String prefix = "detection." + id + ".";
            // 真实字段直接写
            plugin.getConfig().set(prefix + "enabled", payload.enabled);
            if (payload.maxViolations > 0) {
                plugin.getConfig().set(prefix + "maxViolations", payload.maxViolations);
            } else if (payload.autoBan > 0) {
                // 兼容旧 UI：从 autoBan 反推 maxViolations
                int maxViolations = Math.max(1, (payload.autoBan - 50) / 10);
                plugin.getConfig().set(prefix + "maxViolations", maxViolations);
            }
            if (payload.banTime != null && !payload.banTime.isEmpty()) {
                plugin.getConfig().set(prefix + "banTime", payload.banTime);
            }
            if (payload.kickThreshold > 0) {
                plugin.getConfig().set(prefix + "kickThreshold", payload.kickThreshold);
            }
            if (payload.humanReviewThreshold > 0) {
                plugin.getConfig().set(prefix + "humanReviewThreshold", payload.humanReviewThreshold);
            } else if (payload.humanReview > 0) {
                // 兼容旧 UI：旧字段映射到新字段
                plugin.getConfig().set(prefix + "humanReviewThreshold", payload.humanReview);
            }
            if (payload.warningCooldownSecs > 0) {
                plugin.getConfig().set(prefix + "warningCooldownSecs", payload.warningCooldownSecs);
            }
            if (payload.notifyCooldownMs > 0) {
                plugin.getConfig().set(prefix + "notifyCooldownMs", payload.notifyCooldownMs);
            }
            plugin.saveConfig();
            // reload 让磁盘与内存一致，并刷新 ConfigManager 缓存的 config 引用
            plugin.reloadConfig();
            plugin.getConfigManager().refreshConfig();
        });
        audit(ctx, "config_change", id, "success",
                "enabled=" + payload.enabled
                        + " banTime=" + payload.banTime
                        + " kickThreshold=" + payload.kickThreshold
                        + " humanReviewThreshold=" + payload.humanReviewThreshold
                        + " warningCooldownSecs=" + payload.warningCooldownSecs
                        + " notifyCooldownMs=" + payload.notifyCooldownMs);
        ok(ctx, payload);
    }

    // ===================== 回放/监视配置（需求 1：面板热更 maxConcurrent 等） =====================

    private void replayGet(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.CONFIG_READ)) return;
        ok(ctx, replaySnapshot());
    }

    private void replayUpdate(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.CONFIG_UPDATE)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JsonMapper.fromJson(ctx.body(), Map.class);
        if (body == null) {
            fail(ctx, 400, "请求体非法");
            return;
        }
        ReplaySettings s = plugin.getReplaySettings();
        if (s == null) {
            fail(ctx, 500, "回放配置未初始化");
            return;
        }
        final ReplaySettings settings = s;
        BukkitBridge.syncRun(plugin, () -> {
            // 仅应用请求体中出现的字段（缺失字段保持不变）
            applyReplayField(settings, body, "surveillanceEnabled");
            applyReplayField(settings, body, "autoOnJoin");
            applyReplayField(settings, body, "maxConcurrent");
            applyReplayField(settings, body, "queueEnabled");
            applyReplayField(settings, body, "queueMaxSize");
            applyReplayField(settings, body, "cameraMode");
            applyReplayField(settings, body, "autoShoulderOnViolation");
            settings.persist();
            // reload：刷新 ReplaySettings 引用 + 触发 SurveillanceScheduler.onConfigReload()（补满/收敛队列）
            plugin.reloadConfig();
        });
        audit(ctx, "config_change", "replay", "success", "body=" + ctx.body());
        ok(ctx, replaySnapshot());
    }

    private void applyReplayField(ReplaySettings s, Map<String, Object> body, String key) {
        if (body.containsKey(key) && body.get(key) != null) {
            s.setField(key, body.get(key));
        }
    }

    private Map<String, Object> replaySnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        ReplaySettings s = plugin.getReplaySettings();
        if (s == null) return m;
        int poolSize = plugin.getObserverPoolManager() != null
                ? plugin.getObserverPoolManager().getPoolSize() : 0;
        m.put("surveillanceEnabled", s.isSurveillanceEnabled());
        m.put("autoOnJoin", s.isAutoOnJoin());
        m.put("maxConcurrent", s.getMaxConcurrent());
        m.put("queueEnabled", s.isQueueEnabled());
        m.put("queueMaxSize", s.getQueueMaxSize());
        m.put("cameraMode", s.getCameraMode().name().toLowerCase());
        m.put("autoShoulderOnViolation", s.isAutoShoulderOnViolation());
        m.put("autoShoulderHoldSeconds", s.getAutoShoulderHoldMs() / 1000L);
        m.put("crosshairEnabled", s.isCrosshairEnabled());
        m.put("fullInventory", s.isFullInventory());
        m.put("observerPoolSize", poolSize);
        m.put("effectiveMaxConcurrent", Math.min(s.getMaxConcurrent(), Math.max(1, poolSize)));
        return m;
    }
}
