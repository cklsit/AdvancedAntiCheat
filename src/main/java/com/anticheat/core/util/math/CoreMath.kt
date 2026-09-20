package com.anticheat.core.util.math

import kotlin.math.abs
import kotlin.math.log2

/**
 * 反作弊用到的纯数学工具。全部无副作用、不依赖任何平台类型，可直接单测。
 *
 * <p>方法都带 `@JvmStatic`：单测与旧体系的 Java 代码要能直接 `CoreMath.xxx(...)` 调用，
 * 不必写 `CoreMath.INSTANCE.xxx(...)`。</p>
 */
object CoreMath {

    /**
     * 两个角度之间的**最短有向**差（度），落在 (-180, 180]。
     *
     * <p>必须处理环绕：yaw 从 179 变到 -179 实际只转了 2 度，
     * 直接相减得到 358 度——位移类与瞄准类判据都会被这个假值带飞。</p>
     */
    @JvmStatic
    fun deltaDegrees(from: Float, to: Float): Float {
        var diff = (to - from) % 360.0f
        if (diff <= -180.0f) diff += 360.0f
        if (diff > 180.0f) diff -= 360.0f
        return diff
    }

    /** 两个角度之间的最短无向距离（度），落在 [0, 180]。 */
    @JvmStatic
    fun distanceInDegrees(a: Float, b: Float): Float = abs(deltaDegrees(a, b))

    /**
     * 香农熵（比特）。输入是**样本序列**，内部按取值频次统计后再算 `-Σ p·log2(p)`。
     *
     * <p>为什么用熵而不是方差判断"点击间隔是否机械"：方差只衡量离散程度，
     * 无法区分「间隔在 95~105ms 之间均匀抖动」（人类，方差大）与
     * 「间隔只在 99/100/101ms 三个值之间跳」（宏，方差也不小，但取值种类极少）。
     * 熵同时反映**取值的丰富度**，对后者的分辨力更强。</p>
     *
     * @return 0.0 表示只有一个取值；样本数 < 2 时也返回 0.0（不足以下结论）
     */
    @JvmStatic
    fun shannonEntropyBits(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        // 取值频次：样本量最多几十个，用 HashMap 足够，不做更重的排序/分箱
        val counts = HashMap<Double, Int>(values.size * 2)
        for (v in values) {
            counts[v] = (counts[v] ?: 0) + 1
        }
        if (counts.size < 2) return 0.0
        val n = values.size.toDouble()
        var entropy = 0.0
        for (count in counts.values) {
            val p = count / n
            entropy -= p * log2(p)
        }
        return entropy
    }

    @JvmStatic
    fun clamp(value: Double, min: Double, max: Double): Double =
        if (value < min) min else if (value > max) max else value
}
