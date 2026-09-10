package com.anticheat.replay;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ObserverPoolManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 监视调度核心（需求 1）。
 *
 * <p>把回放从"管理员订阅驱动"翻转为"玩家进入驱动"：</p>
 * <ol>
 *   <li>玩家进入 → 入队（FIFO，按进入顺序）；</li>
 *   <li>有空闲槽（{@code maxConcurrent}，默认 3，可配置文件/面板热更）→ 分配观察者；</li>
 *   <li>超出上限 → 排队等待，先到先服务；</li>
 *   <li>玩家退出 → <b>立即释放槽位</b>并调度队首（补齐现状"目标退出仍占槽"的漏洞）；</li>
 *   <li>严重违规 → 可选插队（默认关闭，保持 FIFO）。</li>
 * </ol>
 *
 * <p>本类只负责"调度决策"（队列、槽位计数、分配时机），实际的观察者供给、跟随与
 * FFmpeg 录制仍委托给 {@link ObserverPoolManager}。决策段全部 {@code synchronized}，
 * I/O 与阻塞操作由 {@link ObserverPoolManager} 在异步线程执行。</p>
 */
public class SurveillanceScheduler {

    private static final String OBSERVER_PREFIX = "ReplayObserver_";

    private final AdvancedAntiCheat plugin;
    private final ReplaySettings settings;
    private final ObserverPoolManager pool;
    private final Logger logger;

    /** FIFO 队列（进入顺序）。 */
    private final Deque<UUID> queue = new ArrayDeque<>();
    /** 队列成员去重（O(1) 判重）。 */
    private final Set<UUID> queuedSet = ConcurrentHashMap.newKeySet();
    /** 正在被监视的目标（已占用槽位）。 */
    private final Set<UUID> activeTargets = ConcurrentHashMap.newKeySet();
    /** 队列中各目标的进入序号，供可选插队排序用。 */
    private final Map<UUID, Long> joinSeqMap = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong joinSeq = new java.util.concurrent.atomic.AtomicLong(0);

    public SurveillanceScheduler(AdvancedAntiCheat plugin, ReplaySettings settings, ObserverPoolManager pool) {
        this.plugin = plugin;
        this.settings = settings;
        this.pool = pool;
        this.logger = plugin.getLogger();
    }

    // ===================== 事件入口（主线程） =====================

    /** 玩家进入：满足条件则入队。 */
    public synchronized void onPlayerJoin(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!settings.isSurveillanceEnabled() || !settings.isAutoOnJoin()) return;
        if (isExcluded(player)) return;

        UUID uuid = player.getUniqueId();
        // 已在监视或已在排队 → 幂等
        if (activeTargets.contains(uuid) || queuedSet.contains(uuid)) return;

