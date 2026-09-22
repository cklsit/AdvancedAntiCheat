package com.anticheat.core.bounty

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 行为基线指标（纯计算，无平台依赖）。
 *
 * <h3>这些指标在找什么</h3>
 * 单个指标都不足以定罪——它们全是**统计推断**，而统计推断必然有误报。
 * 它们的用途是给"检测没抓到、但玩家确实完成了作弊目标"这种情况提供**旁证**，
 * 让"绕过成功"从"检测器说没抓到"升级为"多维行为模式与人类显著不同"。
 *
 * <p>因此每个指标都被设计成"**越像机器越小**"：</p>
 * - [moveJitter]：人类的移动永远带手部微调与对抗性修正，二阶差分不为零；
 *   直线加速/飞行类作弊的二阶差分趋近 0。
 * - [turnEntropy] / [pitchEntropy]：自瞄把朝向"锁"在目标上，每步增量高度集中；
 *   人类是抖动式追随，增量分布更均匀。
 * - [attackIntervalCv]：人类点击间隔有明显波动；宏与自动点击器的间隔几乎恒定。
 * - [speedCv]：人类有加减速；速度作弊把水平速度钉死。
 *
 * <h3>两条必须遵守的纪律</h3>
 * 1. **静止样本必须剔除**。玩家不动时，抖动、转向增量、速度全都恒为 0，
 *    把它们算进平均会把指标摊薄——挂机玩家会被算成"极其像机器"。
 *    所以每个指标只在"该维度真的发生了变化"的步上统计。
 * 2. **样本不足时返回 [Double.NaN]**，而不是 0 或 -1。
 *    0 会被当成"极度可疑"，-1 会被当成合法观测值参与 z 分数；
 *    NaN 由 [BaselineModel] 显式跳过，语义上没有歧义。
 */
object BountyMetrics {

    const val KEY_MOVE_JITTER = "move-jitter"
    const val KEY_TURN_ENTROPY = "turn-entropy"
    const val KEY_PITCH_ENTROPY = "pitch-entropy"
    const val KEY_ATTACK_INTERVAL_CV = "attack-interval-cv"
    const val KEY_SPEED_CV = "speed-cv"

    /** 全部指标键，按固定顺序（日志与证据摘要靠它保持稳定）。 */
    @JvmStatic
    val allKeys: List<String> = listOf(
        KEY_MOVE_JITTER,
        KEY_TURN_ENTROPY,
        KEY_PITCH_ENTROPY,
        KEY_ATTACK_INTERVAL_CV,
        KEY_SPEED_CV
    )

    /** 一步位移小于该值视为"这一步没动"（格）。 */
    private const val STILL_EPSILON = 1.0e-4

    /** 一步转向小于该值视为"这一步没转"（度）。 */
    private const val TURN_EPSILON = 0.05

    /**
     * 一次性算出全部指标。
     *
     * @return 键 → 值；样本不足的指标值为 [Double.NaN]
     */
    @JvmStatic
    fun compute(samples: List<BountySample>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(allKeys.size)
        out[KEY_MOVE_JITTER] = moveJitter(samples)
        out[KEY_TURN_ENTROPY] = turnEntropy(samples)
        out[KEY_PITCH_ENTROPY] = pitchEntropy(samples)
        out[KEY_ATTACK_INTERVAL_CV] = attackIntervalCv(samples)
        out[KEY_SPEED_CV] = speedCv(samples)
        return out
    }

    /**
     * 水平移动的二阶差分平均模长（格 / tick²）。
     *
     * <p>只统计"前后两步都在移动"的窗口：静止与起步那一步的二阶差分天然为 0，
     * 混进来会把人类玩家的抖动平均掉。</p>
     */
    @JvmStatic
    fun moveJitter(samples: List<BountySample>): Double {
        if (samples.size < 3) return Double.NaN
        var sum = 0.0
        var count = 0
        for (i in 2 until samples.size) {
            val a = samples[i - 2]
            val b = samples[i - 1]
            val c = samples[i]
            val dx1 = b.x - a.x
            val dz1 = b.z - a.z
            val dx2 = c.x - b.x
            val dz2 = c.z - b.z
            if (dx1 * dx1 + dz1 * dz1 < STILL_EPSILON) continue
            if (dx2 * dx2 + dz2 * dz2 < STILL_EPSILON) continue
            val jx = dx2 - dx1
            val jz = dz2 - dz1
            sum += sqrt(jx * jx + jz * jz)
            count++
        }
        return if (count == 0) Double.NaN else sum / count
    }

