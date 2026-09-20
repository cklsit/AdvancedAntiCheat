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

    /**
     * 玩家朝向与该向量之间的夹角（度，0~180）。
     *
     * <p>朝向按 Minecraft 约定换算成视线单位向量：yaw 0 面向 +Z，pitch 越大越朝下。
     * </p>
     *
     * <pre>
     *   vx = -sin(yaw) * cos(pitch)
     *   vy = -sin(pitch)
     *   vz =  cos(yaw) * cos(pitch)
     * </pre>
     *
     * <p>把 yaw/pitch 的组合算成视线向量再求夹角，比"分别比较 yaw 差与 pitch 差"
     * 更严谨：后者在朝上/朝下时会把水平方向的偏差放大（俯视时身体朝向的影响变小），
     * 更容易误判。向量夹角在全方向上语义一致。</p>
     *
     * @param dx 目标方向分量（不必归一化）；长度为 0 时返回 0（无方向可言，不做判定）
     */
    @JvmStatic
    fun angleOffViewDegrees(yawDegrees: Float, pitchDegrees: Float, dx: Double, dy: Double, dz: Double): Double {
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (length < MIN_DIRECTION_LENGTH) return 0.0

        val yaw = Math.toRadians(yawDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        val cosPitch = kotlin.math.cos(pitch)

        val vx = -kotlin.math.sin(yaw) * cosPitch
        val vy = -kotlin.math.sin(pitch)
        val vz = kotlin.math.cos(yaw) * cosPitch

        val dot = (vx * dx + vy * dy + vz * dz) / length
        return Math.toDegrees(kotlin.math.acos(clamp(dot, -1.0, 1.0)))
    }

    /**
     * 视线单位向量的 x 分量。
     *
     * <p>与 [angleOffViewDegrees] 用的是同一套换算（yaw 0 面向 +Z、pitch 越大越朝下）。
     * 单独暴露三个分量而不是返回数组：射线求交（[RayBox]）在主线程每 tick 每目标都会
     * 调用，返回值数组会带来无谓的分配。</p>
     */
    @JvmStatic
    fun lookX(yawDegrees: Float, pitchDegrees: Float): Double =
        -kotlin.math.sin(Math.toRadians(yawDegrees.toDouble())) * kotlin.math.cos(Math.toRadians(pitchDegrees.toDouble()))

    @JvmStatic
    fun lookY(yawDegrees: Float, pitchDegrees: Float): Double =
        -kotlin.math.sin(Math.toRadians(pitchDegrees.toDouble()))

    @JvmStatic
    fun lookZ(yawDegrees: Float, pitchDegrees: Float): Double =
        kotlin.math.cos(Math.toRadians(yawDegrees.toDouble())) * kotlin.math.cos(Math.toRadians(pitchDegrees.toDouble()))

    private const val MIN_DIRECTION_LENGTH = 1.0e-6
}
