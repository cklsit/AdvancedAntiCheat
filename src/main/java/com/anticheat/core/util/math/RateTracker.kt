package com.anticheat.core.util.math

/**
 * 按 tick 分格的**事件频率窗口**（每格 = 1 tick，滑动窗口 = [windowTicks] tick）。
 *
 * <p>承载「最近一秒发生了多少次 X」这类统计：攻击次数（CPS）、放置方块次数等。</p>
 *
 * <p>用**环形数组**而不是「记时间戳再回删」：后者每次事件都要遍历列表，
 * 而事件频率完全由客户端控制（作弊客户端可以在一秒内灌进上千个包），
 * 那是一条能把主线程拖垮的路径。环形数组把每次事件都压成 O(1)，
 * 每 tick 推进一格也只用 O(1)（维护增量总和，不重新求和）。</p>
 *
 * <p><b>线程安全</b>：事件来自 **Netty 网络线程**（收包），而每 tick 推进
 * 来自**主线程**（TickRunner）。两者会同时改游标与总量，
 * 这里用方法级互斥保证一致性——不加锁的话 `total -= buckets[cursor]`
 * 可能与 `total++` 交错，导致读到的频率长期偏大或偏小。
 * 锁的开销可以忽略：主线程每秒 20 次，Netty 线程每次事件一次。</p>
 */
class RateTracker(private val windowTicks: Int = DEFAULT_WINDOW_TICKS) {

    init {
        require(windowTicks >= 2) { "窗口至少 2 tick，否则频率没有意义" }
    }

    private val buckets = IntArray(windowTicks)

    private var cursor = 0

    /** 窗口内总量。增量维护，避免每 tick 求和。 */
    private var total = 0

    /** 当前格（本 tick）内已记录的事件数。 */
    var eventsInCurrentBucket: Int = 0
        private set

    @Synchronized
    fun record() {
        buckets[cursor]++
        total++
        eventsInCurrentBucket++
    }

    /** 推进一格：滑出最旧的一格。 */
    @Synchronized
    fun tick() {
        cursor = (cursor + 1) % windowTicks
        total -= buckets[cursor]
        buckets[cursor] = 0
        eventsInCurrentBucket = 0
    }

    /** 最近 [windowTicks] tick 内的事件总数。 */
    @Synchronized
    fun count(): Int = total

    /**
     * 单格（单 tick）内的最大事件数。
     *
     * <p>用途是抓"同一 tick 内发生了 N 次"这类**协议层不可能**的行为：
     * 原版一次攻击/一次右键放置都受客户端冷却限制，不可能在一个 tick 内完成两次。
     * 只看每秒总数是发现不了它的（20 次/秒完全正常，但"1 tick 内 20 次"不正常）。</p>
     */
    @Synchronized
    fun peakPerBucket(): Int = buckets.maxOrNull() ?: 0

    @Synchronized
    fun reset() {
        buckets.fill(0)
        cursor = 0
        total = 0
        eventsInCurrentBucket = 0
    }

    companion object {
        const val DEFAULT_WINDOW_TICKS = 20
    }
}
