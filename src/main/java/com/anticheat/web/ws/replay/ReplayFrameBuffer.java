package com.anticheat.web.ws.replay;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 每目标 WS 帧环形缓冲（v3 协议续传用）。
 *
 * <p>服务端为每个 target 保留最近 {@link #RETENTION_MS}（默认 60s）内发出的
 * 可重放帧（{@code hud} / {@code violation} / {@code event}）。
 * 前端断线重连时发 {@code {"op":"subscribe","lastSeq":n}}，
 * 服务端据此回放 {@code seq > n} 的帧，避免 HUD 出现断片。</p>
 *
 * <p><b>关键语义</b>：只有当缓冲中最老的帧 {@code seq <= lastSeq + 1} 时才认为
 * "能无缝补齐"；若缺口已滚出缓冲（断线过久），返回 {@code null}，
 * 由调用方在 {@code hello} 中标记 {@code resumed:false}，
 * 前端据此清空重画——<b>绝不静默显示旧数据</b>。</p>
 *
 * <p><b>线程安全</b>：入队发生在主线程（采样）与调度线程，出队发生在 Javalin IO 线程，
 * 故内部一律使用 {@link ConcurrentLinkedQueue}，不使用会被并发读写的
 * {@code LinkedList}/{@code ArrayDeque}（本仓库曾因此出现 peek() 拆箱 NPE 刷屏）。</p>
 */
public class ReplayFrameBuffer {

    /** 帧保留时长：60 秒（设计文档 §6.1）。 */
    public static final long RETENTION_MS = 60_000L;

    /** 单目标最多缓存的帧数上限（20Hz × 60s = 1200，留足余量并防止恶意堆积）。 */
    public static final int MAX_FRAMES_PER_TARGET = 2400;

    /** 允许重放的消息类型；hello 不重放（它由 onConnect 重新生成）。 */
    private static final java.util.Set<String> REPLAYABLE =
            new java.util.HashSet<>(java.util.Arrays.asList("hud", "violation", "event", "telemetry"));

    /** key = target 玩家 UUID，value = 该目标的帧队列（按 seq 升序） */
    private final Map<UUID, ConcurrentLinkedQueue<Frame>> buffers = new ConcurrentHashMap<>();

    /**
     * 记录一帧（envelope Map）。
     *
     * @param target   目标玩家 UUID
     * @param envelope v3 信封 Map，须含 {@code seq}/{@code type}/{@code ts}
     */
    public void record(UUID target, Map<String, Object> envelope) {
        if (target == null || envelope == null) return;

        Object typeObj = envelope.get("type");
        String type = typeObj == null ? null : String.valueOf(typeObj);
        if (type == null || !REPLAYABLE.contains(type)) return;

        long seq = toLong(envelope.get("seq"), -1L);
        long ts = toLong(envelope.get("ts"), System.currentTimeMillis());
        if (seq < 0) return;

        ConcurrentLinkedQueue<Frame> q = buffers.computeIfAbsent(target,
                k -> new ConcurrentLinkedQueue<>());
        q.add(new Frame(seq, ts, envelope));

        evict(q, ts);
    }

    /**
     * 回放 {@code seq > lastSeq} 的帧。
     *
     * @param target  目标玩家 UUID
     * @param lastSeq 前端已收到的最大 seq
     * @return 待补帧列表（按 seq 升序）；若缺口已滚出缓冲则返回 {@code null}
     */
    public List<Map<String, Object>> replaySince(UUID target, long lastSeq) {
        ConcurrentLinkedQueue<Frame> q = buffers.get(target);
        if (q == null || q.isEmpty()) {
            return lastSeq <= 0 ? new ArrayList<>() : null;
        }

        // 先按保留窗口淘汰过期帧，保证 oldest 的判断基于当前时间
        evict(q, System.currentTimeMillis());

        Frame oldest = q.peek();
        if (oldest == null) {
            return lastSeq <= 0 ? new ArrayList<>() : null;
        }

        // 断开过久：最老的帧已经晚于 lastSeq+1，说明中间丢了帧
        if (oldest.seq > lastSeq + 1) {
            return null;
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Frame f : q) {
            if (f.seq > lastSeq) {
                out.add(f.envelope);
            }
        }
        return out;
    }

    /** 目标会话结束/无任何订阅者时释放缓冲。 */
    public void drop(UUID target) {
        if (target != null) buffers.remove(target);
    }

    /** 清空全部缓冲（插件禁用时调用）。 */
    public void clear() {
        buffers.clear();
    }

    /** 当前缓存的目标数量（诊断用）。 */
    public int trackedTargets() {
        return buffers.size();
    }

    // ===================== 内部实现 =====================

    private void evict(ConcurrentLinkedQueue<Frame> q, long nowMs) {
        if (q == null) return;
        final long cutoff = nowMs - RETENTION_MS;
        // 按时间淘汰
        for (Iterator<Frame> it = q.iterator(); it.hasNext(); ) {
            Frame f = it.next();
            if (f.ts < cutoff) {
                it.remove();
            } else {
                break; // 队列按时间升序，遇到未过期即可停
            }
        }
        // 按数量兜底淘汰（防止极端采样率打爆内存）
        int overflow = q.size() - MAX_FRAMES_PER_TARGET;
        for (int i = 0; i < overflow; i++) {
            if (q.poll() == null) break;
        }
    }

    private static long toLong(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return def;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 一帧缓存项。 */
    private static final class Frame {
        final long seq;
        final long ts;
        final Map<String, Object> envelope;

        Frame(long seq, long ts, Map<String, Object> envelope) {
            this.seq = seq;
            this.ts = ts;
            this.envelope = envelope;
        }
    }
}
