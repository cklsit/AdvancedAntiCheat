package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ConfigManager;
import com.anticheat.web.auth.AuthFilter;
import com.anticheat.web.auth.AuthManager;
import com.anticheat.web.dto.ApiResp;
import com.anticheat.web.util.JsonMapper;
import com.anticheat.web.ws.AlertBroadcaster;
import com.anticheat.web.ws.WebSocketHandler;
import com.anticheat.web.ws.replay.ReplayBroadcaster;
import com.anticheat.web.ws.replay.ReplayWSHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内嵌 Web 面板主控。
 * <p>
 * 启动时在独立 daemon 线程内拉起 Javalin，避免阻塞 Bukkit 主线程；静态资源从
 * classpath /web/dist 提供；SPA fallback 与 /api 404 走分流策略；全局异常映射为 ApiResp 500。
 */
public class WebServer {

    private final AdvancedAntiCheat plugin;
    private final AuthManager authManager;
    private final AlertBroadcaster broadcaster = new AlertBroadcaster();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Javalin> appRef = new AtomicReference<>();

    public WebServer(AdvancedAntiCheat plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public AlertBroadcaster getBroadcaster() {
        return broadcaster;
    }

    /**
     * 启动 Web 服务器。重复调用是幂等的。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ConfigManager cm = plugin.getConfigManager();
        if (!cm.isWebEnabled()) {
            plugin.getLogger().info("[Web] 面板已在 config.yml 中禁用，跳过启动");
            return;
        }
        String host = cm.getWebHost();
        int port = cm.getWebPort();

        Thread thread = new Thread(() -> {
            try {
                Javalin app = Javalin.create(cfg -> {
                    cfg.showJavalinBanner = false;
                    // 静态资源来自 classpath:/web/dist
                    cfg.staticFiles.add(s -> {
                        s.directory = "/web/dist";
                        s.location = Location.CLASSPATH;
                        s.headers.put("Cache-Control", "no-cache");
                        s.precompress = false;
                    });
                    // CORS 开关
                    if (cm.isWebCorsEnabled()) {
                        cfg.bundledPlugins.enableCors(cors -> cors.addRule(r -> r.anyHost()));
                    }
                });

                // ===== 全局拦截器 =====
                app.before("/api/*", new AuthFilter(authManager));

                // ===== WebSocket 路由 =====
                new WebSocketHandler(authManager, broadcaster).register(app);

                // ===== Replay WS 路由 /ws/replay/:uuid =====
                final ReplayWSHandler replayWS = new ReplayWSHandler(plugin);
                replayWS.register(app);
                // 把 ReplayWSHandler 创建的 broadcaster 注入给 ReplayRecorder 用于 HUD / violation / offline 推送
                ReplayBroadcaster rb = replayWS.getBroadcaster();
                if (plugin.getReplayRecorder() != null) {
                    plugin.getReplayRecorder().setReplayBroadcaster(rb);
                }
                // 同时注入到 AdvancedAntiCheat 全局，供 ObserverPoolManager 推送 observer_ready/observer_error 事件
                plugin.setReplayBroadcaster(rb);

                // ===== REST 路由：由 Router 统一注册 =====
                com.anticheat.web.WebRouter.register(app, plugin, this);

                // ===== 404 SPA fallback：/api 路径返回 JSON，其他返回 index.html =====
                app.error(404, this::handleNotFound);

                // ===== 全局异常映射 =====
                app.exception(Exception.class, (e, ctx) -> {
                    plugin.getLogger().warning("[Web] 处理请求异常: " + e.getMessage());
                    ctx.status(500);
                    ctx.contentType("application/json; charset=utf-8");
                    ctx.result(JsonMapper.toJson(ApiResp.error("服务器内部错误: " + e.getMessage())));
                });

                app.start(host, port);
                appRef.set(app);
                plugin.getLogger().info("[Web] 面板已启动: http://" + host + ":" + port);
            } catch (Throwable t) {
                plugin.getLogger().severe("[Web] 启动失败: " + t.getMessage());
                t.printStackTrace();
            }
        }, "AntiCheat-WebServer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 获取当前 Javalin 实例。可能在 WebServer.start() 完成前为 null。
     */
    public Javalin app() {
        return appRef.get();
    }

    /**
     * 停止 Web 服务器并清理资源。
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        Javalin app = appRef.getAndSet(null);
        if (app != null) {
            try {
                app.stop();
                plugin.getLogger().info("[Web] 面板已关闭");
            } catch (Exception e) {
                plugin.getLogger().warning("[Web] 关闭异常: " + e.getMessage());
            }
        }
        authManager.shutdown();
    }

    private void handleNotFound(Context ctx) {
        String path = ctx.path();
        if (path.startsWith("/api/")) {
            ctx.status(404);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(JsonMapper.toJson(ApiResp.notFound("接口不存在: " + path)));
            return;
        }
        // 数据通道（HLS 切片 / WebSocket）绝不能回落到 SPA 首页。
        // 否则文件尚未落盘时 hls.js 会拿到 index.html 却按 m3u8 解析，
        // 触发 manifestParsingError 后卡在"直播流初始化中"且无法自愈。
        if (path.startsWith("/hls/") || path.startsWith("/ws/")) {
            notFoundPlain(ctx, path);
            return;
        }
        // 静态资源请求（带扩展名，如 .js/.css/.png/.m3u8/.ts）同样不应回落：
        // 前端拿到 HTML 却以为是脚本/媒体，只会制造难以排查的诡异故障。
        if (looksLikeStaticAsset(path)) {
            notFoundPlain(ctx, path);
            return;
        }
        // SPA fallback：返回 index.html（200）
        try (InputStream is = getClass().getResourceAsStream("/web/dist/index.html")) {
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                ctx.status(200);
                ctx.contentType("text/html; charset=utf-8");
                ctx.header("Cache-Control", "no-cache");
                ctx.result(new String(bytes));
            } else {
                ctx.status(404);
                ctx.result("index.html not bundled in JAR");
            }
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("SPA fallback error: " + e.getMessage());
        }
    }

    /** 返回纯文本 404（不做 SPA 回落），保持 no-cache 以免错误响应被缓存。 */
    private static void notFoundPlain(Context ctx, String path) {
        ctx.status(404);
        ctx.contentType("text/plain; charset=utf-8");
        ctx.header("Cache-Control", "no-cache");
        ctx.result("404 Not Found: " + path);
    }

    /**
     * 判断路径是否像静态资源（最后一段含扩展名）。
     * <p>SPA 路由（如 {@code /replay/watch}）没有扩展名，应当回落；
     * {@code /assets/index-abc.js} 这类请求缺失时必须 404，不能吐出 HTML。
     */
    private static boolean looksLikeStaticAsset(String path) {
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        return dot > 0 && dot < last.length() - 1;
    }
}
