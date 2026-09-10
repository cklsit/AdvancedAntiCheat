package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.AuditManager;
import com.anticheat.utils.VersionUtil;
import com.anticheat.web.BukkitBridge;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元信息端点：GET /api/meta（无需鉴权，供前端版本号展示）
 */
public class MetaHandler extends AbstractHandler {

    public MetaHandler(AdvancedAntiCheat plugin, AuditManager auditManager) {
        super(plugin, auditManager);
    }

    public void register(Javalin app) {
        // /api/meta 不需要鉴权（仅版本号，无敏感信息）
        app.get("/api/meta", this::meta);
    }

    private void meta(Context ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 从 plugin.yml 读取插件版本
        PluginDescriptionFile pdf = plugin.getDescription();
        result.put("pluginVersion", pdf == null ? "unknown" : pdf.getVersion());
        result.put("pluginName", pdf == null ? "AdvancedAntiCheat" : pdf.getName());
        // 面板版本（硬编码，前端 build 时同步）
        result.put("panelVersion", "1.7.0");
        // 服务器信息
        result.put("mcVersion", Bukkit.getBukkitVersion());
        result.put("onlinePlayers", BukkitBridge.syncSupply(plugin, () -> {
            List<org.bukkit.entity.Player> list = new ArrayList<>();
            try {
                // 用反射方式获取（跨版本兼容）
                java.lang.reflect.Method m = Bukkit.class.getMethod("getOnlinePlayers");
                Object ret = m.invoke(null);
                if (ret instanceof java.util.Collection) {
                    for (Object p : (java.util.Collection<?>) ret) {
                        if (p instanceof org.bukkit.entity.Player) list.add((org.bukkit.entity.Player) p);
                    }
                } else if (ret instanceof org.bukkit.entity.Player[]) {
                    for (org.bukkit.entity.Player p : (org.bukkit.entity.Player[]) ret) list.add(p);
                }
            } catch (Throwable ignored) { /* fallback below */ }
            if (list.isEmpty()) {
                try {
                    java.util.Collection<? extends org.bukkit.entity.Player> cols = Bukkit.getOnlinePlayers();
                    list.addAll(cols);
                } catch (Throwable ignored2) { /* keep empty */ }
            }
            return list;
        }).size());
        result.put("serverName", plugin.getConfig().getString("database.server-name", "Server-1"));
        ok(ctx, result);
    }
}