    /** yaw 每步增量的归一化香农熵（0..1）。 */
    @JvmStatic
    fun turnEntropy(samples: List<BountySample>): Double = angularEntropy(samples) { it.yaw }

    /** pitch 每步增量的归一化香农熵（0..1）。 */
    @JvmStatic
    fun pitchEntropy(samples: List<BountySample>): Double = angularEntropy(samples) { it.pitch }

    /**
     * 攻击间隔的变异系数（标准差 / 均值）。
     *
     * <p>用采样序号之差当间隔，而不是墙钟毫秒：理由同 [BountySample.seq]。
     * 需要至少 3 次攻击才有 2 个间隔。</p>
     *
     * @return 样本不足时 NaN；均值含义上的"恒定"表现为接近 0
     */
    @JvmStatic
    fun attackIntervalCv(samples: List<BountySample>): Double {
        val ticks = ArrayList<Long>(16)
        for (sample in samples) {
            if (sample.attacked) ticks.add(sample.seq)
        }
        if (ticks.size < 3) return Double.NaN

        val intervals = DoubleArray(ticks.size - 1)
        for (i in 1 until ticks.size) {
            intervals[i - 1] = (ticks[i] - ticks[i - 1]).toDouble()
        }
        return coefficientOfVariation(intervals)
    }

    /**
     * 水平速度（格 / tick）的变异系数。
     *
     * <p>与抖动互补：抖动量的是"二阶差分有多大"，这个量的是"一阶速度有多稳"。
     * 只统计真的在动的步。</p>
     */
    @JvmStatic
    fun speedCv(samples: List<BountySample>): Double {
        if (samples.size < 3) return Double.NaN
        val speeds = ArrayList<Double>(samples.size)
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dx = b.x - a.x
            val dz = b.z - a.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < STILL_EPSILON) continue
            speeds.add(distance)
        }
        if (speeds.size < 3) return Double.NaN
        return coefficientOfVariation(speeds.toDoubleArray())
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 角度的逐步增量熵。
     *
     * <p>域固定为 `[-90, 90]` 度（超出夹取），分 [bins] 个桶。
     * 固定域而不是自适应域：自适应域会让"只转了两种角度"的样本也被归一化成均匀分布，
     * 恰好看不见自动化最典型的特征（增量集中在极少数取值上）。</p>
     */
    private inline fun angularEntropy(
        samples: List<BountySample>,
        bins: Int = 12,
        pick: (BountySample) -> Float
    ): Double {
        if (samples.size < 4) return Double.NaN
        val histogram = IntArray(bins)
        var turned = 0
        for (i in 1 until samples.size) {
            val delta = normalizeDegrees((pick(samples[i]) - pick(samples[i - 1])).toDouble())
            if (abs(delta) < TURN_EPSILON) continue
            val clamped = delta.coerceIn(-90.0, 90.0)
            // 把 [-90, 90] 映射到 [0, bins)
            val index = (((clamped + 90.0) / 180.0) * bins).toInt().coerceIn(0, bins - 1)
            histogram[index]++
            turned++
        }
        // 转过的步太少：不足以谈"熵"，返回 NaN 让上层跳过
        if (turned < 4) return Double.NaN

        var entropy = 0.0
        for (count in histogram) {
            if (count == 0) continue
            val p = count.toDouble() / turned
            entropy -= p * ln(p)
        }
        val normalizer = ln(bins.toDouble())
        return if (normalizer <= 0.0) Double.NaN else entropy / normalizer
    }

    /** 把角度差归一化到 `[-180, 180)`（yaw 跨 ±180 时不归一化会得到 359 这种假的大增量）。 */
    private fun normalizeDegrees(value: Double): Double {
        var v = value % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /** 变异系数；均值接近 0 时无意义，返回 NaN。 */
    private fun coefficientOfVariation(values: DoubleArray): Double {
        if (values.size < 2) return Double.NaN
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        if (abs(mean) < 1.0e-9) return Double.NaN
        var variance = 0.0
        for (v in values) {
            val d = v - mean
            variance += d * d
        }
        variance /= (values.size - 1).toDouble()
        return sqrt(variance) / abs(mean)
    }
}
