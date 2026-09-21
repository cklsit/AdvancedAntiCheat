package com.anticheat.core.manager

/**
 * 服务端 tick 计数器。
 *
 * <p>反作弊里所有「多久没动作」「距上次收到包过了几 tick」的判断都必须基于 tick 而不是墙钟：
 * 墙钟在服务器卡顿时会失准，而卡顿本身正是假阳性高发期。</p>
 */
class TickManager {

    @Volatile
    var currentTick: Long = 0L
        private set

    /**
     * 服务器最近 20 tick 的平均 TPS（0~20）。
     *
     * <p>为什么要在核心层自己算：违规记录里要写"当时服务器有多卡"，
     * 而卡顿既会让检测数值失真（本项目的容差全按 50ms/tick 推导），
     * 也是排查"某段时间为什么误报"的第一线索。Bukkit 没有稳定的跨版本 TPS API
     * （1.8 与 1.21 各自的私有字段名不同），因此直接用 tick 间隔自己算。</p>
     */
    @Volatile
    var tps: Double = EXPECTED_TPS
        private set

    private var lastTickNanos: Long = 0L

    private val samples = DoubleArray(TPS_WINDOW)

    private var sampleCursor = 0

    private var sampleCount = 0

    fun nextTick(): Long {
        currentTick++
        recordTickInterval()
        return currentTick
    }

    private fun recordTickInterval() {
        val now = System.nanoTime()
        val previous = lastTickNanos
        lastTickNanos = now
        if (previous == 0L) return

        val elapsed = now - previous
        if (elapsed <= 0L) return
        // 单 tick 的瞬时 TPS 上限是 20（= 50ms 一个 tick）；用滑动窗口平均，
        // 避免一次 GC 停顿把数值打到 0 之后又跳回 20
        val instant = (1_000_000_000.0 / elapsed).coerceAtMost(EXPECTED_TPS)
        samples[sampleCursor] = instant
        sampleCursor = (sampleCursor + 1) % TPS_WINDOW
        if (sampleCount < TPS_WINDOW) sampleCount++

        var sum = 0.0
        for (i in 0 until sampleCount) sum += samples[i]
        tps = sum / sampleCount
    }

    fun reset() {
        currentTick = 0L
        lastTickNanos = 0L
        sampleCursor = 0
        sampleCount = 0
        tps = EXPECTED_TPS
    }

    companion object {
        /** 正常 TPS 与窗口长度（1 秒）。 */
        const val EXPECTED_TPS = 20.0

        const val TPS_WINDOW = 20
    }
}
