package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.AuditManager;
import com.anticheat.managers.ObserverPoolManager;
import com.anticheat.managers.ffmpeg.FfmpegManager;
import com.anticheat.web.dto.ApiResp;
import com.anticheat.web.handler.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST 路由集中注册。
 * 所有 Handler 实例化在此完成，路由前缀与文档对齐。
 */
public final class WebRouter {

    private WebRouter() {
    }

    public static void register(Javalin app, AdvancedAntiCheat plugin, WebServer webServer) {
        AuditManager auditManager = plugin.getAuditManager();
        if (auditManager == null) {
            plugin.getLogger().warning("[Web] AuditManager 未初始化，REST 路由将不记录审计");
        }

        // ===== HLS 静态路由（外置磁盘文件）：优先注册，避免被 SPA fallback 拦截 =====
        registerHlsRoutes(app, plugin);

        MetaHandler meta = new MetaHandler(plugin, auditManager);
        AuthHandler auth = new AuthHandler(plugin, auditManager, webServer.getAuthManager());
        PlayerHandler players = new PlayerHandler(plugin, auditManager);
        CaseHandler cases = new CaseHandler(plugin, auditManager);
        DashboardHandler dashboard = new DashboardHandler(plugin, auditManager, webServer.getBroadcaster());
        AllianceHandler alliance = new AllianceHandler(plugin, auditManager);
        AuditHandler audit = new AuditHandler(plugin, auditManager);
        NotificationHandler notif = new NotificationHandler(plugin, auditManager);
        ConfigHandler config = new ConfigHandler(plugin, auditManager);
        DebugOnlineHandler debugOnline = new DebugOnlineHandler(plugin);
        ReplayHandler replay = new ReplayHandler(plugin, auditManager);

        meta.register(app);  // 先注册，因为不需要鉴权（AuthFilter 白名单放行）
        debugOnline.register(app);  // 调试端点，白名单放行
        auth.register(app);
        players.register(app);
        cases.register(app);
        dashboard.register(app);
        alliance.register(app);
        audit.register(app);
        notif.register(app);
        config.register(app);
        replay.register(app);

        // ===== 调试：ObserverPoolManager 状态快照（需要鉴权，因为在 /api/* 下走 AuthFilter） =====
        app.get("/api/replay/observer-status", ctx -> {
            ObserverPoolManager opm = plugin.getObserverPoolManager();
            Map<String, Object> resp = new LinkedHashMap<>();
            if (opm == null) {
                resp.put("enabled", false);
                resp.put("error", "ObserverPoolManager 未初始化");
            } else {
                resp.put("enabled", true);
                resp.put("snapshot", opm.statusSnapshot());
            }
            ctx.json(ApiResp.ok(resp));
        });

        plugin.getLogger().info("[Web] REST 路由注册完成");
    }

    /**
     * 注册 HLS 静态路由：<br>
     * - GET /hls/{uuid}/index.m3u8   →  playlist (application/vnd.apple.mpegurl)<br>
     * - GET /hls/{uuid}/seg_{N}.ts   →  切片 (video/mp2t)
     * <p>
     * HLS 根目录优先从 FfmpegManager#getHlsRootDir 读取（遵循 config.yml replay.hls.dir），
     * 否则退化到默认路径：{plugin.getDataFolder()}/hls 。
     */
    private static void registerHlsRoutes(Javalin app, AdvancedAntiCheat plugin) {
        final File hlsRoot;
        FfmpegManager ffmpegManager = plugin.getFfmpegManager();
        if (ffmpegManager != null) {
            hlsRoot = ffmpegManager.getHlsRootDir();
        } else {
            hlsRoot = new File(plugin.getDataFolder(),
                    plugin.getConfig().getString("replay.hls.dir", "hls"));
            hlsRoot.mkdirs();
        }
        plugin.getLogger().info("[Web] HLS 静态根目录: " + hlsRoot.getAbsolutePath());

        app.get("/hls/{uuid}/index.m3u8", ctx -> {
            String uuid = ctx.pathParam("uuid");
            if (!isValidUuidOrDirName(uuid)) { ctx.status(400); return; }
            File f = resolveHlsFile(hlsRoot, uuid, "index.m3u8");
            serveStatic(ctx, f, "application/vnd.apple.mpegurl; charset=utf-8", "no-cache");
        });

        app.get("/hls/{uuid}/seg_{segNum}.ts", ctx -> {
            String uuid = ctx.pathParam("uuid");
            String segNum = ctx.pathParam("segNum");
            if (!isValidUuidOrDirName(uuid)) { ctx.status(400); return; }
            if (!segNum.matches("\\d{5}")) { ctx.status(400); return; }
            File f = resolveHlsFile(hlsRoot, uuid, "seg_" + segNum + ".ts");
            serveStatic(ctx, f, "video/mp2t", "public, max-age=300");
        });
    }

    /**
     * 在 HLS 根目录下定位 <name> 文件。<br>
     * 优先：{@code <hlsRoot>/<uuid>/<name>} —— FfmpegManager.hlsUrl 直链。<br>
     * 回退：{@code <hlsRoot>/<observerId>/<uuid>/<name>} —— docker-compose 按
     * observerId 把容器 /hls 挂到 {@code hls/<observerId>/} 时写入的目录结构
     * （每个 observer 独占一个子目录，互不覆盖）。<br>
     * 回退路径会优先选 m3u8/切片最新更新的 observer，避免两个 observer 同时录像
     * 时拿到过期的旧目录。
     */
    private static File resolveHlsFile(File hlsRoot, String uuid, String name) {
        if (!hlsRoot.isDirectory()) return new File(hlsRoot, "_missing_hlsRoot");
        File direct = new File(new File(hlsRoot, uuid), name);
        if (direct.isFile()) return direct;
        File[] subdirs = hlsRoot.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length == 0) return direct;
        File best = null;
        long bestMtime = 0L;
        for (File sub : subdirs) {
            File candidate = new File(new File(sub, uuid), name);
            if (!candidate.isFile()) continue;
            long m = candidate.lastModified();
            if (best == null || m > bestMtime) {
                best = candidate;
                bestMtime = m;
            }
        }
        return best != null ? best : direct;
    }

    private static void serveStatic(Context ctx, File f, String contentType, String cacheControl) throws java.io.IOException {
        if (!f.exists() || f.isDirectory()) {
            ctx.status(404);
            return;
        }
        ctx.status(200);
        ctx.contentType(contentType);
        ctx.header("Cache-Control", cacheControl);
        // 小于等于 4MB：读入 byte[] 更高效；否则走 InputStream（Javalin 支持 ctx.result(InputStream)）
        long len = f.length();
        if (len <= 4L * 1024 * 1024) {
            ctx.result(Files.readAllBytes(f.toPath()));
        } else {
            InputStream in = new FileInputStream(f);
            ctx.result(in);
        }
    }

    /** 允许 UUID 字符串和简单目录名（字母/数字/-/_），避免路径穿越。 */
    private static boolean isValidUuidOrDirName(String s) {
        if (s == null || s.isEmpty() || s.length() > 80) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException ignore) {
        }
        // fallback：允许 ASCII 字母数字、-、_
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
