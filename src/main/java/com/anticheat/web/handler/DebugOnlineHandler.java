package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.utils.VersionUtil;
import com.anticheat.web.BukkitBridge;
import com.anticheat.web.dto.PlayerDTO;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * 调试端点：GET /api/debug/online
 * 用于定位 1.8.8 上玩家列表为空的具体原因，不需要鉴权。
 * 输出：
 *   - bukkitDirect: Bukkit.getOnlinePlayers() 实际返回类型、大小
 *   - reflectionRaw: 反射调用 getOnlinePlayers 的结果（模拟 VersionUtil 内部）
 *   - safeGetOnlinePlayers: VersionUtil.safeGetOnlinePlayers 返回大小、name/uuid 列表
 *   - toDTOResults: 每条玩家 toDTO 的 ok/errType/errMsg/errStackTail
 *   - versionInfo: 版本信息
 */
public final class DebugOnlineHandler {

    private final AdvancedAntiCheat plugin;

    public DebugOnlineHandler(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    public void register(Javalin app) {
        app.get("/api/debug/online", this::handle);
    }

    private void handle(Context ctx) {
        JsonObject out = new JsonObject();
        out.addProperty("ts", System.currentTimeMillis());

        // 1. 直接调用 Bukkit.getOnlinePlayers()（无反射，可能触发 1.8.8 返回数组 vs 1.21 返回 Collection 差异）
        try {
            Object o = Bukkit.getOnlinePlayers();
            JsonObject node = new JsonObject();
            node.addProperty("class", o == null ? "null" : o.getClass().getName());
            if (o == null) node.addProperty("size", -1);
            else if (o instanceof Collection) {
                Collection<?> c = (Collection<?>) o;
                node.addProperty("size", c.size());
                node.addProperty("kind", "Collection");
                JsonArray names = new JsonArray();
                int i = 0;
                for (Object x : c) { if (i++ > 20) break; names.add(String.valueOf(x)); }
                node.add("sample", names);
            } else if (o instanceof Object[]) {
                Object[] arr = (Object[]) o;
                node.addProperty("size", arr.length);
                node.addProperty("kind", "Object[]");
                JsonArray names = new JsonArray();
                int n = Math.min(arr.length, 20);
                for (int i = 0; i < n; i++) names.add(String.valueOf(arr[i]));
                node.add("sample", names);
            } else {
                node.addProperty("size", -2);
                node.addProperty("kind", o.getClass().getSimpleName());
            }
            out.add("bukkitDirect", node);
        } catch (Throwable t) {
            out.add("bukkitDirect", err(t));
        }

        // 2. 反射方式：模拟 VersionUtil.safeGetOnlinePlayers 第一层反射
        try {
            Method m = Bukkit.class.getMethod("getOnlinePlayers");
            Object refl = m.invoke(null);
            JsonObject node = new JsonObject();
            node.addProperty("class", refl == null ? "null" : refl.getClass().getName());
            if (refl instanceof Collection) node.addProperty("size", ((Collection<?>) refl).size());
            else if (refl instanceof Object[]) node.addProperty("size", ((Object[]) refl).length);
            out.add("reflectionRaw", node);
        } catch (Throwable t) {
            out.add("reflectionRaw", err(t));
        }

        // 3. VersionUtil.safeGetOnlinePlayers 实际返回（主线程）
        try {
            List<Player> safe = BukkitBridge.syncSupply(plugin, VersionUtil::safeGetOnlinePlayers);
            JsonObject node = new JsonObject();
            node.addProperty("size", safe.size());
            JsonArray arr = new JsonArray();
            for (Player p : safe) {
                JsonObject item = new JsonObject();
                item.addProperty("name", p.getName());
                item.addProperty("uuid", p.getUniqueId().toString());
                try { item.addProperty("online", p.isOnline()); } catch (Throwable ignored) { item.addProperty("online", "?"); }
                arr.add(item);
            }
            node.add("players", arr);
            out.add("safeGetOnlinePlayers", node);
        } catch (Throwable t) {
            out.add("safeGetOnlinePlayers", err(t));
        }

        // 4. toDTO 逐条结果（完全模拟 PlayerHandler.list 的做法，主线程内执行）
        try {
            PlayerHandler ph = new PlayerHandler(plugin, null); // auditManager not needed for toDTO
            JsonArray items = BukkitBridge.syncSupply(plugin, () -> {
                JsonArray result = new JsonArray();
                List<Player> online2 = VersionUtil.safeGetOnlinePlayers();
                for (Player p : online2) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", p.getName());
                    try {
                        PlayerDTO dto = ph.toDTO(p);
                        entry.addProperty("ok", true);
                        entry.addProperty("uuid", dto.uuid == null ? "" : dto.uuid.substring(0, Math.min(8, dto.uuid.length())));
                        entry.addProperty("status", dto.status);
                        entry.addProperty("world", dto.world);
                        entry.addProperty("ip", dto.ip);
                        entry.addProperty("ping", dto.ping);
                        entry.addProperty("gameMode", dto.gameMode);
                        entry.addProperty("riskScore", dto.riskScore);
                    } catch (Throwable t) {
                        entry.addProperty("ok", false);
                        entry.addProperty("errType", t.getClass().getSimpleName());
                        entry.addProperty("err", t.getMessage() == null ? "" : t.getMessage());
                        StringBuilder sb = new StringBuilder();
                        for (StackTraceElement e : t.getStackTrace()) {
                            String s = e.toString();
                            if (s.contains("anticheat") || s.contains("PlayerHandler")) {
                                sb.append(s).append(" <- ");
                                if (sb.length() > 400) break;
                            }
                        }
                        entry.addProperty("stack", sb.toString());
                        if (t.getCause() != null) {
                            entry.addProperty("cause", t.getCause().getClass().getSimpleName() + ": "
                                    + (t.getCause().getMessage() == null ? "" : t.getCause().getMessage()));
                        }
                    }
                    result.add(entry);
                }
                return result;
            });
            JsonObject node = new JsonObject();
            int ok = 0, fail = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getAsJsonObject().get("ok").getAsBoolean()) ok++;
                else fail++;
            }
            node.addProperty("ok", ok);
            node.addProperty("fail", fail);
            node.add("items", items);
            out.add("toDTOResults", node);
        } catch (Throwable t) {
            out.add("toDTOResults", err(t));
        }

        JsonObject version = new JsonObject();
        try { version.addProperty("Bukkit.getVersion()", Bukkit.getVersion()); } catch (Throwable t) { version.addProperty("Bukkit.getVersion()", t.toString()); }
        try { version.addProperty("Bukkit.getBukkitVersion()", Bukkit.getBukkitVersion()); } catch (Throwable t) { version.addProperty("Bukkit.getBukkitVersion()", t.toString()); }
        out.add("versionInfo", version);

        ctx.status(200).contentType("application/json").result(out.toString());
    }

    private static JsonObject err(Throwable t) {
        JsonObject o = new JsonObject();
        o.addProperty("errType", t.getClass().getSimpleName());
        o.addProperty("err", t.getMessage() == null ? "" : t.getMessage());
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : t.getStackTrace()) {
            String s = e.toString();
            if (s.contains("anticheat")) {
                sb.append(s).append(" | ");
                if (sb.length() > 400) break;
            }
        }
        o.addProperty("anticheatStack", sb.toString());
        if (t.getCause() != null) {
            o.addProperty("causeType", t.getCause().getClass().getSimpleName());
            o.addProperty("causeMsg", t.getCause().getMessage() == null ? "" : t.getCause().getMessage());
        }
        return o;
    }
}
