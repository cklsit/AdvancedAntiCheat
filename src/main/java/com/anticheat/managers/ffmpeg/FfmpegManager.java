package com.anticheat.managers.ffmpeg;

import com.anticheat.AdvancedAntiCheat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * FfmpegManager：通过 HTTP 调用容器内 observerctl 的 /start、/stop、/status 接口，
 * 管理观察者（observer）池中 3 个 MC 观察者实例的 HLS 直播录制与 mp4 合成。
 * <p>
 * 特性：
 * <ul>
 *   <li>Java 11+ HttpClient，连接/读取均带超时保护（不阻塞 Bukkit 主线程）</li>
 *   <li>支持 1..N 个 observer（Map 的 key 为 observer id，value 为 baseUrl）</li>
 *   <li>启动后台守护线程，每 30 分钟 GC 超过 24 小时未访问的 HLS 目录与顶层 mp4 文件</li>
 *   <li>onDisable 时仅记录告警（PlayerQuitListener 已在会话结束时调用 stopStream）</li>
 * </ul>
 */
public class FfmpegManager {

    // ======================= DTOs =======================

    /** start 接口返回 */
    public static class StreamResponse {
        public final boolean success;
        public final int ffmpegPid;
        public final String hlsUrl;
        public final String error;

        public StreamResponse(boolean success, int ffmpegPid, String hlsUrl, String error) {
            this.success = success;
            this.ffmpegPid = ffmpegPid;
            this.hlsUrl = hlsUrl;
            this.error = error;
        }
    }

    /** stop 接口返回 */
    public static class StopResponse {
        public final boolean success;
        public final String mp4FileAbsolutePath;
        public final String error;

        public StopResponse(boolean success, String mp4FileAbsolutePath, String error) {
            this.success = success;
            this.mp4FileAbsolutePath = mp4FileAbsolutePath;
            this.error = error;
        }
    }

    /** connect 接口返回（让 MC 客户端自动加入服务器） */
    public static class ConnectResponse {
        public final boolean success;
        public final String error;

        public ConnectResponse(boolean success, String error) {
            this.success = success;
            this.error = error;
        }
    }

    /** status 接口返回 */
    public static class StatusResponse {
        public final boolean success;
        public final String mcStatus;
        public final String xvfb;
        public final int ffmpegPid;
        public final int observerId;
        public final String raw;
        public final String error;

        public StatusResponse(boolean success, String mcStatus, String xvfb, int ffmpegPid,
                              int observerId, String raw, String error) {
            this.success = success;
            this.mcStatus = mcStatus;
            this.xvfb = xvfb;
            this.ffmpegPid = ffmpegPid;
            this.observerId = observerId;
            this.raw = raw;
            this.error = error;
        }
    }

    /** concat 接口返回 */
    public static class ConcatResult {
        public final boolean ok;
        public final String mp4Path;
        public final long sizeBytes;
        public final double durationSec;
        public final String error;

        public ConcatResult(boolean ok, String mp4Path, long sizeBytes, double durationSec, String error) {
            this.ok = ok;
            this.mp4Path = mp4Path;
            this.sizeBytes = sizeBytes;
            this.durationSec = durationSec;
            this.error = error;
        }

        public boolean isOk() { return ok; }
        public String getMp4Path() { return mp4Path; }
        public long getSizeBytes() { return sizeBytes; }
        public double getDurationSec() { return durationSec; }
        public String getError() { return error; }
    }

    private static final Duration CONCAT_TIMEOUT = Duration.ofSeconds(200); // concat 最长 180s + 余量

    // ======================= Manager 实现 =======================

    private static final Gson GSON = new Gson();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(35); // ffmpeg concat 最长 30s
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(10);
    private static final long GC_INTERVAL_MS = 30L * 60 * 1000; // 30 分钟
    private static final long GC_TTL_MS = 24L * 60 * 60 * 1000; // 24 小时

    private final AdvancedAntiCheat plugin;
    private final Logger logger;
    private final Map<Integer, String> observerBaseUrls;
    private final File hlsRootDir;
    private final HttpClient httpClient;
    private final Thread gcThread;

