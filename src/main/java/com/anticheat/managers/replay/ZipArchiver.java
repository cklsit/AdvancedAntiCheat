package com.anticheat.managers.replay;

import com.google.gson.Gson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 下线存档工具：将玩家 session 的完整轨迹 + 违规标记写入 ZIP。
 * <p>
 * ZIP 内部包含两个条目：
 * <ul>
 *     <li>{@code replay.json} — 完整轨迹 JSON（大文件）</li>
 *     <li>{@code meta.json} — 仅含元数据（uuid, playerName, startTime, endTime, violationCount, sizeBytes），
 *         Web 端扫描历史存档时只需解压此条目即可</li>
 * </ul>
 * <p>
 * 清理策略：超过 {@link #MAX_ARCHIVE_AGE_MS}（默认 7 天）的 ZIP 会被删除。
 */
public final class ZipArchiver {

    /** ZIP 内完整轨迹文件名 */
    public static final String ENTRY_REPLAY_JSON = "replay.json";
    /** ZIP 内元数据文件名 */
    public static final String ENTRY_META_JSON = "meta.json";

    /** 默认保留 7 天（毫秒） */
    private static final long DEFAULT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;

    /** 内部 meta.json 格式 */
    public static class Meta {
        public String uuid;
        public String playerName;
        public long startTime;
        public long endTime;
        public long durationMs;
        public int violationCount;
        public long sizeBytes;
    }

    public static class SessionJson {
        public String uuid;
        public String playerName;
        public long startTime;
        public long endTime;
        public List<TracePoint> points;
        /** 相对于 startTime 的毫秒时间戳 */
        public List<ViolationExport> violations;
    }

    public static class ViolationExport {
        public long t;      // 相对于 startTime 的时间偏移（毫秒）
        public String type;
        public String level;

        public ViolationExport(long t, String type, String level) {
            this.t = t;
            this.type = type;
            this.level = level;
        }
    }

    private ZipArchiver() {
    }

    /**
     * 保存一个玩家 session 到视频目录。
     *
     * @param playerName 玩家名字
     * @param uuid       玩家 UUID
     * @param startTime  session 起点 epoch ms
     * @param endTime    session 终点 epoch ms
     * @param points     轨迹点列表（有序，最旧在前）
     * @param markers    违规标记列表（pointIndex 对应 points 的下标）
     * @param videoDir   视频目录（通常是 plugin.getDataFolder()/video）
     * @param logger     日志器（可选，null 时静默）
     * @param gson       Gson 实例（可选，null 时新建）
     * @return 生成的 ZIP 文件；写入失败返回 null
     */
    public static File saveToVideoDir(String playerName, UUID uuid,
                                      long startTime, long endTime,
                                      List<TracePoint> points,
                                      List<ViolationMarker> markers,
                                      File videoDir,
                                      Logger logger,
                                      Gson gson) {
        if (points == null) points = Collections.emptyList();
        if (markers == null) markers = Collections.emptyList();

        // 准备目录
        if (videoDir == null) return null;
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }

        // 文件名：{playerName}_{yyyyMMdd_HHmmss}.zip
        String safeName = playerName == null ? uuid.toString() : playerName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File zipFile = new File(videoDir, safeName + "_" + ts + ".zip");

        Gson g = gson != null ? gson : new Gson();

        try (FileOutputStream fos = new FileOutputStream(zipFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            // 1) replay.json — 完整轨迹
            SessionJson sj = new SessionJson();
            sj.uuid = uuid == null ? null : uuid.toString();
            sj.playerName = playerName;
            sj.startTime = startTime;
            sj.endTime = endTime;
            sj.points = points;

            // 将 ViolationMarker.pointIndex 转为 timeOffsetMs
            List<ViolationExport> violations = new ArrayList<>(markers.size());
            for (ViolationMarker m : markers) {
                if (m == null) continue;
                long t = 0L;
                if (m.pointIndex >= 0 && m.pointIndex < points.size()) {
                    TracePoint tp = points.get(m.pointIndex);
                    if (tp != null) t = tp.getTimeOffsetMs();
                }
                violations.add(new ViolationExport(t, m.type, m.level));
            }
            sj.violations = violations;

            String replayJson = g.toJson(sj);

            zos.putNextEntry(new ZipEntry(ENTRY_REPLAY_JSON));
            zos.write(replayJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2) meta.json — 元数据
            Meta meta = new Meta();
            meta.uuid = sj.uuid;
            meta.playerName = playerName;
            meta.startTime = startTime;
            meta.endTime = endTime;
            meta.durationMs = endTime - startTime;
            meta.violationCount = violations.size();
            meta.sizeBytes = replayJson.length(); // 近似

            zos.putNextEntry(new ZipEntry(ENTRY_META_JSON));
            zos.write(g.toJson(meta).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();

            // 定期清理
            cleanupExpired(videoDir, logger);

            return zipFile;

        } catch (Throwable t) {
            if (logger != null) {
                logger.warning("[Replay] ZipArchiver 写入失败: " + t.getMessage());
            }
            try { zipFile.delete(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /**
     * 从一个 ZIP 中读取 meta.json（只读元数据，不解压 replay.json）。
     * 用于 Web 端快速列历史存档。
     *
     * @param zipFile ZIP 文件
     * @param gson    Gson 实例
     * @return Meta；失败返回 null
     */
    public static Meta readMeta(File zipFile, Gson gson) {
        if (zipFile == null || !zipFile.exists()) return null;
        Gson g = gson != null ? gson : new Gson();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (ENTRY_META_JSON.equals(entry.getName())) {
                    String json = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    return g.fromJson(json, Meta.class);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 删除超过 {@link #DEFAULT_RETENTION_MS} 天的 ZIP 存档。
     */
    public static void cleanupExpired(File videoDir, Logger logger) {
        cleanupExpired(videoDir, DEFAULT_RETENTION_MS, logger);
    }

    public static void cleanupExpired(File videoDir, long retentionMs, Logger logger) {
        if (videoDir == null || !videoDir.exists() || retentionMs <= 0) return;
        long now = System.currentTimeMillis();
        int removed = 0;
        File[] files = videoDir.listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null) return;
        for (File f : files) {
            if (now - f.lastModified() > retentionMs) {
                if (f.delete()) removed++;
            }
        }
        if (removed > 0 && logger != null) {
            logger.info("[Replay] ZipArchiver 清理过期存档 " + removed + " 个");
        }
    }

    /**
     * 计算某个 ZIP 文件的合法路径（防止路径遍历）。
     * 若文件名包含 {@code ..} 或绝对路径字符，返回 null。
     */
    public static File safeResolve(File videoDir, String filename) {
        if (videoDir == null || filename == null) return null;
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) return null;
        File resolved = new File(videoDir, filename);
        // 额外安全：确保 resolved 在 videoDir 之下
        try {
            if (!resolved.getCanonicalPath().startsWith(videoDir.getCanonicalPath())) return null;
        } catch (IOException e) {
            return null;
        }
        return resolved;
    }
}
