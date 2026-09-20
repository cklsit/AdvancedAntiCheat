package com.anticheat.core.util.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 定长的三维点环（按写入顺序保存最近 [capacity] 个坐标），纯逻辑、可离线单测。
 *
 * <p><b>为什么需要"历史"而不是只看当前帧：</b>反作弊在服务端看到的时刻，
 * 与客户端做出判定时看到的时刻**不是同一时刻**。客户端先看到目标、再发攻击包，
 * 包还要走一个单程延迟才到服务端。若只用"收包这一刻"的目标位置去比对，
 * 一个正在奔跑的目标在 100ms 内就移动了 0.6 格——足已让一个完全正常的攻击
 * 被判成超距。把双方各取最近若干 tick 的位置，再取**最小**距离，
 * 就等价于替玩家做了一次"最有利"的延迟补偿。</p>
 *
 * <p><b>刻意取最小而不是按时间对齐配对：</b>按时间配对需要事务号/延迟反推，
 * 在没有那套基础设施时容易算错；取最小是有意的悲观放弃——它会给玩家
 * 比真实可解释范围更大的容差，代价是可能漏判，收益是几乎不会误判。
 * 这与"先确保没有误报"的优先级一致。</p>
 */
class PointHistory(val capacity: Int) {

    init {
        require(capacity > 0) { "容量必须为正" }
    }

    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val zs = DoubleArray(capacity)

    private var cursor = 0

    var size: Int = 0
        private set

    val isFull: Boolean get() = size == capacity

    fun add(x: Double, y: Double, z: Double) {
        xs[cursor] = x
        ys[cursor] = y
        zs[cursor] = z
        cursor = (cursor + 1) % capacity
        if (size < capacity) size++
    }

    /** 下标 0 = 最旧的样本，[size]-1 = 最新的样本。 */
    private fun slot(index: Int): Int =
        if (size < capacity) index else (cursor + index) % capacity

    fun x(index: Int): Double = xs[slot(index)]

    fun y(index: Int): Double = ys[slot(index)]

    fun z(index: Int): Double = zs[slot(index)]

    fun clear() {
        cursor = 0
        size = 0
    }

    /**
     * 本历史里所有点，到 [other] 里所有点所代表盒子（以点为**脚底中心**，
     * 水平半宽 [halfWidth]、高 [height]）的**最短**距离。
     *
     * @return 任一侧为空时返回 [Double.MAX_VALUE]（调用方应据此放弃判定）
     */
    fun minDistanceToBoxes(other: PointHistory, halfWidth: Double, height: Double): Double {
        if (size == 0 || other.size == 0) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until size) {
            val px = x(i)
            val py = y(i)
            val pz = z(i)
            for (j in 0 until other.size) {
                val d = distanceToBox(
                    px, py, pz,
                    other.x(j), other.y(j), other.z(j),
                    halfWidth, height
                )
                if (d < best) best = d
            }
        }
        return best
    }

    /** 到某个固定盒子的最短距离。 */
    fun minDistanceToBox(cx: Double, cy: Double, cz: Double, halfWidth: Double, height: Double): Double {
        if (size == 0) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until size) {
            val d = distanceToBox(x(i), y(i), z(i), cx, cy, cz, halfWidth, height)
            if (d < best) best = d
        }
        return best
    }

    companion object {

        /**
         * 点到轴对齐盒子的最短距离。
         *
         * <p>盒子的水平范围是 `[cx ± halfWidth]`，垂直范围是 `[cy, cy + height]`
         * —— **点代表脚底中心**，这是 Minecraft 实体坐标的约定
         * （`Location.y` 是脚底而不是中心）。把这点搞错会让所有距离整体偏移约一格。</p>
         */
        @JvmStatic
        fun distanceToBox(
            px: Double, py: Double, pz: Double,
            cx: Double, cy: Double, cz: Double,
            halfWidth: Double, height: Double
        ): Double {
            val dx = max(0.0, abs(px - cx) - halfWidth)
            val dz = max(0.0, abs(pz - cz) - halfWidth)
            // 垂直方向：分别算"在盒子下方"与"在盒子上方"的出界量
            val dy = max(0.0, max(cy - py, py - (cy + height)))
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
