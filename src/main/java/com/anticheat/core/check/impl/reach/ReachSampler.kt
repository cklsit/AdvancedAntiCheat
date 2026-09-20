package com.anticheat.core.check.impl.reach

import java.util.Arrays

/**
 * 伸手距离的**实测样本收集器**——标定专用，纯逻辑、可离线单测。
 *
 * <h3>它解决什么问题</h3>
 * 阈值不能靠猜，也不能靠"我读过原版代码"来定。真实服务器上的实测分布取决于
 * 该服的网络状况、插件集（反作弊必须与其它注入型插件共存）、玩法（PvP 密度）。
 * 所以流程应该是：**先在只告警模式下把真实样本收下来，看分布，再收紧阈值**。
 * 本类就是收样本的那一端：它按定长环形缓冲累积"每一次合格判定的实测距离"，
 * 然后给出分位数，让"该把 tolerance 定到多少"变成一个可以看数决定的问题。
 *
 * <h3>为什么存实测值而不是只存违规值</h3>
 * 只看违规值等于只看超出阈值的那部分——**永远无法知道离误报有多近**。
 * 存全部样本才能看出 p99 与阈值的间距；间距肉眼可见地小，就是该放松
 * （或者该查数据源）的信号。
 *
 * <p>线程：只允许主线程写入（检测本身跑在服务端主线程）。</p>
 */
class ReachSampler(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须为正" }
    }

    private val samples = DoubleArray(capacity)

    private var size = 0

    private var cursor = 0

    /** 自上次 [reset] 起累计的样本数（可能远超容量）。 */
    var totalCount: Long = 0L
        private set

    /** 上一次汇总输出的位置，用于"本周期新增多少样本"的判读。 */
    fun add(distance: Double) {
        if (distance.isNaN() || distance == Double.MAX_VALUE) return
        samples[cursor] = distance
        cursor = (cursor + 1) % capacity
        if (size < capacity) size++
        totalCount++
    }

    /** 当前环形缓冲内的有效样本数。 */
    fun size(): Int = size

    val isEmpty: Boolean get() = size == 0

    /**
     * 分位数（最近秩法：取排序后第 ⌈p·n⌉ 个）。
     *
     * @param p 0.0 ~ 1.0
     * @return 空样本时返回 `NaN`（调用方应据此跳过输出，而不是当成 0）
     */
    fun percentile(p: Double): Double {
        if (size == 0) return Double.NaN
        val rank = Math.ceil(p.coerceIn(0.0, 1.0) * size).toInt().coerceAtLeast(1)
        val sorted = sortedCopy()
        return sorted[rank - 1]
    }

    fun max(): Double {
        if (size == 0) return Double.NaN
        var best = samples[0]
        for (i in 1 until size) if (samples[i] > best) best = samples[i]
        return best
    }

    /** 超过给定阈值的样本数与占比——标定时用来判断"阈值周围有多少样本"。 */
    fun countAbove(threshold: Double): Int {
        var count = 0
        for (i in 0 until size) if (samples[i] > threshold) count++
        return count
    }

    fun reset() {
        size = 0
        cursor = 0
        totalCount = 0L
    }

    /**
     * 一行汇总，直接进控制台。
     *
     * @param threshold 当前生效阈值（`max-reach + 有效容差`）
     * @param maxReach 原版上限，用于把"离原版上限多远"一并打出来
     */
    fun summary(threshold: Double, maxReach: Double): String {
        if (size == 0) return "本周期无有效样本"
        val p50 = percentile(0.50)
        val p95 = percentile(0.95)
        val p99 = percentile(0.99)
        val max = max()
        val above = countAbove(threshold)
        return "n=" + size + " p50=" + f(p50) + " p95=" + f(p95) + " p99=" + f(p99) +
            " max=" + f(max) +
            " | 原版上限=" + f(maxReach) + " 阈值=" + f(threshold) +
            " 超阈值=" + above + " (" + f(100.0 * above / size) + "%)" +
            " | p99 距阈值 " + f(threshold - p99)
    }

    private fun sortedCopy(): DoubleArray {
        val copy = Arrays.copyOf(samples, size)
        Arrays.sort(copy)
        return copy
    }

    private fun f(value: Double): String = String.format("%.2f", value)

    companion object {
        /**
         * 默认容量。4096 个样本 ≈ 一次激烈团战的全部出手次数；
         * 每组 8 字节，常驻内存 32KB，可以忽略。
         */
        const val DEFAULT_CAPACITY = 4096
    }
}
