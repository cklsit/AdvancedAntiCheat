package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.AuditManager;
import com.anticheat.managers.ReplayRecorder;
import com.anticheat.managers.replay.RingBuffer;
import com.anticheat.managers.replay.TracePoint;
import com.anticheat.managers.replay.ViolationMarker;
import com.anticheat.managers.replay.ZipArchiver;
import com.anticheat.web.auth.Permission;
import com.anticheat.web.handler.AuthHandler;
import com.anticheat.web.dto.ReplayArchiveDTO;
import com.anticheat.web.dto.ReplayDeltaDTO;
import com.anticheat.web.dto.ReplayPlayerDTO;
import com.anticheat.web.dto.ReplaySessionDTO;
import com.anticheat.web.util.JsonMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 新回放 REST 端点（Task 2 — "实时回放" 模式）。
 * <p>
 * 路由：
 * <ul>
 *     <li>{@code GET /api/replays/players} — 在线玩家 session 列表</li>
 *     <li>{@code GET /api/replays/players/{uuid}/session} — 完整轨迹快照（gzip 压缩）</li>
 *     <li>{@code GET /api/replays/players/{uuid}/delta?sinceMs=xxx} — 增量拉取（gzip 压缩）</li>
 *     <li>{@code GET /api/replays/archives} — 历史存档列表</li>
 *     <li>{@code GET /api/replays/archives/{filename}} — 下载 ZIP</li>
 *     <li>{@code DELETE /api/replays/archives/{filename}} — 删除存档</li>
 * </ul>
 */
public class ReplayHandler extends AbstractHandler {

    /** 单个 session 里一个"违规窗口"对应的点数量偏移（用于 delta 的违规计数） */
    public static final int REPLAY_HZ = 20;

    public ReplayHandler(AdvancedAntiCheat plugin, AuditManager auditManager) {
        super(plugin, auditManager);
    }

    public void register(Javalin app) {
        // 旧路由保持兼容（/api/replays /api/replays/{id}），但实际已不再有 segment 概念
        // 新路由体系
        app.get("/api/replays/players", this::listOnlinePlayers);
        app.get("/api/replays/players/{uuid}/session", this::getSession);
        app.get("/api/replays/players/{uuid}/delta", this::getDelta);
        app.get("/api/replays/archives", this::listArchives);
        app.get("/api/replays/archives/{filename}", this::downloadArchive);
        app.get("/api/replays/archives/{filename}/metadata", this::getArchiveMetadata);
        app.delete("/api/replays/archives/{filename}", this::deleteArchive);

        // 时钟校正（设计文档 §3.3）：视频与遥测各自延迟不同，只有统一时基才能对齐。
        // 前端按 (t0, serverTimeMs, t1) 三点估算单向延迟与时钟偏移：
        //   offset ≈ serverTimeMs + rtt/2 - t1
        app.get("/api/replay/clock", this::getClock);
        app.get("/api/replays/clock", this::getClock);

        // 旧路由 — 保留为"暂无数据"提示，避免前端 404
        app.get("/api/replays", this::legacyListEmpty);
        app.get("/api/replays/{legacyId}", this::legacyDetailEmpty);
        app.delete("/api/replays/{legacyId}", this::legacyDeleteEmpty);
    }

    // ========== /api/replay/clock — 服务端墙钟（时基校正） ==========

