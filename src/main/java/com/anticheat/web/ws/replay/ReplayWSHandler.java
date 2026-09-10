package com.anticheat.web.ws.replay;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ObserverPoolManager;
import com.anticheat.managers.replay.RingBuffer;
import com.anticheat.managers.replay.TracePoint;
import com.anticheat.managers.replay.ViolationMarker;
import com.anticheat.web.auth.AuthManager;
import com.anticheat.web.util.JsonMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replay 回放 WebSocket 路由处理器。
 * <p>
 * 路径：/ws/replay/{targetUuid}?token=xxx
 * <p>
 * 流程：
 * <ol>
 *     <li>onConnect：queryParam token 鉴权 → pathParam targetUuid 解析 → 校验目标在线
 *         → 构造 init 帧（hlsUrl + wallClockStartMs + lastHudFrame + lastInventory + violations）
 *         → sendToOne → broadcaster.register</li>
 *     <li>onMessage：只处理 __PING__ → __PONG__，其余忽略（WS 是 server push only）</li>
 *     <li>onClose / onError：broadcaster.unregister</li>
 * </ol>
 */
public class ReplayWSHandler {

    private static final Logger LOG = Logger.getLogger(ReplayWSHandler.class.getName());

    private final AdvancedAntiCheat plugin;
    private final AuthManager authManager;
    private final com.anticheat.managers.ReplayRecorder recorder;
    private final com.anticheat.managers.ffmpeg.FfmpegManager ffmpegManager;
    private final ObserverPoolManager poolManager;
    private final ReplayBroadcaster broadcaster;

    public ReplayWSHandler(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.authManager = plugin.getAuthManager();
        this.recorder = plugin.getReplayRecorder();
        this.ffmpegManager = plugin.getFfmpegManager();
        this.poolManager = plugin.getObserverPoolManager();
        this.broadcaster = new ReplayBroadcaster();
    }

    /** 暴露 broadcaster，供 ReplayRecorder / ObserverPoolManager 推送 HUD/违规/事件。 */
    public ReplayBroadcaster getBroadcaster() {
        return broadcaster;
    }

    public void register(Javalin app) {
        app.ws("/ws/replay/{targetUuid}", this::configure);
    }

    private void configure(WsConfig ws) {
        ws.onConnect(this::onConnect);
        ws.onMessage(this::onMessage);
        ws.onClose(this::onClose);
        ws.onError(this::onError);
    }

    // ========== 生命周期 ==========