        enqueue(uuid);
        pump();
    }

    /** 玩家退出：立即释放槽位 / 移出队列，并调度下一个。 */
    public synchronized void onPlayerQuit(UUID uuid) {
        if (uuid == null) return;

        boolean wasActive = activeTargets.remove(uuid);
        if (wasActive) {
            try {
                pool.release(uuid); // 异步：停流 + 合成 mp4 + 归档 + 归还槽位
            } catch (Throwable t) {
                logger.warning("[Replay-Sched] 释放观察者异常 target=" + uuid + ": " + t.getMessage());
            }
        } else {
            queuedSet.remove(uuid);
            queue.remove(uuid);
            joinSeqMap.remove(uuid);
        }

        pump();
    }

    /**
     * 记录到违规时调用（可选插队）。
     *
     * @param severe 是否为严重违规（决定是否触发优先级提升）
     */
    public synchronized void onViolation(UUID uuid, boolean severe) {
        if (uuid == null || !severe) return;
        if (!settings.isPriorityBoostOnViolation()) return;
        if (activeTargets.contains(uuid)) return; // 已在监视
        if (!queuedSet.contains(uuid)) return;

        // 移到队首
        queue.remove(uuid);
        queue.addFirst(uuid);
        logger.info("[Replay-Sched] 严重违规，目标插队到队首: " + uuid);
        pump();
    }

    // ===================== 队列 / 分配 =====================

    private void enqueue(UUID uuid) {
        if (queue.size() >= settings.getQueueMaxSize()) {
            logger.warning("[Replay-Sched] 队列已满（" + settings.getQueueMaxSize() + "），丢弃监视请求: " + uuid);
            // 显式降级事件（§7 禁止静默降级）：该玩家本次不获槽，仅遥测录制
            pushEvent(uuid, "degraded", "队列溢出，仅遥测录制（无视频）",
                    null, null, "L3", "queue_overflow");
            return;
        }
        queue.addLast(uuid);
        queuedSet.add(uuid);
        joinSeqMap.put(uuid, joinSeq.incrementAndGet());
        int position = queue.size();
        logger.info("[Replay-Sched] 目标入队 " + uuid + "，当前队列深度=" + position);
        // 告知面板当前排队位置（§4.1 event:slot_queued）
        pushEvent(uuid, "slot_queued", "排队等待观察者槽位", position, position * 60L, null, null);
    }

    /** 尽可能用空闲槽调度队列头部目标。 */
    private void pump() {
        int maxSlots = effectiveMaxConcurrent();
        while (activeTargets.size() < maxSlots && !queue.isEmpty()) {
            UUID next = queue.pollFirst();
            if (next == null) break;
            queuedSet.remove(next);
            joinSeqMap.remove(next);
            assign(next);
        }
    }

    private void assign(UUID uuid) {
        ObserverPoolManager.Observer obs = pool.acquire(uuid);
        if (obs != null) {
            activeTargets.add(uuid);
            logger.info("[Replay-Sched] 已分配观察者 #" + obs.id + " name=" + obs.name + " → target=" + uuid);
            pushEvent(uuid, "slot_acquired", "已获观察者槽位，等待画面就绪", null, null, null, null);
        } else {
            // 理论上不会发生（pump 已保证有空槽）；兜底：插回队首并告警
            logger.warning("[Replay-Sched] acquire 返回 null（池满或异常），目标重新排队: " + uuid);
            queuedSet.add(uuid);
            queue.addFirst(uuid);
            joinSeqMap.put(uuid, joinSeq.incrementAndGet());
        }
    }

    /**
     * 向指定 target 的 WS 订阅者推送 event 帧（降级阶梯 §7：每一级都必须显式上报）。
     * 无订阅者时 broadcastEnvelope 自动空转，不会抛错。
     */
    private void pushEvent(UUID targetUuid, String event, String message,
                           Integer queuePosition, Long etaSec, String level, String reason) {
        try {
            com.anticheat.web.ws.replay.ReplayBroadcaster rb = plugin.getReplayBroadcaster();
            if (rb == null || targetUuid == null) return;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("event", event);
            if (message != null) data.put("message", message);
            if (queuePosition != null) data.put("queuePosition", queuePosition);
            if (etaSec != null) data.put("etaSec", etaSec);
            if (level != null) data.put("level", level);
            if (reason != null) data.put("reason", reason);
            rb.broadcastEnvelope(targetUuid, "event", data);
        } catch (Throwable t) {
            logger.fine("[Replay-Sched] pushEvent 失败（已忽略）: " + t.getMessage());
        }
    }

    private int effectiveMaxConcurrent() {
        int poolSize = pool != null ? pool.getPoolSize() : 1;
        return Math.min(settings.getMaxConcurrent(), Math.max(1, poolSize));
    }

    private boolean isExcluded(Player player) {
        // 观察者机器人自身不可被监视
        if (player.getName() != null && player.getName().startsWith(OBSERVER_PREFIX)) {
            return true;
        }
        for (String perm : settings.getExcludePermissions()) {
            if (perm != null && !perm.isEmpty() && player.hasPermission(perm)) {
                return true;
            }
        }
        String uuidStr = player.getUniqueId().toString();
        for (String excluded : settings.getExcludeUuids()) {
            if (excluded != null && excluded.equalsIgnoreCase(uuidStr)) {
                return true;
            }
        }
        return false;
    }

    // ===================== 热更新 =====================

    /** 配置重载后调用：maxConcurrent 调大则立即补满队列，调小则停止新分配（自然收敛）。 */
    public synchronized void onConfigReload() {
        logger.info("[Replay-Sched] 配置重载：maxConcurrent=" + settings.getMaxConcurrent()
                + "，active=" + activeTargets.size() + "，queue=" + queue.size());
        pump();
    }

    // ===================== 状态快照（供面板） =====================

    /** 返回可 JSON 序列化的调度状态快照。 */
    public synchronized Map<String, Object> statusSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxConcurrent", settings.getMaxConcurrent());
        result.put("effectiveMaxConcurrent", effectiveMaxConcurrent());
        result.put("activeCount", activeTargets.size());
        result.put("queueSize", queue.size());
        result.put("queueEnabled", settings.isQueueEnabled());
        result.put("autoOnJoin", settings.isAutoOnJoin());
        result.put("surveillanceEnabled", settings.isSurveillanceEnabled());

        java.util.List<Map<String, Object>> active = new java.util.ArrayList<>();
        for (UUID uuid : activeTargets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", uuid.toString());
            Player p = Bukkit.getPlayer(uuid);
            m.put("name", p != null ? p.getName() : null);
            active.add(m);
        }
        result.put("active", active);

        java.util.List<Map<String, Object>> queued = new java.util.ArrayList<>();
        for (UUID uuid : queue) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", uuid.toString());
            m.put("joinSeq", joinSeqMap.getOrDefault(uuid, 0L));
            Player p = Bukkit.getPlayer(uuid);
            m.put("name", p != null ? p.getName() : null);
            queued.add(m);
        }
        result.put("queue", queued);
        return result;
    }

    public void shutdown() {
        queue.clear();
        queuedSet.clear();
        activeTargets.clear();
        joinSeqMap.clear();
    }
}
