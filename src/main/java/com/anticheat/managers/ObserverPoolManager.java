package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ffmpeg.FfmpegManager;
import com.anticheat.replay.CameraBinder;
import com.anticheat.web.util.JsonMapper;
import com.anticheat.web.ws.replay.ReplayBroadcaster;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 观察者池调度器。
 * <p>
 * 功能：
 * <ul>
 *   <li>管理 3 个 observer 账号（ReplayObserver_1/2/3）的 READY/BUSY 状态</li>
 *   <li>acquire(targetUuid)：按需分配空闲 observer，启动跟随 + FFmpeg HLS 录制</li>
 *   <li>订阅者计数：incrementSubscriber / decrementSubscriber，归零自动 release</li>
 *   <li>错误重试：startStream 失败时 10s 后自动重试（仍有订阅者时）</li>
 *   <li>release：停止 FFmpeg（生成 mp4）、停止跟随、归还 observer 到 READY</li>
 *   <li>statusSnapshot()：调试 JSON 快照</li>
 *   <li>shutdown()：强制释放所有 observer、cancel 重试任务</li>
 * </ul>
 */
public class ObserverPoolManager {

    // ======================= 内部 Observer =======================

    /**
     * 单个 observer 描述。
     * status: READY / BUSY
     * busyTarget: BUSY 时绑定的目标玩家 UUID
     */
    public static class Observer {
        public final int id;
        public final String name;
        public final String baseUrl;
        public volatile String status;
        public volatile UUID busyTarget;
        /** startStream 返回的 session id（当前实现即 targetUuid.toString()） */
        public volatile String streamSessionId;
        /** startFollow 成功后的 wallClock（跟随开始的时间戳 ms） */
        public volatile long followStartTimeMs;