    public FfmpegManager(AdvancedAntiCheat plugin, Map<Integer, String> observerBaseUrls, File hlsRootDir) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.observerBaseUrls = new HashMap<>(observerBaseUrls);
        this.hlsRootDir = hlsRootDir;
        if (!this.hlsRootDir.exists()) {
            boolean ok = this.hlsRootDir.mkdirs();
            if (ok) {
                logger.info("[Replay] HLS 根目录创建完成: " + this.hlsRootDir.getAbsolutePath());
            }
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.gcThread = new Thread(this::gcLoop, "AAC-FfmpegGC");
        this.gcThread.setDaemon(true);
        this.gcThread.start();
        logger.info("[Replay] FfmpegManager 已初始化，observer 池大小=" + this.observerBaseUrls.size()
                + "，HLS 根目录=" + this.hlsRootDir.getAbsolutePath());
    }

    // ======================= 公开 API =======================

    /**
     * 向指定 observer 发起录制请求。
     *
     * @return 若成功：{success=true, ffmpegPid, hlsUrl}；失败则 error 非空。
     */
    public StreamResponse startStream(int observerId, UUID targetUuid) {
        String base = observerBaseUrls.get(observerId);
        if (base == null) {
            return new StreamResponse(false, 0, null, "observer id=" + observerId + " 未在池中配置 baseUrl");
        }
        String hlsUrl = hlsUrl(targetUuid);
        try {
            String url = base + "/start?uuid=" + targetUuid;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(START_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                return new StreamResponse(false, 0, hlsUrl,
                        "observer 返回非 2xx: HTTP " + code + ", body=" + truncate(body));
            }
            // 保守判断：body 含 "ok":true 视为成功
            boolean ok = body.contains("\"ok\":true") || body.contains("\"success\":true")
                    || body.trim().equalsIgnoreCase("ok");
            int pid = parseIntField(body, "ffmpeg_pid");
            if (ok && body.contains("\"playlist_ready\":false")) {
                // observer 未在等待窗口内产出首个切片：画面可能延迟若干秒才可用。
                // 前端 hls.js 会自动重试，此处仅告警，便于运维定位慢启动。
                logger.warning("[Replay] observer#" + observerId
                        + " 未在超时内产出首个 HLS 切片，playlist 可能延迟可用: " + hlsUrl);
            }
            return new StreamResponse(ok, pid, hlsUrl, ok ? null : "observer 返回未成功: " + truncate(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StreamResponse(false, 0, hlsUrl, "startStream 被中断: " + e.getMessage());
        } catch (IOException e) {
            // ConnectException / NoRouteToHostException 等的 getMessage() 通常为 null，
            // 仅拼接会变成 "...: null"，看不出原因。补上异常类名 + 实际 URL + 常见原因提示。
            String cls = e.getClass().getSimpleName();
            String detail = e.getMessage();
            String hint = (e instanceof java.net.ConnectException)
                    ? "（容器可能未启动、端口未开放、或 URL 配置错误）"
                    : "";
            return new StreamResponse(false, 0, hlsUrl,
                    "startStream I/O 失败 (observer=" + base + "): " + cls
                            + (detail == null ? hint : ": " + detail));
        } catch (Throwable t) {
            return new StreamResponse(false, 0, hlsUrl, "startStream 异常: " + t.getMessage());
        }
    }

    /**
     * 触发 observer 容器内的 MC 客户端自动连接服务器。
     * 1.8.8 客户端 Main 类不支持 --server 参数，必须由容器侧通过 xdotool 点击菜单加入。
     */
    public ConnectResponse connectServer(int observerId) {
        String base = observerBaseUrls.get(observerId);
        if (base == null) {
            return new ConnectResponse(false, "observer id=" + observerId + " 未在池中配置 baseUrl");
        }
        try {
            String url = base + "/connect";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(START_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                return new ConnectResponse(false,
                        "observer 返回非 2xx: HTTP " + code + ", body=" + truncate(body));
            }
            boolean ok = body.contains("\"ok\":true") || body.contains("\"success\":true")
                    || body.trim().equalsIgnoreCase("ok");
            return new ConnectResponse(ok, ok ? null : "observer 返回未成功: " + truncate(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectResponse(false, "connectServer 被中断: " + e.getMessage());
        } catch (IOException e) {
            String cls = e.getClass().getSimpleName();
            String detail = e.getMessage();
            String hint = (e instanceof java.net.ConnectException)
                    ? "（容器可能未启动、端口未开放、或 URL 配置错误）"
                    : "";
            return new ConnectResponse(false,
                    "connectServer I/O 失败 (observer=" + base + "): " + cls
                            + (detail == null ? hint : ": " + detail));
        } catch (Throwable t) {
            return new ConnectResponse(false, "connectServer 异常: " + t.getMessage());
        }
    }

    /**
     * 让 observer 客户端**按需进入服务器**。
     * <p>观察者不再开机常驻：容器容器只跑 Xorg + observerctl（很轻），
     * 只有管理员通过网页面板观看直播时才调用本方法把 MC 客户端拉起来进服。
     * <p>调用方拿到 success 后还需轮询登录状态（客户端冷启动需数十秒）。
     */
    public ConnectResponse mcUp(int observerId) {
        return postAction(observerId, "/mc/up", "mcUp");
    }

    /**
     * 让 observer 客户端**退出服务器**（离线），容器与 observerctl 仍保持运行，
     * 以便下次有观看请求时可被快速拉起。
     */
    public ConnectResponse mcDown(int observerId) {
        return postAction(observerId, "/mc/down", "mcDown");
    }

    /** 对 observerctl 发起一个无 body 的 POST 动作，统一处理错误与超时。 */
    private ConnectResponse postAction(int observerId, String path, String actionName) {
        String base = observerBaseUrls.get(observerId);
        if (base == null) {
            return new ConnectResponse(false, "observer id=" + observerId + " 未在池中配置 baseUrl");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .timeout(START_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                return new ConnectResponse(false,
                        actionName + " 返回非 2xx: HTTP " + code + ", body=" + truncate(body));
            }
            boolean ok = body.contains("\"ok\":true") || body.contains("\"success\":true")
                    || body.trim().equalsIgnoreCase("ok");
            return new ConnectResponse(ok, ok ? null : actionName + " 返回未成功: " + truncate(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectResponse(false, actionName + " 被中断: " + e.getMessage());
        } catch (IOException e) {
            String cls = e.getClass().getSimpleName();
            String detail = e.getMessage();
            String hint = (e instanceof java.net.ConnectException)
                    ? "（容器可能未启动、端口未开放、或 URL 配置错误）"
                    : "";
            return new ConnectResponse(false,
                    actionName + " I/O 失败 (observer=" + base + "): " + cls
                            + (detail == null ? hint : ": " + detail));
        } catch (Throwable t) {
            return new ConnectResponse(false, actionName + " 异常: " + t.getMessage());
        }
    }

    /**
     * 停止指定 observer 正在进行的录制，并等待 ffmpeg 合成 mp4。
     *
     * @return success=true 时 mp4FileAbsolutePath 为宿主侧绝对路径（由容器 volume 写入）。
     */
    public StopResponse stopStream(int observerId, UUID targetUuid) {
        String base = observerBaseUrls.get(observerId);
        if (base == null) {
            return new StopResponse(false, null, "observer id=" + observerId + " 未在池中配置 baseUrl");
        }
        File expectedMp4 = new File(hlsRootDir, targetUuid + ".mp4").getAbsoluteFile();
        try {
            String url = base + "/stop?uuid=" + targetUuid;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(STOP_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                return new StopResponse(false, null,
                        "observer 返回非 2xx: HTTP " + code + ", body=" + truncate(body));
            }
            boolean ok = body.contains("\"ok\":true") || body.contains("\"success\":true")
                    || body.trim().equalsIgnoreCase("ok");
            return new StopResponse(ok, expectedMp4.toString(),
                    ok ? null : "observer 返回未成功: " + truncate(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StopResponse(false, null, "stopStream 被中断: " + e.getMessage());
        } catch (IOException e) {
            return new StopResponse(false, null, "stopStream I/O 失败: " + e.getMessage());
        } catch (Throwable t) {
            return new StopResponse(false, null, "stopStream 异常: " + t.getMessage());
        }
    }

    /**
     * 查询指定 observer 的运行状态。
     */
    public StatusResponse status(int observerId) {
        String base = observerBaseUrls.get(observerId);
        if (base == null) {
            return new StatusResponse(false, null, null, 0, observerId, null,
                    "observer id=" + observerId + " 未在池中配置 baseUrl");
        }
        try {
            String url = base + "/status";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(STATUS_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                return new StatusResponse(false, null, null, 0, observerId, body,
                        "observer 返回非 2xx: HTTP " + code);
            }
            String mc = null, xvfb = null;
            int pid = 0, oid = 0;
            try {
                JsonElement el = JsonParser.parseString(body);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    mc = strOrNull(obj, "mc_status");
                    xvfb = strOrNull(obj, "xvfb");
                    pid = intOrZero(obj, "ffmpeg_pid");
                    oid = intOrZero(obj, "observer_id");
                    if (oid == 0) oid = observerId; // 容错
                }
            } catch (Throwable ignore) {
                // 非 JSON 时仅返回 raw 字段
            }
            return new StatusResponse(true, mc, xvfb, pid, oid, body, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StatusResponse(false, null, null, 0, observerId, null,
                    "status 被中断: " + e.getMessage());
        } catch (IOException e) {
            return new StatusResponse(false, null, null, 0, observerId, null,
                    "status I/O 失败: " + e.getMessage());
        } catch (Throwable t) {
            return new StatusResponse(false, null, null, 0, observerId, null,
                    "status 异常: " + t.getMessage());
        }
    }

    /**
     * 拼接 HLS m3u8 为 mp4。
     *
     * @param observerId  观察者 id（用于取 baseUrl）
     * @param sessionId   startStream 返回的 session id（当前实现为 target uuid 字符串）
     * @param outFilename 输出文件名（不含路径），建议 UUID + 时间戳组成
     * @return ConcatResult { ok, mp4Path(容器内路径 /hls/archive/xxx.mp4), sizeBytes, durationSec, error }
     * @throws IOException 网络/IO 异常
     */
    public ConcatResult concatVideo(int observerId, String sessionId, String outFilename) throws IOException {
        String base = observerBaseUrls.get(observerId);
        if (base == null) {
            return new ConcatResult(false, null, 0, 0,
                    "observer id=" + observerId + " 未在池中配置 baseUrl");
        }
        try {
            String url = base + "/concat";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", sessionId);
            if (outFilename != null && !outFilename.isEmpty()) {
                body.put("outFilename", outFilename);
            }
            String jsonBody = GSON.toJson(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(CONCAT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String respBody = resp.body() == null ? "" : resp.body();
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                String errMsg = extractErrorField(respBody,
                        "observer 返回非 2xx: HTTP " + code + ", body=" + truncate(respBody));
                return new ConcatResult(false, null, 0, 0, errMsg);
            }
            // 解析 JSON
            boolean ok = false;
            String mp4Path = null;
            long sizeBytes = 0;
            double durationSec = 0;
            String err = null;
            try {
                JsonElement el = JsonParser.parseString(respBody);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    ok = booleanOrFalse(obj, "ok");
                    mp4Path = strOrNull(obj, "mp4Path");
                    sizeBytes = longOrZero(obj, "sizeBytes");
                    durationSec = doubleOrZero(obj, "durationSec");
                    if (!ok) {
                        err = strOrNull(obj, "error");
                        if (err == null) err = "concat returned ok=false: " + truncate(respBody);
                    }
                }
            } catch (Throwable t) {
                return new ConcatResult(false, null, 0, 0,
                        "concat response parse failed: " + t.getMessage());
            }
            if (!ok) {
                return new ConcatResult(false, null, 0, 0, err == null ? "concat unknown error" : err);
            }
            return new ConcatResult(true, mp4Path, sizeBytes, durationSec, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConcatResult(false, null, 0, 0, "concatVideo 被中断: " + e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            return new ConcatResult(false, null, 0, 0, "concatVideo 异常: " + t.getMessage());
        }
    }

    /** 构造 HLS index.m3u8 静态访问路径 */
    public String hlsUrl(UUID uuid) {
        return "/hls/" + uuid + "/index.m3u8";
    }

    /** HLS 根目录（宿主磁盘） */
    public File getHlsRootDir() {
        return hlsRootDir;
    }

    /** 观察者池只读视图 */
    public Map<Integer, String> getObserverBaseUrls() {
        return Map.copyOf(observerBaseUrls);
    }

    /**
     * 插件 onDisable 钩子：关闭 GC 线程；对仍有流的 observer 不做强制 kill，
     * 因为正常流程已由 PlayerQuitListener 触发 release；仅做 warn 记录。
     */
    public void shutdown() {
        if (gcThread != null && gcThread.isAlive()) {
            gcThread.interrupt();
        }
        logger.info("[Replay] FfmpegManager 已关闭（若仍有活跃 observer 流，请检查 PlayerQuitListener 释放逻辑）");
    }

    // ======================= 内部工具 =======================

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 512 ? s.substring(0, 512) + "...(truncated)" : s;
    }

    private static int parseIntField(String body, String field) {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                return intOrZero(el.getAsJsonObject(), field);
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static String strOrNull(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    private static int intOrZero(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            if (el.isJsonPrimitive()) return el.getAsInt();
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static long longOrZero(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            if (el.isJsonPrimitive()) return el.getAsLong();
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static double doubleOrZero(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            if (el.isJsonPrimitive()) return el.getAsDouble();
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static boolean booleanOrFalse(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return false;
        try {
            if (el.isJsonPrimitive()) return el.getAsBoolean();
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static String extractErrorField(String body, String fallback) {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                String err = strOrNull(el.getAsJsonObject(), "error");
                if (err != null && !err.isEmpty()) return err;
            }
        } catch (Throwable ignore) {
        }
        return fallback;
    }

    // ======================= GC 线程 =======================

    private void gcLoop() {
        try {
            // 首次稍等，避免启动风暴
            Thread.sleep(GC_INTERVAL_MS);
            while (!Thread.interrupted()) {
                runGcOnce();
                Thread.sleep(GC_INTERVAL_MS);
            }
        } catch (InterruptedException ok) {
            Thread.currentThread().interrupt();
        }
    }

    private void runGcOnce() {
        try {
            if (!hlsRootDir.isDirectory()) return;
            long now = System.currentTimeMillis();
            File[] files = hlsRootDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                try {
                    if (f.isDirectory()) {
                        // 仅清理 uuid 命名的会话目录。
                        // 必须做名字校验：docker-compose 会把各 observer 容器的 /hls
                        // 分别挂载到 <hlsRoot>/<observerId>/（1、2、3），这些目录是
                        // bind mount 的宿主机源目录。若被 GC 删除，容器重启时会因
                        // "Bind mount failed: ... does not exist" 直接启动失败。
                        if (isUuidName(f.getName())) {
                            if (isExpired(f, now)) {
                                deleteRecursively(f.toPath());
                                logger.info("[Replay][GC] 已删除过期 HLS 目录: " + f.getAbsolutePath());
                            }
                            continue;
                        }
                        // 非 uuid 目录（observerId 挂载目录 / archive）：目录本身绝不能删，
                        // 但里面的会话产物需要回收，否则每个 observer 子目录会无限堆积。
                        gcInsideObserverDir(f, now);
                        continue;
                    }
                    if (f.getName().toLowerCase().endsWith(".mp4") && isExpired(f, now)) {
                        if (f.delete()) {
                            logger.info("[Replay][GC] 已删除过期 mp4 文件: " + f.getAbsolutePath());
                        }
                    }
                } catch (Throwable t) {
                    logger.warning("[Replay][GC] 清理失败 " + f.getAbsolutePath() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            logger.warning("[Replay][GC] 扫描异常: " + t.getMessage());
        }
    }

    private boolean isExpired(File f, long now) {
        return now - Math.max(f.lastModified(), 0L) >= GC_TTL_MS;
    }

    /**
     * 回收 observer 子目录（{@code hls/<observerId>/}）内部的过期产物。
     * <p>只处理两类：uuid 命名的会话切片目录、以及录制中间产物 {@code <uuid>.mp4}。
     * {@code archive/} 下的归档文件是取证资料，交由 config 的 retentionDays 策略处理，
     * 这里绝不动；目录自身更不能删（是容器 bind mount 的源）。
     */
    private void gcInsideObserverDir(File dir, long now) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            try {
                if (c.isDirectory()) {
                    if (isUuidName(c.getName()) && isExpired(c, now)) {
                        deleteRecursively(c.toPath());
                        logger.info("[Replay][GC] 已删除过期 HLS 目录: " + c.getAbsolutePath());
                    }
                } else if (c.getName().toLowerCase().endsWith(".mp4") && isExpired(c, now)) {
                    if (c.delete()) {
                        logger.info("[Replay][GC] 已删除过期 mp4 文件: " + c.getAbsolutePath());
                    }
                }
            } catch (Throwable t) {
                logger.warning("[Replay][GC] 清理失败 " + c.getAbsolutePath() + ": " + t.getMessage());
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 判断目录名是否为 UUID（标准 8-4-4-4-12 十六进制）格式。
     * <p>仅用于 GC 安全检查：HLS 会话目录以目标玩家 UUID 命名，而 docker-compose
     * 挂载的 observerId 目录（1、2、3）与 archive 等目录不应被清理。</p>
     */
    static boolean isUuidName(String name) {
        if (name == null || name.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = name.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else {
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!hex) {
                    return false;
                }
            }
        }
        return true;
    }
}