    /**
     * 返回服务端权威墙钟，供前端做时钟偏移校正。
     *
     * <p>对齐链路：视频延迟 τ≈1–3s、遥测延迟≈50–100ms，二者只有挂在同一个时基上
     * 才能对齐（{@code 视频当前帧对应世界时间 = serverNow - τ}）。
     * 若容器时钟与插件时钟不同步，叠层就会整体错位，因此这里必须可被前端探测。</p>
     *
     * <p>响应体：{@code {serverTimeMs, uptimeMs, retentionMs, ntpSynced}}</p>
     */
    private void getClock(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        Map<String, Object> body = new LinkedHashMap<>();
        // 服务端墙钟（权威时基）
        body.put("serverTimeMs", System.currentTimeMillis());
        // JVM 启动至今，用于前端判断服务端是否重启过（重启后所有会话时基失效）
        body.put("uptimeMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        // 续传窗口（前端据此判断自己的 lastSeq 是否还有可能补齐）
        body.put("retentionMs", com.anticheat.web.ws.replay.ReplayFrameBuffer.RETENTION_MS);
        body.put("ntpSynced", true);
        ok(ctx, body);
    }

    // ========== /api/replays/players — 在线玩家 ==========

    private void listOnlinePlayers(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        Set<UUID> uuids = rr.getActivePlayers();
        Map<UUID, String> names = rr.getPlayerNames();

        List<ReplayPlayerDTO> result = new ArrayList<>(uuids.size());
        long now = System.currentTimeMillis();
        for (UUID uuid : uuids) {
            RingBuffer<TracePoint> buf = rr.getSession(uuid);
            if (buf == null) continue;
            Long epoch = rr.getSessionEpoch(uuid);
            List<ViolationMarker> markers = rr.getViolationMarkers(uuid);

            ReplayPlayerDTO dto = new ReplayPlayerDTO();
            dto.uuid = uuid.toString();
            dto.playerName = names.getOrDefault(uuid, uuid.toString());
            dto.onlineSeconds = epoch != null ? (now - epoch) / 1000L : 0;
            dto.replaySeconds = buf.size() / REPLAY_HZ;
            dto.violationCount = markers.size();
            if (!markers.isEmpty()) {
                ViolationMarker last = markers.get(markers.size() - 1);
                dto.lastViolationType = last.type;
                dto.lastViolationLevel = last.level;
            }
            result.add(dto);
        }

        ok(ctx, result);
    }

    // ========== /api/replays/players/{uuid}/session — 完整快照 ==========

    private void getSession(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        UUID uuid = parseUuid(ctx.pathParam("uuid"));
        if (uuid == null) {
            fail(ctx, 400, "uuid 非法");
            return;
        }

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        RingBuffer<TracePoint> buf = rr.getSession(uuid);
        if (buf == null) {
            notFound(ctx, "玩家当前无在线 session: " + uuid);
            return;
        }

        Long epoch = rr.getSessionEpoch(uuid);
        long now = System.currentTimeMillis();
        List<TracePoint> points = buf.snapshot();
        List<ViolationMarker> markers = rr.getViolationMarkers(uuid);

        // 构造 DTO
        ReplaySessionDTO dto = new ReplaySessionDTO();
        dto.playerUuid = uuid.toString();
        dto.playerName = rr.getPlayerNames().getOrDefault(uuid, uuid.toString());
        dto.startTime = epoch != null ? epoch : now;
        dto.endTime = now;

        // Points
        List<ReplaySessionDTO.Point> dtoPoints = new ArrayList<>(points.size());
        for (TracePoint tp : points) {
            dtoPoints.add(ReplaySessionDTO.fromTracePoint(tp));
        }
        dto.points = dtoPoints;

        // Violations
        List<ReplaySessionDTO.Violation> dtoViolations = new ArrayList<>(markers.size());
        for (ViolationMarker m : markers) {
            TracePoint corr = null;
            if (m.pointIndex >= 0 && m.pointIndex < points.size()) {
                corr = points.get(m.pointIndex);
            }
            dtoViolations.add(ReplaySessionDTO.fromMarker(m, corr));
        }
        dto.violations = dtoViolations;

        // gzip 压缩输出
        writeGzipJson(ctx, dto);
    }

    // ========== /api/replays/players/{uuid}/delta?sinceMs=xxx ==========

    private void getDelta(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        UUID uuid = parseUuid(ctx.pathParam("uuid"));
        if (uuid == null) {
            fail(ctx, 400, "uuid 非法");
            return;
        }

        long sinceMs;
        try {
            String s = ctx.queryParam("sinceMs");
            sinceMs = s != null && !s.isEmpty() ? Long.parseLong(s) : 0L;
        } catch (NumberFormatException e) {
            fail(ctx, 400, "sinceMs 非法");
            return;
        }

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        RingBuffer<TracePoint> buf = rr.getSession(uuid);
        if (buf == null) {
            // session 已结束：返回空 delta（不报错，客户端能识别到）
            writeGzipJson(ctx, new ReplayDeltaDTO());
            return;
        }

        List<TracePoint> allPoints = buf.snapshot();
        List<ViolationMarker> markers = rr.getViolationMarkers(uuid);

        // 筛 timeOffsetMs > sinceMs 的点
        List<ReplaySessionDTO.Point> newPoints = new ArrayList<>();
        for (TracePoint tp : allPoints) {
            if (tp.getTimeOffsetMs() > sinceMs) {
                newPoints.add(ReplaySessionDTO.fromTracePoint(tp));
            }
        }

        // 新违规：violation 对应的 TracePoint.timeOffsetMs > sinceMs
        List<ReplaySessionDTO.Violation> newViolations = new ArrayList<>();
        for (ViolationMarker m : markers) {
            TracePoint corr = null;
            if (m.pointIndex >= 0 && m.pointIndex < allPoints.size()) {
                corr = allPoints.get(m.pointIndex);
            }
            long t = corr != null ? corr.getTimeOffsetMs() : 0;
            if (t > sinceMs) {
                newViolations.add(ReplaySessionDTO.fromMarker(m, corr));
            }
        }

        ReplayDeltaDTO delta = new ReplayDeltaDTO();
        delta.newPoints = newPoints;
        delta.newViolations = newViolations;

        writeGzipJson(ctx, delta);
    }

    // ========== /api/replays/archives — 历史存档 ==========

    private void listArchives(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        File dir = rr.getVideoDir();
        if (dir == null || !dir.exists()) {
            ok(ctx, new ArrayList<>());
            return;
        }

        File[] zips = dir.listFiles((d, name) -> name.endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            ok(ctx, new ArrayList<>());
            return;
        }

        List<ReplayArchiveDTO> result = new ArrayList<>(zips.length);
        for (File f : zips) {
            try {
                ReplayArchiveDTO dto = new ReplayArchiveDTO();
                dto.filename = f.getName();
                dto.sizeKB = f.length() / 1024;

                // 优先尝试新格式：metadata.json (schemaVersion 2/3, hasVideo==true)
                Map<String, Object> newMeta = readNewMetadata(f);
                if (newMeta != null) {
                    // 新格式解析
                    dto.playerName = stringOrNull(newMeta, "playerName");
                    Object startTs = newMeta.get("startTimeMs");
                    if (startTs instanceof Number) {
                        dto.startTime = ((Number) startTs).longValue();
                    }
                    Object dur = newMeta.get("durationMs");
                    if (dur instanceof Number) {
                        dto.durationMs = ((Number) dur).longValue();
                    }
                    Object vios = newMeta.get("violations");
                    if (vios instanceof List) {
                        dto.violationCount = ((List<?>) vios).size();
                    }
                    // 若某些字段缺失，仍保留已填充的
                    if (dto.playerName == null || dto.playerName.isEmpty()) {
                        // fallback：文件名尽力解析
                        String name = f.getName();
                        int firstUnder = name.indexOf('_');
                        // 新文件名: YYYYMMDD_HHmm_name_uuid8.zip
                        String[] parts = name.split("_");
                        if (parts.length >= 3) {
                            dto.playerName = parts[2];
                        } else if (firstUnder > 0) {
                            dto.playerName = name.substring(0, firstUnder);
                        }
                    }
                    if (dto.startTime <= 0) dto.startTime = f.lastModified();
                } else {
                    // 老格式：meta.json（ZipArchiver.Meta）
                    ZipArchiver.Meta meta = ZipArchiver.readMeta(f, JsonMapper.get());
                    if (meta != null) {
                        dto.playerName = meta.playerName;
                        dto.startTime = meta.startTime;
                        dto.durationMs = meta.durationMs;
                        dto.violationCount = meta.violationCount;
                    } else {
                        // meta 读取失败：用文件名尽力解析 playerName
                        String name = f.getName();
                        int idx = name.indexOf('_');
                        if (idx > 0) dto.playerName = name.substring(0, idx);
                        dto.startTime = f.lastModified();
                        dto.durationMs = 0;
                        dto.violationCount = 0;
                    }
                }
                result.add(dto);
            } catch (Throwable t) {
                // 单个文件失败不阻塞整体
            }
        }

        // 按 startTime 倒序
        result.sort((a, b) -> Long.compare(b.startTime, a.startTime));
        ok(ctx, result);
    }

    // ========== /api/replays/archives/{filename} — 下载 ZIP ==========

    private void downloadArchive(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        String filename = ctx.pathParam("filename");
        File target = ZipArchiver.safeResolve(rr.getVideoDir(), filename);
        if (target == null || !target.exists()) {
            notFound(ctx, "存档不存在: " + filename);
            return;
        }

        try {
            byte[] bytes = Files.readAllBytes(target.toPath());
            ctx.status(200);
            ctx.contentType("application/zip");
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            ctx.header("Content-Length", String.valueOf(bytes.length));
            ctx.result(bytes);
        } catch (IOException e) {
            fail(ctx, 500, "读取存档失败: " + e.getMessage());
        }
    }

    // ========== DELETE /api/replays/archives/{filename} ==========

    private void deleteArchive(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.CASES_VERDICT)) return;

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        String filename = ctx.pathParam("filename");
        File target = ZipArchiver.safeResolve(rr.getVideoDir(), filename);
        if (target == null) {
            forbidden(ctx, "非法文件名");
            return;
        }
        if (!target.exists()) {
            notFound(ctx, "存档不存在: " + filename);
            return;
        }

