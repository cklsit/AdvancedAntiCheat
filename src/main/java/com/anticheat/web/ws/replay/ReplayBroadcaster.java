package com.anticheat.web.ws.replay;

import com.anticheat.web.util.JsonMapper;
import io.javalin.websocket.WsContext;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replay 回放 WS 广播器。
 * <p>
 * - 按 targetUuid 维护订阅者集合（每个 target 独立一份 Set）
 * - register/unregister/broadcast 全部线程安全（ConcurrentHashMap + KeySet）
 * - broadcast 发送前检测 session 状态，失败自动 unregister
 * - 提供 sendToOne helper：任意对象 → JSON → ctx.send
 */
public class ReplayBroadcaster {

    private static final Logger LOG = Logger.getLogger(ReplayBroadcaster.class.getName());

    /** key = target 玩家 UUID，value = 订阅者 WS 连接集合 */
    private final Map<UUID, Set<WsContext>> subscribers = new ConcurrentHashMap<>();

    /** key = target 玩家 UUID，value = 该目标的单调消息序号（v3 信封 seq，用于前端丢帧/乱序检测） */
    private final Map<UUID, AtomicLong> seqMap = new ConcurrentHashMap<>();

    /**
     * 帧环形缓冲（v3 续传用）：记录每个 target 最近 60s 发出的可重放帧。
     * 前端重连时发 {@code {"op":"subscribe","lastSeq":n}}，由 ReplayWSHandler 从此缓冲补帧。
     */
    private final ReplayFrameBuffer frameBuffer = new ReplayFrameBuffer();

    /** 协议版本号（v3 统一信封）。 */
    public static final int PROTOCOL_VERSION = 3;

    /** 暴露帧缓冲，供 ReplayWSHandler 做断线续传。 */
    public ReplayFrameBuffer getFrameBuffer() {
        return frameBuffer;
    }

    /**
     * 构建 v3 统一信封 Map：{@code {"v":3,"type":...,"ts":...,"seq":...,"data":{...}}}。
     * seq 为按目标递增的单调序号，从 1 开始。
     */
    public Map<String, Object> envelopeMap(UUID target, String type, Map<String, Object> data) {
        long seq = seqMap.computeIfAbsent(target, k -> new AtomicLong(0)).incrementAndGet();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("v", PROTOCOL_VERSION);
        env.put("type", type);
        env.put("ts", System.currentTimeMillis());
        env.put("seq", seq);
        env.put("data", data == null ? Collections.emptyMap() : data);
        return env;
    }

    /** 构建 v3 统一信封并序列化为 JSON。 */
    public String envelope(UUID target, String type, Map<String, Object> data) {
        return JsonMapper.toJson(envelopeMap(target, type, data));
    }

    /** 构建 v3 信封并按目标广播（同时写入帧缓冲，供断线续传）。 */
    public void broadcastEnvelope(UUID target, String type, Map<String, Object> data) {
        Map<String, Object> env = envelopeMap(target, type, data);
        // 入缓冲必须在 broadcast 之前：保证补帧序列与实际下发序列一致
        frameBuffer.record(target, env);
        String json = JsonMapper.toJson(env);
        broadcast(target, json);
    }

    /**
     * 注册一个订阅者。
     *
     * @param ctx        WS 连接上下文
     * @param targetUuid 订阅的目标玩家 UUID
     */
    public void register(WsContext ctx, UUID targetUuid) {
        if (ctx == null || targetUuid == null) return;
        try {
            ctx.attribute("replay.target", targetUuid);
        } catch (Throwable ignored) {
        }
        Set<WsContext> set = subscribers.computeIfAbsent(targetUuid,
                k -> ConcurrentHashMap.newKeySet());
        set.add(ctx);
    }

    /**
     * 注销一个订阅者（从 attribute 中取 targetUuid）。
     * 若对应 set 变空则移除 targetUuid 条目。
     */
    public void unregister(WsContext ctx) {
        if (ctx == null) return;
        UUID targetUuid = null;
        try {
            Object attr = ctx.attribute("replay.target");
            if (attr instanceof UUID) {
                targetUuid = (UUID) attr;
            }
        } catch (Throwable ignored) {
        }
        if (targetUuid == null) return;
        Set<WsContext> set = subscribers.get(targetUuid);
        if (set == null) return;
        set.remove(ctx);
        if (set.isEmpty()) {
            subscribers.remove(targetUuid);
            // 无人订阅时释放该目标的帧缓冲（60s 续传窗口已无意义）
            frameBuffer.drop(targetUuid);
        }
    }

    /** 目标下线时清理其帧缓冲（由 SurveillanceScheduler 调用）。 */
    public void dropFrames(UUID target) {
        frameBuffer.drop(target);
    }

    /**
     * 返回指定 target 的订阅者数量。
     */
    public int subscriberCount(UUID target) {
        if (target == null) return 0;
        Set<WsContext> set = subscribers.get(target);
        return set == null ? 0 : set.size();
    }

    /**
     * 向指定 target 的所有订阅者广播一条 JSON 消息。
     * 对已关闭或发送失败的连接，自动 unregister。
     */
    public void broadcast(UUID target, String jsonMessage) {
        if (target == null || jsonMessage == null) return;
        Set<WsContext> set = subscribers.get(target);
        if (set == null || set.isEmpty()) return;
        for (Iterator<WsContext> it = set.iterator(); it.hasNext(); ) {
            WsContext ctx = it.next();
            try {
                if (ctx.session != null && ctx.session.isOpen()) {
                    ctx.send(jsonMessage);
                } else {
                    it.remove();
                    // 可能需要从 subscribers map 里也清（若 set 空）
                    if (set.isEmpty()) subscribers.remove(target);
                }
            } catch (Throwable t) {
                it.remove();
                if (set.isEmpty()) subscribers.remove(target);
                LOG.log(Level.WARNING, "[ReplayBroadcaster] send 失败，已移除连接: "
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        }
    }

    /**
     * 向单个 WS 连接发送对象（JSON 序列化）。
     * 发送异常时只记录 warning，不抛出。
     */
    public void sendToOne(WsContext ctx, Object obj) {
        if (ctx == null || obj == null) return;
        try {
            String json = JsonMapper.toJson(obj);
            if (ctx.session != null && ctx.session.isOpen()) {
                ctx.send(json);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[ReplayBroadcaster] sendToOne 失败: "
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }
}