        Observer(int id, String name, String baseUrl) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.status = "READY";
            this.busyTarget = null;
            this.streamSessionId = null;
            this.followStartTimeMs = 0;
        }
    }

    // ======================= Manager 字段 =======================

    private static final long RETRY_DELAY_TICKS = 200L; // 10s

    private final AdvancedAntiCheat plugin;
    private final Logger logger;
    private final List<Observer> observers;
    /** key = targetUuid，value = 分配到的 observer */
    private final ConcurrentHashMap<UUID, Observer> busyObservers;
    /** key = targetUuid，value = 当前订阅者数（管理员 WS 连接数） */
    private final ConcurrentHashMap<UUID, Integer> subscribersPerTarget;
    /** key = targetUuid，value = 错误重试 scheduled task（cancel 用） */
    private final ConcurrentHashMap<UUID, BukkitTask> errorRetryMap;

    public ObserverPoolManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.observers = new ArrayList<>();
        // 池大小可配：replay.surveillance.maxConcurrent（默认 3，与历史行为一致）。
        // 观察者是物理资源（docker 容器），上限受限于实际部署的 observer 容器数量。
        int poolSize = Math.max(1, plugin.getConfig().getInt("replay.surveillance.maxConcurrent", 3));
        for (int i = 1; i <= poolSize; i++) {
            String name = plugin.getConfig().getString("replay.observer." + i + ".name", "ReplayObserver_" + i);
            String url = plugin.getConfig().getString("replay.observer." + i + ".url", "http://127.0.0.1:1808" + i);
            int id = i;
            observers.add(new Observer(id, name, url));
        }
        this.busyObservers = new ConcurrentHashMap<>();
        this.subscribersPerTarget = new ConcurrentHashMap<>();
        this.errorRetryMap = new ConcurrentHashMap<>();
        logger.info("[Replay-Pool] ObserverPoolManager 已初始化，池大小=" + observers.size());
        for (Observer o : observers) {
            logger.info("[Replay-Pool]   observer#" + o.id + " name=" + o.name + " url=" + o.baseUrl);
        }
    }

    // ======================= 公开：订阅计数 =======================

    /**
     * 增加目标的订阅者数。
     * 当 count 从 0→1 时，触发 acquire 分配 observer 并启动流。
     */
    public void incrementSubscriber(UUID targetUuid) {
        if (targetUuid == null) return;
        Integer prev = subscribersPerTarget.get(targetUuid);
        subscribersPerTarget.merge(targetUuid, 1, Integer::sum);
        if (prev == null || prev == 0) {
            logger.info("[Replay-Pool] 目标首次有订阅者，触发 acquire: " + targetUuid);
            // 首次订阅：触发 acquire（分配 observer 并异步启动流）
            acquire(targetUuid);
        }
    }

    /**
     * 减少目标的订阅者数。
     * 当 count 归零（或 map 条目被移除）时，触发 release。
     */
    public void decrementSubscriber(UUID targetUuid) {
        if (targetUuid == null) return;
        Integer n = subscribersPerTarget.compute(targetUuid, (k, v) -> (v != null && v > 1) ? v - 1 : null);
        if (n == null || n == 0) {
            logger.info("[Replay-Pool] 目标订阅者归零，触发 release: " + targetUuid);
            release(targetUuid);
        }
    }

    /** 获取指定目标的订阅者数（0 表示无订阅）。 */
    public int subscriberCount(UUID targetUuid) {
        if (targetUuid == null) return 0;
        Integer n = subscribersPerTarget.get(targetUuid);
        return n == null ? 0 : n;
    }

    /** 观察者池容量（物理 observer 数量）。 */
    public int getPoolSize() {
        return observers.size();
    }

    // ======================= 摄像机绑定（spectator）辅助 =======================

    /**
     * 阻塞等待观察者账号登录到 Bukkit 服务器（在异步线程安全轮询主线程）。
     *
     * @param observerName 观察者游戏内名字
     * @param timeoutMs    最长等待毫秒
     * @return true 表示已在线
     */
    private boolean waitForObserverLogin(final String observerName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                final java.util.concurrent.atomic.AtomicBoolean online =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        Player p = Bukkit.getPlayerExact(observerName);
                        online.set(p != null && p.isOnline());
                    } finally {
                        latch.countDown();
                    }
                });
                if (latch.await(2, java.util.concurrent.TimeUnit.SECONDS) && online.get()) {
                    return true;
                }
            } catch (Throwable t) {
                logger.warning("[Replay-Pool] 等待观察者登录轮询异常: " + t.getMessage());
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    /**
     * 在主线程把观察者以旁观者模式绑定到目标（方案 A）。
     * 观察者或目标尚未在线时本次跳过——由调用方在 acquire 的成功路径中调用，
     * 观察者客户端通常在 acquire 前已登录。
     */
    private void bindCameraOnMainThread(final String observerName, final UUID targetUuid) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                CameraBinder cb = plugin.getCameraBinder();
                if (cb == null) return;
                Player observer = Bukkit.getPlayerExact(observerName);
                Player target = Bukkit.getPlayer(targetUuid);
                if (observer == null || target == null || !observer.isOnline() || !target.isOnline()) {
                    return;
                }
                cb.bind(observer, target);
            } catch (Throwable t) {
                logger.warning("[Replay-Pool] 摄像机绑定失败 observer=" + observerName
                        + ": " + t.getMessage());
            }
        });
    }

    /** 在主线程解除观察者的旁观者绑定。 */
    private void unbindCameraOnMainThread(final String observerName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                CameraBinder cb = plugin.getCameraBinder();
                if (cb == null) return;
                Player observer = Bukkit.getPlayerExact(observerName);
                if (observer != null) cb.unbind(observer);
            } catch (Throwable t) {
                logger.warning("[Replay-Pool] 摄像机解绑失败 observer=" + observerName
                        + ": " + t.getMessage());
            }
        });
    }

    // ======================= 公开：acquire / release =======================

    /**
     * 为目标玩家分配一个 READY observer，并异步启动跟随 + FFmpeg 录制。
     * 分配（找 READY observer / 标记 BUSY）是 synchronized 的；实际阻塞 HTTP 在异步线程执行，
     * 避免阻塞调用方（WS 线程或 Bukkit 主线程）。
     *
     * @return 已被标记为 BUSY 的 Observer（即使流尚未完全启动），或 null 表示无可用 observer。
     */
    public synchronized Observer acquire(final UUID targetUuid) {
        if (targetUuid == null) return null;

        // 1. 已存在绑定 → 复用
        Observer existing = busyObservers.get(targetUuid);
        if (existing != null) {
            logger.info("[Replay-Pool] acquire 复用已有 observer#" + existing.id
                    + " name=" + existing.name + " for target=" + targetUuid);
            return existing;
        }

        // 2. 找第一个 READY observer
        Observer picked = null;
        for (Observer o : observers) {
            if ("READY".equals(o.status)) {
                picked = o;
                break;
            }
        }
        // 3. 没有空闲
        if (picked == null) {
            logger.warning("[Replay-Pool] 无可用 READY observer，target=" + targetUuid
                    + " 无法分配（所有 observer 都在 BUSY）");
            // 推送：observer_error（池满）
            pushObserverEvent(targetUuid, "observer_error",
                    "暂无可用观察者，请稍后重试（所有回放画面已占用）", null);
            return null;
        }

        // 4. 标记为 BUSY 并记录
        picked.status = "BUSY";
        picked.busyTarget = targetUuid;
        busyObservers.put(targetUuid, picked);
        final Observer obs = picked;
        logger.info("[Replay-Pool] 已分配 observer#" + obs.id + " name=" + obs.name
                + " → target=" + targetUuid);

        // 取消之前可能存在的重试任务（避免重复跑）
        cancelRetryTask(targetUuid);

        // 5/6/7/8. 异步线程：连接服务器 → 等待登录 → 跟随启动 → startStream → 推送 ready/error + 重试
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean followOk = false;
            try {
                // 5. 触发 MC 客户端自动连接服务器（1.8.8 不支持 --server 参数）
                FfmpegManager.ConnectResponse cr = plugin.getFfmpegManager()
                        .connectServer(obs.id);
                if (!cr.success) {
                    logger.warning("[Replay-Pool] observer#" + obs.id
                            + " 连接服务器失败: " + cr.error);
                    rollbackObserver(obs, targetUuid, false);
                    reportObserverErrorInternal(targetUuid,
                            "observer 连接服务器失败: " + (cr.error == null ? "unknown" : cr.error)
                                    + "，10s 后自动重试");
                    return;
                }
                logger.info("[Replay-Pool] observer#" + obs.id + " 已发送连接服务器指令，等待登录...");

                // 6. 等待 observer 账号登录到 Bukkit 服务器（最长 30s）
                boolean loggedIn = waitForObserverLogin(obs.name, 30_000L);
                if (!loggedIn) {
                    logger.warning("[Replay-Pool] observer#" + obs.id + " (" + obs.name
                            + ") 未在 30s 内登录服务器");
                    rollbackObserver(obs, targetUuid, false);
                    reportObserverErrorInternal(targetUuid,
                            "observer 未在 30s 内登录服务器，10s 后自动重试");
                    return;
                }
                logger.info("[Replay-Pool] observer#" + obs.id + " (" + obs.name + ") 已登录服务器");

                // 7. 摄像机绑定（spectator，方案 A）：主线程执行，取代旧版每 tick teleport
                try {
                    bindCameraOnMainThread(obs.name, targetUuid);
                    followOk = true;
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "[Replay-Pool] 摄像机绑定调度异常 observer="
                            + obs.name + " target=" + targetUuid + ": " + t.getMessage(), t);
                    // 绑定失败记 warning，但仍尝试启动 ffmpeg（由容器侧 fallback）
                }

                // 8. FFmpeg startStream（阻塞 HTTP，35s 超时）
                FfmpegManager.StreamResponse sr = plugin.getFfmpegManager()
                        .startStream(obs.id, targetUuid);
                if (!sr.success) {
                    logger.warning("[Replay-Pool] ffmpeg startStream 失败 observer#" + obs.id
                            + " target=" + targetUuid + ": " + sr.error);
                    // 回滚
                    rollbackObserver(obs, targetUuid, followOk);
                    // 推送 error 事件 + 安排重试
                    String reason = "ffmpeg 启动失败: " + (sr.error == null ? "unknown" : sr.error)
                            + "，10s 后自动重试";
                    reportObserverErrorInternal(targetUuid, reason);
                    return;
                }

                // 记录 streamSessionId 和跟随开始时间
                obs.streamSessionId = targetUuid.toString();
                if (followOk) {
                    obs.followStartTimeMs = System.currentTimeMillis();
                } else {
                    // 跟随未明确成功，用当前时间兜底
                    obs.followStartTimeMs = System.currentTimeMillis();
                }

                logger.info("[Replay-Pool] FFmpeg 流就绪 observer#" + obs.id
                        + " pid=" + sr.ffmpegPid + " hls=" + sr.hlsUrl
                        + " streamSessionId=" + obs.streamSessionId
                        + " followStart=" + obs.followStartTimeMs);

                // 9. 推送 observer_ready：前端正式绑定 <video src=hlsUrl>
                String hlsUrl = (sr.hlsUrl != null && !sr.hlsUrl.isEmpty())
                        ? sr.hlsUrl
                        : plugin.getFfmpegManager().hlsUrl(targetUuid);
                pushObserverEvent(targetUuid, "observer_ready", "画面就绪", hlsUrl);

            } catch (Throwable t) {
                logger.log(Level.SEVERE, "[Replay-Pool] acquire 异步流程异常 observer#"
                        + obs.id + " target=" + targetUuid + ": " + t.getMessage(), t);
                rollbackObserver(obs, targetUuid, followOk);
                reportObserverErrorInternal(targetUuid,
                        "内部错误: " + t.getMessage() + "，10s 后自动重试");
            }
        });

        return obs;
    }

    /**
     * 释放指定目标占用的 observer：异步停止 FFmpeg + 停止跟随 + 归还 READY。
     * 同步部分：从 busyObservers 移除并标记；stopStream 等阻塞操作在异步线程执行。
     */
    public synchronized void release(final UUID targetUuid) {
        if (targetUuid == null) return;

        // 取消重试任务（如果有）
        cancelRetryTask(targetUuid);

        final Observer obs = busyObservers.remove(targetUuid);
        if (obs == null) {
            logger.info("[Replay-Pool] release 跳过：target=" + targetUuid + " 无绑定 observer");
            return;
        }

        logger.info("[Replay-Pool] 释放 observer#" + obs.id + " name=" + obs.name
                + " → target=" + targetUuid);

        // 异步：stopStream（阻塞 35s）、stopFollow、zip 打包、重置 observer 状态
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String mp4Path = null;
            try {
                // 3. 停止 ffmpeg，等待 mp4 合成（最长 35s）
                FfmpegManager.StopResponse sp = plugin.getFfmpegManager()
                        .stopStream(obs.id, targetUuid);
                if (!sp.success) {
                    logger.warning("[Replay-Pool] stopStream 失败 observer#" + obs.id
                            + " target=" + targetUuid + ": " + sp.error);
                } else {
                    mp4Path = sp.mp4FileAbsolutePath;
                    logger.info("[Replay-Pool] mp4 合成完成: " + mp4Path);
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[Replay-Pool] stopStream 异常 observer#" + obs.id
                        + ": " + t.getMessage(), t);
            }

            // 4. 解除摄像机绑定（spectator → 解除旁观目标）
            try {
                unbindCameraOnMainThread(obs.name);
            } catch (Throwable t) {
                logger.warning("[Replay-Pool] 摄像机解绑失败 observer=" + obs.name
                        + ": " + t.getMessage());
            }

            // 5. 打包 zip（真正实现：HLS concat → metadata.json → ZIP 写入 video/ 目录）
            try {
                // 获取玩家名（优先 ReplayRecorder，其次 Bukkit，最后 fallback UUID）
                String playerName = null;
                ReplayRecorder rr = plugin.getReplayRecorder();
                if (rr != null) {
                    Map<UUID, String> nameMap = rr.getPlayerNames();
                    if (nameMap != null) playerName = nameMap.get(targetUuid);
                }
                if (playerName == null) {
                    try {
                        Player p = Bukkit.getPlayer(targetUuid);
                        if (p != null) playerName = p.getName();
                    } catch (Throwable ignored) {
                    }
                }
                if (playerName == null || playerName.isEmpty()) {
                    playerName = targetUuid.toString();
                }
                long endTimeMs = System.currentTimeMillis();
                long startTimeMs = (obs.followStartTimeMs > 0)
                        ? obs.followStartTimeMs
                        : endTimeMs - 60_000L; // 兜底 1 分钟前
                zipVideoSession(obs, targetUuid, playerName, startTimeMs, endTimeMs);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[Replay-Pool] zipVideoSession 异常 observer#" + obs.id
                        + " target=" + targetUuid + ": " + t.getMessage(), t);
            }

            // 6. 归还 observer 到 READY（重置新字段）
            obs.busyTarget = null;
            obs.streamSessionId = null;
            obs.followStartTimeMs = 0;
            obs.status = "READY";
            logger.info("[Replay-Pool] observer#" + obs.id + " 已归还为 READY");
        });
    }

    // ======================= 公开：错误上报 / 调试 / 关闭 =======================

    /**
     * 外部（如 Ffmpeg/跟随健康检查）上报 observer 运行错误。
     * 取消现有重试 → 推送 WS observer_error → 新的 10s 后重试（仅当订阅者>0）。
     */
    public void reportObserverError(UUID targetUuid, String reason) {
        reportObserverErrorInternal(targetUuid, reason);
    }

    /**
     * 调试接口：返回完整状态快照（JSON 序列化友好）。
     * 包含 observers 列表、subscribersPerTarget、busyInfo（含 ffmpeg status）。
     */
    public Map<String, Object> statusSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. observers 列表
        List<Map<String, Object>> obsList = new ArrayList<>();
        for (Observer o : observers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id);
            m.put("name", o.name);
            m.put("baseUrl", o.baseUrl);
            m.put("status", o.status);
            UUID bt = o.busyTarget;
            m.put("busyTarget", bt == null ? null : bt.toString());
            String playerName = null;
            if (bt != null) {
                try {
                    Player p = Bukkit.getPlayer(bt);
                    playerName = (p != null) ? p.getName() : null;
                } catch (Throwable ignored) {
                }
            }
            m.put("busyTargetPlayerName", playerName);
            obsList.add(m);
        }
        result.put("observers", obsList);

        // 2. subscribersPerTarget
        Map<String, Integer> subs = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> e : subscribersPerTarget.entrySet()) {
            subs.put(e.getKey().toString(), e.getValue());
        }
        result.put("subscribersPerTarget", subs);

        // 3. busyInfo：target → {observerName, ffmpegPid, ffmpegOk}
        //    注意 status 是阻塞 HTTP，这里异步获取可能慢，所以用 runTaskAsynchronously 的结果会麻烦；
        //    这里直接在当前线程调用，健康检查接受最多 10s 阻塞（调试接口调用频率低）。
        Map<String, Object> busyInfo = new LinkedHashMap<>();
        for (Map.Entry<UUID, Observer> e : busyObservers.entrySet()) {
            UUID tid = e.getKey();
            Observer o = e.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("observerId", o.id);
            info.put("observerName", o.name);
            FfmpegManager.StatusResponse st = plugin.getFfmpegManager().status(o.id);
            info.put("ffmpegOk", st.success);
            info.put("ffmpegPid", st.ffmpegPid);
            info.put("mcStatus", st.mcStatus);
            info.put("xvfb", st.xvfb);
            info.put("statusError", st.error);
            busyInfo.put(tid.toString(), info);
        }
        result.put("busyInfo", busyInfo);

        // 4. retryMap
        List<String> retryTargets = new ArrayList<>();
        for (UUID tid : errorRetryMap.keySet()) {
            retryTargets.add(tid.toString());
        }
        result.put("pendingRetryTargets", retryTargets);

        return result;
    }

    /**
     * 插件关闭时：对所有目标强制 release（简化版：只归还 observer，不跑 zip），并 cancel 所有重试。
     */
    public synchronized void shutdown() {
        logger.info("[Replay-Pool] 执行 shutdown：清理所有 observer 与重试任务");

        // cancel 所有重试 task
        for (BukkitTask t : errorRetryMap.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        errorRetryMap.clear();

        // 对每个 busyObservers 条目：强制停止跟随 + stopStream + 归还 READY
        List<Map.Entry<UUID, Observer>> snapshot = new ArrayList<>(busyObservers.entrySet());
        for (Map.Entry<UUID, Observer> e : snapshot) {
            final UUID tid = e.getKey();
            final Observer obs = e.getValue();
            // 同步状态重置
            busyObservers.remove(tid);
            subscribersPerTarget.remove(tid);

            // 异步 stopStream（35s 阻塞不能在主线程）
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getFfmpegManager().stopStream(obs.id, tid);
                } catch (Throwable ignored) {
                }
                try {
                    unbindCameraOnMainThread(obs.name);
                } catch (Throwable ignored) {
                }
                obs.busyTarget = null;
                obs.status = "READY";
            });
        }

        logger.info("[Replay-Pool] shutdown 完成");
    }

    // ======================= 内部：回滚 / 重试 / 事件推送 / ZIP 打包 =======================

    /**
     * acquire 异步流程失败时的回滚。
     * 需要同步保护对 busyObservers / observer 状态的修改。
     */
    private synchronized void rollbackObserver(Observer obs, UUID targetUuid, boolean followStarted) {
        // 确认此 observer 仍然绑定的是同一个 target（避免被其他流程抢先改掉）
        Observer current = busyObservers.get(targetUuid);
        if (current != obs) {
            logger.info("[Replay-Pool] rollback 跳过：observer#" + obs.id
                    + " 已被其他 target 占用或已释放");
            return;
        }
        busyObservers.remove(targetUuid);
        if (followStarted) {
            try {
                unbindCameraOnMainThread(obs.name);
            } catch (Throwable ignored) {
            }
        }
        obs.busyTarget = null;
        obs.streamSessionId = null;
        obs.followStartTimeMs = 0;
        obs.status = "READY";
        logger.info("[Replay-Pool] rollback 完成：observer#" + obs.id + " 已归还 READY");
    }

    /**
     * 文件名清理：只保留字母、数字、下划线。
     */
    private static String sanitizeForFilename(String s) {
        if (s == null) return "player";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String r = sb.toString().replaceAll("_+", "_");
        if (r.startsWith("_")) r = r.substring(1);
        if (r.endsWith("_")) r = r.substring(0, r.length() - 1);
        return r.isEmpty() ? "player" : r;
    }

    /**
     * 取 UUID short8：去掉横线后前 8 字符。
     */
    private static String shortUuid8(UUID uuid) {
        if (uuid == null) return "unknown";
        String full = uuid.toString().replace("-", "");
        return full.length() >= 8 ? full.substring(0, 8) : full;
    }

    /**
     * 异步打包：ffmpeg concat → metadata → ZIP 写入 plugins/AdvancedAntiCheat/video/*.zip
     * 调用方必须在异步线程执行（本方法内部会做 IO）。
     *
     * @param obs          观察者（含 streamSessionId/baseUrl 等）
     * @param targetUuid   被跟随的玩家 UUID
     * @param playerName   玩家名字（文件名美化用）
     * @param startTimeMs  跟随开始的 wallClock ms
     * @param endTimeMs    当前的 wallClock ms
     */
    public void zipVideoSession(Observer obs, UUID targetUuid, String playerName,
                                long startTimeMs, long endTimeMs) {
        if (obs == null || targetUuid == null) {
            logger.warning("[Replay-Pool][Zip] 参数为空，跳过打包");
            return;
        }
        String sessionId = obs.streamSessionId != null ? obs.streamSessionId : targetUuid.toString();
        if (sessionId.isEmpty()) {
            logger.warning("[Replay-Pool][Zip] streamSessionId 为空，跳过打包 target=" + targetUuid);
            return;
        }

        // 1. 生成文件名前缀：{yyyyMMdd_HHmm}_{sanitize(playerName)}_{short8}
        String tsPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String safeName = sanitizeForFilename(playerName);
        String uuid8 = shortUuid8(targetUuid);
        String filePrefix = tsPrefix + "_" + safeName + "_" + uuid8;
        String zipName = filePrefix + ".zip";
        String mp4OutFilename = filePrefix + ".mp4";

        // 2. concat mp4（同步，已在异步线程中）
        FfmpegManager.ConcatResult concat = null;
        try {
            concat = plugin.getFfmpegManager().concatVideo(obs.id, sessionId, mp4OutFilename);
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Replay-Pool][Zip] concat IO 异常: " + e.getMessage(), e);
            concat = new FfmpegManager.ConcatResult(false, null, 0, 0, "IO: " + e.getMessage());
        }
        if (concat == null || !concat.isOk()) {
            String err = (concat != null && concat.getError() != null) ? concat.getError() : "unknown";
            logger.warning("[Replay-Pool][Zip] concatVideo 失败，不生成 ZIP: " + err);
            return;
        }
        logger.info("[Replay-Pool][Zip] concat 完成: " + concat.getMp4Path()
                + " size=" + concat.getSizeBytes() + " duration=" + concat.getDurationSec() + "s");

        // 3. 构造 metadata.json
        Map<String, Object> metadata = new LinkedHashMap<>();
        // v3：单一归档（视频 + 遥测合一），含 cameraSegments / coverage 等取证诚实性字段
        metadata.put("schemaVersion", 3);
        metadata.put("hasVideo", true);
        metadata.put("playerUuid", targetUuid.toString());
        metadata.put("playerName", playerName != null ? playerName : targetUuid.toString());
        metadata.put("startTimeMs", startTimeMs);
        metadata.put("endTimeMs", endTimeMs);
        metadata.put("durationMs", Math.max(0L, endTimeMs - startTimeMs));

        // ---- video 段（v3）----
        com.anticheat.replay.CameraBinder camBinder = plugin.getCameraBinder();
        List<Map<String, Object>> cameraSegments = (camBinder != null)
                ? camBinder.getCameraSegments(targetUuid)
                : Collections.emptyList();
        boolean showsHeldItem = camBinder != null && camBinder.recordedHeldItem(targetUuid);

        Map<String, Object> videoMeta = new LinkedHashMap<>();
        videoMeta.put("file", "video.mp4");
        videoMeta.put("durationSec", concat.getDurationSec());
        videoMeta.put("sizeBytes", concat.getSizeBytes());
        videoMeta.put("observerId", obs.id);
        // 视频首帧相对 startTimeMs 的偏移：回放时用于对齐遥测与画面
        videoMeta.put("offsetMs", 0);
        // 按时间段记录机位：ATTACH 段画面不含手持物品（原生 MC 限制），必须如实标注
        videoMeta.put("cameraSegments", cameraSegments);
        videoMeta.put("showsHeldItem", showsHeldItem);
        metadata.put("video", videoMeta);

        // 兼容旧字段（v2 消费方仍读这些键，保留避免破坏既有面板）
        metadata.put("videoFilename", mp4OutFilename);
        metadata.put("videoDurationSec", concat.getDurationSec());
        metadata.put("videoSizeBytes", concat.getSizeBytes());
        metadata.put("observerId", obs.id);
        metadata.put("observerName", obs.name != null ? obs.name : ("observer_" + obs.id));

        // violations: 从 ReplayRecorder 取
        List<Map<String, Object>> violationsList = new ArrayList<>();
        ReplayRecorder rr = plugin.getReplayRecorder();
        if (rr != null) {
            try {
                List<com.anticheat.managers.replay.ViolationMarker> markers = rr.getViolationMarkers(targetUuid);
                if (markers != null && !markers.isEmpty()) {
                    // 用 trace point 取时间偏移
                    com.anticheat.managers.replay.RingBuffer<com.anticheat.managers.replay.TracePoint> buf = rr.getSession(targetUuid);
                    List<com.anticheat.managers.replay.TracePoint> snap = buf != null ? buf.snapshot() : Collections.emptyList();
                    long startEpoch = startTimeMs;
                    Long recEpoch = rr.getSessionEpoch(targetUuid);
                    if (recEpoch != null) startEpoch = recEpoch;
                    for (com.anticheat.managers.replay.ViolationMarker m : markers) {
                        if (m == null) continue;
                        long tMs = 0L;
                        if (snap != null && !snap.isEmpty()) {
                            int idx = Math.max(0, Math.min(m.getPointIndex(), snap.size() - 1));
                            com.anticheat.managers.replay.TracePoint tp = snap.get(idx);
                            if (tp != null) {
                                // 转换成相对于 wallClock start 的绝对 ms
                                long relMs = tp.getTimeOffsetMs();
                                tMs = relMs; // 这里用相对 startEpoch，但我们在 violations 中存 startTimeMs + relMs
                                tMs = Math.max(0L, (startEpoch + relMs) - startTimeMs);
                            }
                        }
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("tMs", tMs);
                        v.put("type", m.getType() != null ? m.getType() : "UNKNOWN");
                        v.put("level", m.getLevel() != null ? m.getLevel() : "");
                        violationsList.add(v);
                    }
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[Replay-Pool][Zip] 取 violations 失败（返回空数组）: " + t.getMessage());
            }
        } else {
            logger.warning("[Replay-Pool][Zip] ReplayRecorder 未注入，violations 留空数组");
        }
        metadata.put("violations", violationsList);

        // hudSampled5Hz：HUD 帧采样（每 200ms 一条，t = 相对视频起点 ms）
        // pointsSampled2Hz：位置轨迹采样（每 500ms 一条）
        // 数据源：ReplayRecorder 的 TracePoint 环形缓冲（20Hz，含 w/h/f/a/lvl/xp/hs/inv HUD 字段）
        List<Map<String, Object>> hudSamples = new ArrayList<>();
        List<Map<String, Object>> pointSamples = new ArrayList<>();
        // v3：全量 20Hz 遥测（telemetry.jsonl，JSON Lines，可流式读写）
        StringBuilder telemetryJsonl = new StringBuilder();
        int telemetryFrames = 0;
        if (rr != null) {
            try {
                com.anticheat.managers.replay.RingBuffer<com.anticheat.managers.replay.TracePoint> hudBuf =
                        rr.getSession(targetUuid);
                List<com.anticheat.managers.replay.TracePoint> hudSnap =
                        hudBuf != null ? hudBuf.snapshot() : null;
                if (hudSnap != null && !hudSnap.isEmpty()) {
                    Long recEpoch = rr.getSessionEpoch(targetUuid);
                    long videoDurationMs = Math.max(0L, endTimeMs - startTimeMs);
                    long lastHudT = Long.MIN_VALUE;
                    long lastPtT = Long.MIN_VALUE;
                    for (com.anticheat.managers.replay.TracePoint tp : hudSnap) {
                        if (tp == null) continue;
                        // 相对视频起点的时间：优先 wallClock（w 字段），退化用 sessionEpoch + timeOffsetMs
                        long absMs = tp.getWallClockMs();
                        if (absMs <= 0 && recEpoch != null) absMs = recEpoch + tp.getTimeOffsetMs();
                        if (absMs <= 0) continue;
                        long tMs = absMs - startTimeMs;
                        // 视频窗口过滤（前后各 500ms 容差）
                        if (tMs < -500 || tMs > videoDurationMs + 500) continue;
                        long tClamped = Math.max(0L, tMs);

                        // 全量 20Hz 遥测：一行一帧（与 WS hud 帧同构，前端可零成本复用）
                        try {
                            telemetryJsonl.append(JsonMapper.toJson(
                                    com.anticheat.web.ws.replay.HudFrameBuilder.build(tp, tClamped)));
                            telemetryJsonl.append('\n');
                            telemetryFrames++;
                        } catch (Throwable ignored) {
                            // 单帧序列化失败不影响其余帧
                        }

                        // HUD 5Hz 采样
                        if (tClamped - lastHudT >= 200) {
                            lastHudT = tClamped;
                            Map<String, Object> hm = new LinkedHashMap<>();
                            hm.put("t", tClamped);
                            hm.put("h", tp.getHealthHalf());
                            hm.put("f", tp.getHunger());
                            hm.put("a", tp.getArmorHalf());
                            hm.put("lvl", tp.getExpLevel());
                            hm.put("xp", tp.getExpPercent());
                            hm.put("hs", tp.getHotbarSlot());
                            hm.put("inv", tp.getInventoryHotbar()); // 稀疏：null=沿用最近非空帧
                            hudSamples.add(hm);
                        }
                        // 位置 2Hz 采样
                        if (tClamped - lastPtT >= 500) {
                            lastPtT = tClamped;
                            Map<String, Object> pm = new LinkedHashMap<>();
                            pm.put("t", tClamped);
                            pm.put("x", tp.getX());
                            pm.put("y", tp.getY());
                            pm.put("z", tp.getZ());
                            pm.put("yaw", tp.getYaw());
                            pm.put("pitch", tp.getPitch());
                            pm.put("m", tp.getMainHandItemId());
                            pointSamples.add(pm);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warning("[Replay-Pool][Zip] HUD/轨迹采样失败（保留已采部分）: " + t.getMessage());
            }
        }
        metadata.put("hudSampled5Hz", hudSamples);
        metadata.put("pointsSampled2Hz", pointSamples);

        // ---- telemetry 段（v3）：全量 20Hz 遥测落 telemetry.jsonl ----
        Map<String, Object> telemetryMeta = new LinkedHashMap<>();
        telemetryMeta.put("file", "telemetry.jsonl");
        telemetryMeta.put("rateHz", 20);
        telemetryMeta.put("frames", telemetryFrames);
        telemetryMeta.put("compressed", false);
        metadata.put("telemetry", telemetryMeta);

        // ---- coverage 段（v3）----
        // 取证诚实性字段：若该玩家因队列溢出/观察者故障而没录到视频，归档里必须写明，
        // 避免管理员误以为"没录到＝没作弊"。走到这里视频已合成，记 recorded=true。
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("recorded", true);
        coverage.put("reason", null);
        metadata.put("coverage", coverage);

        logger.info("[Replay-Pool][Zip] 采样完成: hud=" + hudSamples.size() + " 条(5Hz), points="
                + pointSamples.size() + " 条(2Hz), telemetry=" + telemetryFrames + " 帧(20Hz)"
                + ", cameraSegments=" + cameraSegments.size() + ", showsHeldItem=" + showsHeldItem);

        // 4. 写 ZIP
        File videoDir = new File(plugin.getDataFolder(), "video");
        if (!videoDir.exists()) {
            if (!videoDir.mkdirs()) {
                logger.warning("[Replay-Pool][Zip] video 目录创建失败: " + videoDir.getAbsolutePath());
                return;
            }
        }

        // hostHlsRoot: 从 config.yml replay.hlsHostRoot 读，fallback 到 plugin data folder + hls
        String hostHlsRootCfg = plugin.getConfig().getString("replay.hlsHostRoot", "").trim();
        File hostHlsRoot;
        if (!hostHlsRootCfg.isEmpty()) {
            hostHlsRoot = new File(hostHlsRootCfg);
        } else {
            hostHlsRoot = new File(plugin.getDataFolder(), "hls");
        }
        // 容器路径 /hls/archive/xxx.mp4 对应宿主机 ${hostHlsRoot}/archive/xxx.mp4
        File hostMp4File = new File(new File(hostHlsRoot, "archive"), mp4OutFilename);
        if (!hostMp4File.exists()) {
            // 尝试用 FfmpegManager 的 hlsRootDir（容器 volume 挂载）
            FfmpegManager fm = plugin.getFfmpegManager();
            if (fm != null) {
                File alt = new File(new File(fm.getHlsRootDir(), "archive"), mp4OutFilename);
                if (alt.exists()) {
                    hostMp4File = alt;
                }
            }
        }
        if (!hostMp4File.exists()) {
            logger.warning("[Replay-Pool][Zip] 宿主机 mp4 文件不存在: " + hostMp4File.getAbsolutePath()
                    + "（期望 concat 写入的文件），无法打包 ZIP");
            return;
        }

        File zipFile = new File(videoDir, zipName);
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            // a) video.mp4：从宿主机 mp4 文件读取写入 ZIP（4KB 缓冲）
            zos.putNextEntry(new ZipEntry("video.mp4"));
            Path mp4Path = Paths.get(hostMp4File.toURI());
            try (InputStream in = new BufferedInputStream(Files.newInputStream(mp4Path))) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    zos.write(buffer, 0, n);
                }
            }
            zos.closeEntry();

            // b) metadata.json
            String metaJson;
            try {
                metaJson = JsonMapper.toJson(metadata);
            } catch (Throwable t) {
                logger.warning("[Replay-Pool][Zip] JsonMapper 序列化失败，fallback toString: " + t.getMessage());
                metaJson = metadata.toString();
            }
            zos.putNextEntry(new ZipEntry("metadata.json"));
            zos.write(metaJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // c) telemetry.jsonl：全量 20Hz 遥测（JSON Lines，v3 单一归档）
            zos.putNextEntry(new ZipEntry("telemetry.jsonl"));
            zos.write(telemetryJsonl.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[Replay-Pool][Zip] 写入 ZIP 失败: " + t.getMessage(), t);
            try { zipFile.delete(); } catch (Throwable ignored) {}
            return;
        }

        long zipSize = zipFile.length();
        logger.info("[Replay-Pool][Zip] ZIP 存档生成: " + zipFile.getAbsolutePath()
                + " (" + zipSize + " bytes), violations=" + violationsList.size()
                + ", mp4=" + concat.getSizeBytes() + " bytes, duration=" + concat.getDurationSec() + "s");

        // 清理中间产物：HLS 切片目录 + archive mp4（内容已完整打入 ZIP）
        // 失败仅记日志，不影响存档（FfmpegManager 的 24h GC 兜底）
        try {
            File hlsSessionDir = new File(hostHlsRoot, sessionId);
            if (hlsSessionDir.isDirectory() && deleteRecursively(hlsSessionDir.toPath())) {
                logger.info("[Replay-Pool][Zip] 已清理 HLS 切片目录: " + hlsSessionDir.getAbsolutePath());
            }
            File archiveMp4 = new File(new File(hostHlsRoot, "archive"), mp4OutFilename);
            if (archiveMp4.exists() && archiveMp4.delete()) {
                logger.info("[Replay-Pool][Zip] 已清理中间 mp4: " + archiveMp4.getAbsolutePath());
            }
        } catch (Throwable t) {
            logger.warning("[Replay-Pool][Zip] 清理中间文件失败（由 24h GC 兜底）: " + t.getMessage());
        }
    }

    /** 递归删除目录（打包完成后清理 HLS 中间产物用）。 */
    private static boolean deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return false;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
        return !Files.exists(root);
    }

    /**
     * 内部错误上报 + 安排重试（acquire 失败 / reportObserverError 公开 API 都走这里）。
     */
    private void reportObserverErrorInternal(UUID targetUuid, String reason) {
        // cancel 旧的重试 task
        cancelRetryTask(targetUuid);

        // 推送 WS error 事件
        pushObserverEvent(targetUuid, "observer_error", reason, null);

        // 安排 10s 后重试（异步延迟 task）
        BukkitTask retryTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // 执行前：判断订阅者>0，否则直接取消不浪费资源
                    int cnt = subscriberCount(targetUuid);
                    if (cnt <= 0) {
                        logger.info("[Replay-Pool] 重试跳过：target=" + targetUuid
                                + " 已无订阅者（cnt=" + cnt + "）");
                        errorRetryMap.remove(targetUuid, this);
                        return;
                    }
                    logger.info("[Replay-Pool] 执行重试 acquire for target=" + targetUuid
                            + " subscribers=" + cnt);
                    acquire(targetUuid);
                } finally {
                    errorRetryMap.remove(targetUuid, this);
                }
            }
        }.runTaskLaterAsynchronously(plugin, RETRY_DELAY_TICKS);

        errorRetryMap.put(targetUuid, retryTask);
        logger.info("[Replay-Pool] 已安排重试 target=" + targetUuid + "，延迟 200 ticks (10s)");
    }

    /** 取消指定 target 的重试任务（如果存在）。 */
    private void cancelRetryTask(UUID targetUuid) {
        if (targetUuid == null) return;
        BukkitTask old = errorRetryMap.remove(targetUuid);
        if (old != null) {
            try {
                old.cancel();
                logger.info("[Replay-Pool] 已 cancel 旧重试 task target=" + targetUuid);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 向指定 target 的所有 WS 订阅者推送 event 帧。
     *
     * @param event   observer_ready / observer_error / offline
     * @param message 人类可读提示
     * @param hlsUrl  仅 observer_ready 时非 null
     */
    private void pushObserverEvent(UUID targetUuid, String event, String message, String hlsUrl) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("event", event);
            if (message != null) data.put("message", message);
            if (hlsUrl != null) data.put("hlsUrl", hlsUrl);

            ReplayBroadcaster rb = plugin.getReplayBroadcaster();
            if (rb == null) {
                logger.fine("[Replay-Pool] ReplayBroadcaster 尚未注入，跳过 WS event 推送: " + event);
                return;
            }
            rb.broadcastEnvelope(targetUuid, "event", data);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[Replay-Pool] pushObserverEvent 异常: " + t.getMessage(), t);
        }
    }

    /**
     * Zip 打包占位：后续 Task 8 将在这里调用 ZipArchiver.asyncPackageWithVideo(...)
     * 组装 mp4 + 玩家名 + epoch + violations markers 生成最终 zip 归档。
     */
    @SuppressWarnings("unused")
    private void asyncPackageWithVideo(UUID targetUuid, String mp4AbsolutePath) {
        // TODO(Task 8): 调用 ZipArchiver.asyncPackageWithVideo(targetUuid, mp4AbsolutePath, markers, playerName, epochMs)
        // 目前仅占位，保证编译通过。
        if (mp4AbsolutePath != null) {
            logger.info("[Replay-Pool][Zip-Placeholder] mp4 已生成，等待打包: " + mp4AbsolutePath
                    + " target=" + targetUuid);
        }
    }
}