        if (target.delete()) {
            audit(ctx, "replay_archive_delete", filename, "success", null);
            ok(ctx, Map.of("deleted", true));
        } else {
            fail(ctx, 500, "删除失败（文件被占用？）");
        }
    }

    // ========== GET /api/replays/archives/{filename}/metadata ==========

    private void getArchiveMetadata(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr == null) {
            fail(ctx, 503, "回放模块未初始化");
            return;
        }

        String filename = ctx.pathParam("filename");
        File target = ZipArchiver.safeResolve(rr.getVideoDir(), filename);
        if (target == null || !target.exists()) {
            notFound(ctx, "存档不存在: " + filename);
            return;
        }

        // 先用 ZipFile（零拷贝）读 metadata.json（新格式），找不到再退回 meta.json（旧格式）
        String jsonContent = null;
        try (ZipFile zip = new ZipFile(target)) {
            ZipEntry newMeta = zip.getEntry("metadata.json");
            if (newMeta != null) {
                try (InputStream in = zip.getInputStream(newMeta)) {
                    jsonContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                ZipEntry oldMeta = zip.getEntry("meta.json");
                if (oldMeta != null) {
                    try (InputStream in = zip.getInputStream(oldMeta)) {
                        jsonContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Throwable t) {
            fail(ctx, 500, "读取 metadata 失败: " + t.getMessage());
            return;
        }

        if (jsonContent == null) {
            notFound(ctx, "ZIP 内不存在 metadata.json 或 meta.json");
            return;
        }

        ctx.status(200);
        ctx.contentType("application/json; charset=utf-8");
        ctx.result(jsonContent);
    }

    // ========== 旧路由 — 返回空 / 不支持 ==========

    private void legacyListEmpty(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;
        ok(ctx, Map.of(
                "mode", "continuous-recording",
                "message", "旧 segment 模式已废弃，请使用 /api/replays/players 和 /api/replays/archives",
                "items", Collections.emptyList()
        ));
    }

    private void legacyDetailEmpty(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;
        fail(ctx, 410, "旧 segment 路由已废弃，请使用新路由");
    }

    private void legacyDeleteEmpty(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.CASES_VERDICT)) return;
        fail(ctx, 410, "旧 segment 路由已废弃");
    }

    // ========== 工具方法 ==========

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 读取新格式 ZIP (schemaVersion 2/3 且 hasVideo=true) 的 metadata.json。
     * v3 在 v2 基础上新增 video.cameraSegments / video.showsHeldItem / telemetry / coverage 字段，
     * 读取侧保持向后兼容（旧字段不变，新字段缺失时忽略）。
     * 不匹配或失败时返回 null。
     * 使用 ZipFile 单条目读取（不解压全部）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readNewMetadata(File zipFile) {
        if (zipFile == null || !zipFile.exists()) return null;
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry("metadata.json");
            if (entry == null) return null;
            String json;
            try (InputStream in = zip.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Object parsed = JsonMapper.get().fromJson(json, Object.class);
            if (!(parsed instanceof Map)) return null;
            Map<String, Object> m = (Map<String, Object>) parsed;
            Object schema = m.get("schemaVersion");
            Object hasVideo = m.get("hasVideo");
            boolean schemaOk = schema instanceof Number
                    && (((Number) schema).intValue() == 2 || ((Number) schema).intValue() == 3);
            boolean videoOk = Boolean.TRUE.equals(hasVideo) || "true".equals(String.valueOf(hasVideo));
            if (schemaOk && videoOk) {
                return m;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringOrNull(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    /**
     * Gzip 压缩 JSON 输出。大 payload 如 session/必须做 gzip，避免 OOM。
     */
    private void writeGzipJson(Context ctx, Object obj) {
        try {
            String json = JsonMapper.toJson(obj);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(json.length() / 2);
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
            }

            ctx.status(200);
            ctx.contentType("application/json; charset=utf-8");
            ctx.header("Content-Encoding", "gzip");
            ctx.header("Content-Length", String.valueOf(baos.size()));
            ctx.result(baos.toByteArray());
        } catch (Throwable t) {
            fail(ctx, 500, "Gzip JSON 输出失败: " + t.getMessage());
        }
    }
}