    private void onConnect(WsConnectContext ctx) {
        try {
            // 1) Token 鉴权
            String token = ctx.queryParam("token");
            if (token == null || token.isEmpty() || authManager.verifyToken(token) == null) {
                safeClose(ctx, 4001, "未授权");
                return;
            }

            // 2) 解析 targetUuid
            String targetUuidStr = ctx.pathParam("targetUuid");
            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(targetUuidStr);
            } catch (IllegalArgumentException e) {
                safeClose(ctx, 4400, "Bad UUID");
                return;
            }

            // 3) 判断目标玩家是否在线
            Player targetPlayer = safeGetPlayer(targetUuid);
            if (targetPlayer == null) {
                // 发送 offline 事件后关闭
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("event", "offline");
                data.put("message", "玩家离线");
                try {
                    ctx.send(broadcaster.envelope(targetUuid, "event", data));
                } catch (Throwable ignored) {
                }
                safeClose(ctx, 4402, "玩家离线");
                return;
            }

            // 4) 组合 hello 帧并发送（v3 信封）
            //    resumed=false：全新会话，前端据此清空既有缓冲重画
            Map<String, Object> hello = buildHelloData(targetUuid, targetPlayer);
            hello.put("resumed", false);
            broadcaster.sendToOne(ctx, broadcaster.envelopeMap(targetUuid, "hello", hello));

            // 5) 注册到 broadcaster
            broadcaster.register(ctx, targetUuid);

            // 6) 通知 ObserverPoolManager 增加订阅者（首次订阅会触发 acquire → 启动观察者 + ffmpeg）
            if (poolManager != null) {
                poolManager.incrementSubscriber(targetUuid);
            } else {
                LOG.log(Level.WARNING, "[ReplayWS] ObserverPoolManager 未初始化，跳过 acquire");
            }

            LOG.log(Level.INFO, "[ReplayWS] 新订阅: target=" + targetUuid
                    + " session=" + truncateSessionId(ctx.sessionId()));

        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[ReplayWS] onConnect 异常: " + t.getMessage(), t);
            safeClose(ctx, 4500, "连接初始化失败");
        }
    }

    /**
     * 构造 hello 帧的 data 部分（首次连接与断线续传共用）。
     *
     * @param targetUuid   目标玩家 UUID
     * @param targetPlayer 目标玩家（必须在线）
     * @param resumed      true=续传补齐；false=全新会话（前端需清空重画）
     */
    private Map<String, Object> buildHelloData(UUID targetUuid, Player targetPlayer) {
        // wallClockStartMs
        long wallClockStartMs = (recorder != null && recorder.getSessionEpoch(targetUuid) != null)
                ? recorder.getSessionEpoch(targetUuid)
                : System.currentTimeMillis();

        // hlsUrl
        String hlsUrl = (ffmpegManager != null)
                ? ffmpegManager.hlsUrl(targetUuid)
                : "/hls/" + targetUuid + "/index.m3u8";

        // lastHudFrame + lastInventory
        Map<String, Object> lastHudFrame = null;
        String[] lastInventory = null;
        RingBuffer<TracePoint> buf = (recorder != null) ? recorder.getSession(targetUuid) : null;
        if (buf != null) {
            int sz = buf.size();
            if (sz > 0) {
                TracePoint last = buf.peekLast();
                if (last != null) {
                    lastHudFrame = buildHudFrame(last);
                }
                // 回溯最多 20 点找 inventoryHotbar != null 的
                int maxBack = Math.min(20, sz);
                for (int k = 1; k <= maxBack; k++) {
                    TracePoint tp = buf.get(sz - k);
                    if (tp != null && tp.getInventoryHotbar() != null) {
                        lastInventory = tp.getInventoryHotbar();
                        break;
                    }
                }
            }
        }

        // violations 列表
        List<Map<String, Object>> violations = new ArrayList<>();
        if (recorder != null) {
            List<ViolationMarker> markers = recorder.getViolationMarkers(targetUuid);
            if (markers != null && !markers.isEmpty() && buf != null && buf.size() > 0) {
                List<TracePoint> snap = buf.snapshot();
                for (ViolationMarker m : markers) {
                    long wall = 0L;
                    long t = 0L;
                    int idx = Math.max(0, Math.min(m.getPointIndex(), snap.size() - 1));
                    TracePoint tp = snap.get(idx);
                    if (tp != null) {
                        wall = tp.getWallClockMs();
                        t = tp.getTimeOffsetMs();
                    }
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("wallClockMs", wall);
                    v.put("t", t);
                    v.put("type", m.getType() != null ? m.getType() : "");
                    v.put("level", m.getLevel() != null ? m.getLevel() : "");
                    v.put("details", "");
                    violations.add(v);
                }
            }
        }

        // ---- 组装 ----
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("uuid", targetUuid.toString());
        target.put("name", targetPlayer.getName());

        Map<String, Object> video = new LinkedHashMap<>();
        video.put("mode", "llhls");
        video.put("url", hlsUrl);
        video.put("targetLatencyMs", 2000);
        // ABR 阶梯：清晰度优先，最低 720p（设计文档 §8.2 取消 360p）
        List<Object> qualities = new ArrayList<>();
        qualities.add(quality("720p", 1280, 720, 2500));
        qualities.add(quality("1080p", 1920, 1080, 4500));
        video.put("qualities", qualities);

        // 能力位：如实上报，前端据此决定合成层渲染哪些区块
        com.anticheat.replay.ReplaySettings rs = plugin.getReplaySettings();
        boolean crosshairOn = rs == null || rs.isCrosshairEnabled();
        boolean fullInvOn = rs == null || rs.isFullInventory();
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("spectatorBound", true);
        capabilities.put("crosshair", crosshairOn);
        capabilities.put("fullInventory", fullInvOn);
        capabilities.put("resume", true);   // 支持 lastSeq 续传

        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("rateHz", 20);
        telemetry.put("fields", new String[]{
                "health", "hunger", "armor", "level", "xp", "hotbar",
                "pos", "target", "inventory"
        });

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("target", target);
        hello.put("t0WallClockMs", wallClockStartMs);
        hello.put("serverTimeMs", System.currentTimeMillis()); // 时钟校正用（§3.3）
        hello.put("hlsUrl", hlsUrl);
        hello.put("video", video);
        hello.put("telemetry", telemetry);
        hello.put("capabilities", capabilities);
        if (lastHudFrame != null) {
            hello.put("lastHud", lastHudFrame);
        }
        if (lastInventory != null) {
            hello.put("lastInventory", lastInventory);
        }
        hello.put("violations", violations);
        return hello;
    }

    private static Map<String, Object> quality(String name, int width, int height, int bitrateKbps) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("name", name);
        q.put("width", width);
        q.put("height", height);
        q.put("bitrateKbps", bitrateKbps);
        return q;
    }

    @SuppressWarnings("unchecked")
    private void onMessage(io.javalin.websocket.WsMessageContext ctx) {
        String msg = ctx.message();
        if (msg == null) return;
        String trimmed = msg.trim();
        if (trimmed.isEmpty()) return;

        // 兼容旧版字符串心跳
        if ("__PING__".equalsIgnoreCase(trimmed)) {
            sendPong(ctx);
            return;
        }

        Object parsed;
        try {
            parsed = JsonMapper.fromJson(trimmed, Object.class);
        } catch (Throwable ignored) {
            return; // 非 JSON 且非心跳 → 忽略
        }
        if (!(parsed instanceof Map)) return;

        Map<String, Object> body = (Map<String, Object>) parsed;
        Object opObj = body.get("op");
        if (opObj == null) return;
        String op = String.valueOf(opObj);

        if ("ping".equalsIgnoreCase(op)) {
            sendPong(ctx);
        } else if ("subscribe".equalsIgnoreCase(op)) {
            handleSubscribe(ctx, body);
        } else if ("setQuality".equalsIgnoreCase(op)) {
            // ABR 画质切换（P4）：容器侧尚未开放运行时调档，先记录不报错
            LOG.log(Level.FINE, "[ReplayWS] setQuality 请求（暂未支持）: " + body.get("name"));
        }
        // 其余 op 忽略（server push 为主）
    }

    /** 心跳响应，带服务端墙钟供前端做时钟偏移校正（设计文档 §3.3）。 */
    private void sendPong(io.javalin.websocket.WsContext ctx) {
        UUID targetUuid = getTarget(ctx);
        if (targetUuid == null) return;
        try {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("serverTimeMs", System.currentTimeMillis());
            ctx.send(broadcaster.envelope(targetUuid, "pong", d));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 断线续传：重连后发 {@code {"op":"subscribe","lastSeq":n}}，
     * 服务端从 60s 环形缓冲回放 {@code seq > n} 的帧。
     *
     * <p>缓冲不足（断开过久）时重发 {@code hello} 并标记 {@code resumed:false}，
     * 前端据此清空重画——<b>绝不静默显示旧数据</b>。</p>
     */
    private void handleSubscribe(io.javalin.websocket.WsMessageContext ctx, Map<String, Object> body) {
        UUID targetUuid = getTarget(ctx);
        if (targetUuid == null) return;

        long lastSeq = -1L;
        Object ls = body.get("lastSeq");
        if (ls instanceof Number) {
            lastSeq = ((Number) ls).longValue();
        } else if (ls != null) {
            try {
                lastSeq = Long.parseLong(String.valueOf(ls));
            } catch (NumberFormatException ignored) {
                lastSeq = -1L;
            }
        }

        Player targetPlayer = safeGetPlayer(targetUuid);
        if (targetPlayer == null) return;

        boolean canResume = lastSeq >= 0
                && broadcaster.getFrameBuffer().replaySince(targetUuid, lastSeq) != null;

        Map<String, Object> hello = buildHelloData(targetUuid, targetPlayer);
        hello.put("resumed", canResume);
        broadcaster.sendToOne(ctx, broadcaster.envelopeMap(targetUuid, "hello", hello));

        if (!canResume) {
            if (lastSeq >= 0) {
                LOG.log(Level.FINE, "[ReplayWS] 续传缺口已滚出缓冲，重发全量 hello: target="
                        + targetUuid + " lastSeq=" + lastSeq);
            }
            return;
        }

        List<Map<String, Object>> missed =
                broadcaster.getFrameBuffer().replaySince(targetUuid, lastSeq);
        if (missed == null) return; // 竞态：缓冲刚被淘汰
        for (Map<String, Object> env : missed) {
            broadcaster.sendToOne(ctx, env);
        }
        LOG.log(Level.FINE, "[ReplayWS] 续传补帧 " + missed.size() + " 条: target="
                + targetUuid + " lastSeq=" + lastSeq);
    }

    private void onClose(io.javalin.websocket.WsCloseContext ctx) {
        UUID targetUuid = getTarget(ctx);
        try {
            broadcaster.unregister(ctx);
        } catch (Throwable ignored) {
        }
        // 通知 ObserverPoolManager 减少订阅者（归零自动 release）
        if (poolManager != null && targetUuid != null) {
            poolManager.decrementSubscriber(targetUuid);
        }
    }

    private void onError(io.javalin.websocket.WsErrorContext ctx) {
        UUID targetUuid = getTarget(ctx);
        try {
            broadcaster.unregister(ctx);
        } catch (Throwable ignored) {
        }
        if (poolManager != null && targetUuid != null) {
            poolManager.decrementSubscriber(targetUuid);
        }
    }

    /** 从 WsContext attribute 中取出绑定的 targetUuid。 */
    private UUID getTarget(WsContext ctx) {
        try {
            Object attr = ctx.attribute("replay.target");
            if (attr instanceof UUID) return (UUID) attr;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ========== 辅助 ==========

    /** 构建 HUD frame Map（长键，与 WS hud 帧一致）。 */
    private Map<String, Object> buildHudFrame(TracePoint tp) {
        return HudFrameBuilder.build(tp, null);
    }

    private Player safeGetPlayer(UUID uuid) {
        try {
            return plugin.getServer().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeClose(WsConnectContext ctx, int code, String reason) {
        try {
            ctx.closeSession(code, reason);
        } catch (Throwable ignored) {
        }
    }

    private static String truncateSessionId(String sid) {
        if (sid == null) return "?";
        return sid.length() > 8 ? sid.substring(0, 8) : sid;
    }
}
