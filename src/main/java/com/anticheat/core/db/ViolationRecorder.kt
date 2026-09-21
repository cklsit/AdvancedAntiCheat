package com.anticheat.core.db

import com.anticheat.core.util.CoreLog
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

/**
 * 违规与检查统计的**异步批量落库器**。
 *
 * <h3>三条不可违反的纪律</h3>
 * 1. **绝不阻塞调用线程**：`record()` 只做一次入队（[ArrayBlockingQueue.offer]），
 *    队列满时**直接丢弃并计数**。调用点是收包链路与主线程，
 *    在那里等数据库等于把整个服务器拖住——宁可在极端情况下丢日志。
 * 2. **攒批 + 事务写**：一条一次 INSERT 会把库打满（一次交火几十条），
 *    所以按 `batch-size` / `flush-interval-ms` 两个条件触发刷写。
 * 3. **失败只记数不抛**：写库失败会一直被重试（下一批），
 *    但**不允许把异常抛回业务线程**；失败次数与丢弃条数都要能在日志里看到，
 *    否则"数据没了但没人知道"比丢数据本身更糟。
 *
 * <p>检查命中率的计数（`evaluations` / `flags`）与违规一起刷写：两者时间基准相同，
 * 分开刷会出现"命中率分母比分子晚一个周期"的诡异曲线。</p>
 */
class ViolationRecorder(
    private val settings: DatabaseSettings.ViolationSettings,
    private val violations: ViolationRepository,
    private val rules: RuleRepository,
    /** 统计桶的分钟数（来自 `database.stats.bucket-minutes`）。 */
    private val bucketMinutes: Int,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** 一个检测的累计计数（刷写后清零）。 */
    private class Counter {
        val evaluations = AtomicLong()
        val flags = AtomicLong()
        val vlSum = DoubleAdder()
        /** 桶内出现过的玩家（有上限：这里只需要"几个"，不需要精确名单）。 */
        val players = ConcurrentHashMap.newKeySet<UUID>()
    }

    private val queue = ArrayBlockingQueue<ViolationInput>(settings.queueCapacity, false)

    private val counters = ConcurrentHashMap<String, Counter>()

    private val droppedCount = AtomicLong()

    private val writtenCount = AtomicLong()

    private val failedCount = AtomicLong()

    private val flushCount = AtomicLong()

    /** 入队一条违规。**不阻塞、不抛异常**。 */
    fun record(input: ViolationInput) {
        if (!queue.offer(input)) droppedCount.incrementAndGet()
    }

    /**
     * 记一次"检测被评估"（命中率的分母）。
     *
     * <p>由 `CheckManager` 每 tick 对每个检测调用一次：这是本类里最高频的方法
     * （19 检测 × 在线人数 × 20 tick），所以只做一次 `AtomicLong` 自增。</p>
     */
    fun noteEvaluation(checkName: String) {
        counters.computeIfAbsent(checkName) { Counter() }.evaluations.incrementAndGet()
    }

    /** 记一次"检测判出违规"（命中率的分子 + VL 累计）。 */
    fun noteFlag(checkName: String, vlDelta: Double, uuid: UUID) {
        val counter = counters.computeIfAbsent(checkName) { Counter() }
        counter.flags.incrementAndGet()
        counter.vlSum.add(vlDelta)
        if (counter.players.size < MAX_TRACKED_PLAYERS) counter.players.add(uuid)
    }

    /**
     * 刷一次。
     *
     * @return `(写入的违规条数, 刷新的检测统计条目数)`；数据库不可用时返回 (-1, 0)
     */
    fun flush(): Pair<Int, Int> {
        var written = -1
        val batch = ArrayList<ViolationInput>(settings.batchSize)
        queue.drainTo(batch, settings.batchSize)
        if (batch.isNotEmpty()) {
            written = try {
                val count = violations.insertBatch(batch)
                writtenCount.addAndGet(count.toLong())
                count
            } catch (t: Throwable) {
                failedCount.incrementAndGet()
                // 只打一次消息、不打堆栈：容器化环境里这类失败通常成片出现，
                // 每批打一次堆栈会把日志刷满，反而埋掉真正的线索
                if (failedCount.get() <= 3L) {
                    CoreLog.warn("违规批量落库失败（第 " + failedCount.get() + " 次）: " + t.message)
                }
                -1
            }
        }

        val deltas = drainCounters()
        if (deltas.isNotEmpty()) {
            try {
                rules.accumulateCheckStats(bucketMillis(now()), deltas)
            } catch (t: Throwable) {
                if (failedCount.get() <= 3L) CoreLog.warn("检查统计落库失败: " + t.message)
                failedCount.incrementAndGet()
            }
        }

        flushCount.incrementAndGet()
        return written to deltas.size
    }

    /** 取出并清空累计计数。 */
    private fun drainCounters(): List<CheckStatDelta> {
        if (counters.isEmpty()) return emptyList()
        val out = ArrayList<CheckStatDelta>(counters.size)
        for ((name, counter) in counters) {
            val evaluations = counter.evaluations.getAndSet(0)
            val flags = counter.flags.getAndSet(0)
            val vlSum = counter.vlSum.sumThenReset()
            val players = counter.players.size
            counter.players.clear()
            if (evaluations == 0L && flags == 0L) continue
            out.add(CheckStatDelta(name, evaluations, flags, vlSum, players))
        }
        return out
    }

    /** 统计桶的起点（按 `bucket-minutes` 对齐）。 */
    fun bucketMillis(nowMillis: Long): Long {
        val bucketSize = bucketMinutes.coerceAtLeast(1) * 60_000L
        return nowMillis - Math.floorMod(nowMillis, bucketSize)
    }

    fun pendingCount(): Int = queue.size

    fun describe(): String =
        "待写=" + queue.size + " 已写=" + writtenCount.get() + " 丢弃=" + droppedCount.get() +
            " 失败=" + failedCount.get() + " 刷写=" + flushCount.get()

    companion object {
        /** 每个检测每桶最多跟踪多少个玩家（只要"几个"这个量级，不要精确名单）。 */
        const val MAX_TRACKED_PLAYERS = 256
    }
}
