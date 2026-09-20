package com.anticheat.core.check.impl.timer

/**
 * 移动包余额法（Timer 检测的核心，纯逻辑、可离线单测）。
 *
 * <p><b>为什么用余额而不是"数每秒多少个包"：</b>计数的分辨率是整包，而原版客户端
 * 每 tick 恰好发 1 个移动包——作弊客户端把发包率调快 5% 时，用 1 秒窗口计数
 * 只会看到 20 或 21 个包，根本无法区分"网络抖动"和"时钟加速"。
 * 余额法把每个包的**时间误差累积起来**：包比预期早到多少毫秒，就欠多少毫秒，
 * 于是 5% 的加速会在 20 个包之后累积出整整一个 tick 的误差，信噪比高得多。</p>
 *
 * <p>余额的两个边界各有用途：</p>
 * - **下界**（`-bufferMillis`）：容纳真实的网络抖动与卡顿。玩家卡一下，
 *   余额会掉到很负，之后要慢慢"还账"才能回到 0——这正是我们想要的，
 *   卡顿本身不构成违规，但它会稀释掉之后的检测灵敏度；
 * - **上界**（[maxMillis]，固定 1000ms）：防止余额被一次异常值撑爆后无法回落。</p>
 *
 * <p>参考值来自 intave 的 `Balance`：预期间隔 50ms、溢出线 100ms、
 * 每次通过扣 0.75ms。100ms 的溢出线意味着
 * 「比原版快约 1 个 tick」就会触发，而 0.75ms/tick 的还账速率
 * 决定了它能被容忍多久（约 0.75ms × 20 = 15ms/s，即 1.5% 的持续加速
 * 会被还账抵消掉——这恰好是刻意留出的容差）。</p>
 */
class TimerBalance(
    /** 原版客户端每 tick 一个移动包，即每 50ms 一个。 */
    private val expectedIntervalMillis: Double = DEFAULT_EXPECTED_INTERVAL_MS,
    /** 超过该余额即判定为"发包过快"。 */
    private val overflowMillis: Double = DEFAULT_OVERFLOW_MS,
    /** 每次通过（未溢出的包）扣减的余额，即"还账"速率。 */
    private val releasePerPassMillis: Double = DEFAULT_RELEASE_MS,
    private val maxMillis: Double = DEFAULT_MAX_MS
) {

    init {
        require(expectedIntervalMillis > 0.0) { "预期间隔必须为正" }
        require(overflowMillis > 0.0 && maxMillis > 0.0) { "溢出线与上限必须为正" }
        require(releasePerPassMillis >= 0.0) { "还账速率不能为负" }
    }

    /** 允许的欠账额度（毫秒，正数表示"最多允许欠这么多"）。 */
    var bufferMillis: Double = DEFAULT_BUFFER_MS
        set(value) {
            field = value.coerceAtLeast(0.0)
        }

    /** 当前余额（毫秒）。正数 = 发包快于原版，负数 = 发包慢/卡顿欠账。 */
    var balanceMillis: Double = 0.0
        private set

    private var lastPacketNanos: Long = 0L

    /** 是否已收到过第一个包。第一个包没有间隔可比，只用来定基准。 */
    var primed: Boolean = false
        private set

    /**
     * 收到一个移动包。
     *
     * @param nowNanos 单调时钟（`System.nanoTime()`）。**不要用墙钟**：
     *   校时/NTP 跳变会让墙钟出现负的或巨大的间隔，直接把余额打飞。
     * @return 更新后的余额
     */
    fun onMovementPacket(nowNanos: Long): Double {
        if (!primed) {
            primed = true
            lastPacketNanos = nowNanos
            return balanceMillis
        }
        val deltaNanos = nowNanos - lastPacketNanos
        lastPacketNanos = nowNanos
        // 时钟回退或同一纳秒：不产生信息，直接忽略（不要当成"发了两个包"）
        if (deltaNanos <= 0L) return balanceMillis

        val deltaMillis = deltaNanos / 1_000_000.0
        balanceMillis += expectedIntervalMillis - deltaMillis
        balanceMillis = balanceMillis.coerceIn(-bufferMillis, maxMillis)
        return balanceMillis
    }

    /** 一次"未溢出"的通过：余额为正时扣掉 [releasePerPassMillis]。 */
    fun onPass() {
        if (balanceMillis > 0.0) {
            balanceMillis = (balanceMillis - releasePerPassMillis).coerceAtLeast(0.0)
        }
    }

    fun isOverflowing(): Boolean = balanceMillis > overflowMillis

    /** 溢出被判违规后削掉一部分余额，避免同一次超发被反复计数。 */
    fun forgive(millis: Double) {
        balanceMillis -= millis
    }

    /** 服务端把玩家传送/重生走：下一个包的间隔必然是异常的，需要先还一个 tick。 */
    fun onTeleport() {
        balanceMillis -= expectedIntervalMillis
    }

    /** 余额相当于"快了几个 tick"，用于告警文案。 */
    fun ticksAhead(): Double {
        val value = balanceMillis / expectedIntervalMillis
        return if (value < 0.01) 0.01 else value
    }

    fun reset() {
        balanceMillis = 0.0
        lastPacketNanos = 0L
        primed = false
    }

    companion object {
        const val DEFAULT_EXPECTED_INTERVAL_MS = 50.0
        const val DEFAULT_OVERFLOW_MS = 100.0
        const val DEFAULT_RELEASE_MS = 0.75

        /**
         * 默认只给 1000ms 的欠账额度，对应信任度最低的一档。
         *
         * <p>intave 按信任值给了 1000~100000ms 的宽裕区间；**这里刻意取最保守的一档**：
         * 额度越大，卡顿玩家能"藏"住的加速越多。真实网络下的抖动通常远小于 1 秒，
         * 先用 1000ms 收敛假阳性，等真机数据出来再谈放宽。</p>
         */
        const val DEFAULT_BUFFER_MS = 1000.0
        const val DEFAULT_MAX_MS = 1000.0
    }
}
