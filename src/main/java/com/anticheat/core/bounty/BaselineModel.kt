package com.anticheat.core.bounty

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** 指标的可疑方向：基线学习时要知道"偏哪边才可疑"。 */
enum class MetricDirection(val lowerIsSuspicious: Boolean) {
    /** 越小越可疑（抖动、熵、变异系数全都属于这一类）。 */
    LOWER_SUSPICIOUS(true),
    /** 越大越可疑（保留给将来可能引入的"过度修正"类指标）。 */
    HIGHER_SUSPICIOUS(false)
}

/**
 * 一个指标的人类基线。
 *
 * @param samples 参与学习的样本数。低于 [BaselineModel.minSamples] 时该指标不参与判定——
 *   **空基线绝不等于"正常"**，见 [BaselineModel.anomaly]。
 */
class MetricBaseline(
    val key: String,
    val mean: Double,
    val sd: Double,
    val samples: Long,
    val direction: MetricDirection = MetricDirection.LOWER_SUSPICIOUS
) {
    /**
     * 标准化偏差。**用绝对值除下界的 sd**：sd 接近 0 时 z 会趋于无穷，
     * 而"基线方差极小"通常意味着样本太少而不是"人类极其稳定"。
     */
    fun zScore(observed: Double): Double {
        val safeSd = if (sd < MIN_SD) MIN_SD else sd
        return (observed - mean) / safeSd
    }

    companion object {
        /** sd 下界。比这更小的一律按这个值算，避免 z 爆炸成天文数字。 */
        const val MIN_SD = 1.0e-4
    }
}

/** 单个指标对异常分的贡献（写进证据摘要，用来解释"为什么判它异常"）。 */
class MetricContribution(
    val key: String,
    val observed: Double,
    val baselineMean: Double,
    val baselineSd: Double,
    /** 标准化偏差；越小（或越大）越可疑，取决于方向。 */
    val z: Double,
    /** 该指标的"惊奇度" `-log10(p)`；越大说明越不可能来自人类基线。 */
    val surprise: Double
)

/**
 * 异常判定结果。
 *
 * @param score 0..100 的异常分。**它不是概率**，而是"多维惊奇度"的归一化结果，
 *   只用于和配置里的阈值比较，不要对外当作置信度展示。
 * @param ready 是否有足够多的指标可参与判定。[BaselineModel.anomaly] 在基线不足时
 *   返回 false，调用方必须回落成"只看检测证据"，**绝不能**把"基线不足"当成低分。
 */
class AnomalyResult(
    val score: Double,
    val ready: Boolean,
    val contributions: List<MetricContribution>
) {
    fun topContributors(limit: Int = 3): List<MetricContribution> =
        contributions.sortedByDescending { it.surprise }.take(limit)
}

/**
 * 人类行为基线模型：把多维行为指标与基线对比，得到 0..100 的异常分。
 *
 * <h3>为什么用"多维联合"而不是各维各自设阈值</h3>
 * 作弊者会刻意让**每一维**都落在正常范围内（"擦边"），但多维同时偏向同一侧的概率
 * 是各维概率的乘积——极低。这正是文档里说的"单维未超阈值，但多维联合概率极低"。
 * 实现上就是各维单侧尾概率取对数后求和（[surprise]）。
 *
 * <h3>基线不足时必须"弃权"</h3>
 * 没有基线数据时，"异常分"只会输出 0，而 0 在判定里意味着"完全正常"——那就把
 * "我们不知道"伪装成了"它是清白的"。所以 [ready] 为 false 时判定必须回落到
 * 纯检测证据路径（[BountyJudge] 里显式处理）。
 */
class BaselineModel(
    val baselines: Map<String, MetricBaseline>,
    /** 单个指标参与判定所需的最少人类样本数。 */
    val minSamples: Long = DEFAULT_MIN_SAMPLES,
    /** 至少要有这么多个指标可用，否则判定为"基线未就绪"。 */
    val minReadyMetrics: Int = DEFAULT_MIN_READY_METRICS,
    /** 惊奇度累加到多少算"满格"（score = 100）。 */
    val fullScaleSurprise: Double = DEFAULT_FULL_SCALE
) {

    /**
     * 基线是否可用于判定：至少 [minReadyMetrics] 个指标达到了 [minSamples] 样本。
     *
     * <p>调用方（判定）必须显式检查它。**"基线未就绪"不等于"行为正常"**——
     * 把没有基线当成低异常分，等于把"我们不知道"伪装成"他很清白"。</p>
     */
    fun ready(): Boolean = baselines.values.count { it.samples >= minSamples } >= minReadyMetrics

    fun anomaly(observed: Map<String, Double>): AnomalyResult {
        val contributions = ArrayList<MetricContribution>(observed.size)
        var totalSurprise = 0.0

        for ((key, value) in observed) {
            // 样本不足的指标（NaN）直接跳过：它没有信息量，不能当成 0
            if (value.isNaN()) continue
            val baseline = baselines[key] ?: continue
            if (baseline.samples < minSamples) continue

            val z = baseline.zScore(value)
            // z > 0 表示观测值高于基线均值。对"越小越可疑"的指标，
            // 可疑侧是 z < 0，单侧尾概率 p = Φ(z)。
            val p = if (baseline.direction.lowerIsSuspicious) normalCdf(z) else normalCdf(-z)
            val safeP = p.coerceIn(MIN_P, 1.0)
            // 归一化到"观测值恰好落在人类均值上"= 0：
            // 单侧尾概率在均值处是 0.5，直接取 -log10(p) 会让**完全正常的玩家**
            // 也拿到 0.3/指标 的基础分（5 个指标 ≈ 异常分 12）。
            // 减去 -log10(0.5) 等价于对各维尾概率的联合对数似然比做零假设归一化，
            // 于是"偏离人类多少个数量级"才真正成为分数的含义。
            val surprise = (-ln(safeP) / LN_10 - LN2_LOG10).coerceIn(0.0, MAX_SURPRISE_PER_METRIC)
            totalSurprise += surprise
            contributions.add(MetricContribution(key, value, baseline.mean, baseline.sd, z, surprise))
        }

        val ready = contributions.size >= minReadyMetrics
        val score = if (!ready) 0.0 else {
            (100.0 * totalSurprise / fullScaleSurprise).coerceIn(0.0, 100.0)
        }
        return AnomalyResult(score, ready, contributions)
    }

    fun describe(): String {
        if (baselines.isEmpty()) return "基线为空"
        val parts = baselines.entries.sortedBy { it.key }.map { (key, b) ->
            key + "=" + String.format("%.4f", b.mean) + "±" + String.format("%.4f", b.sd) +
                "(" + b.samples + ")"
        }
        return parts.joinToString(", ")
    }

    companion object {
        const val DEFAULT_MIN_SAMPLES = 200L
        const val DEFAULT_MIN_READY_METRICS = 2
        /** 惊奇度满格值：约等于 3 个指标各自 p≈1e-4 的联合。 */
        const val DEFAULT_FULL_SCALE = 12.0
        private const val MAX_SURPRISE_PER_METRIC = 6.0
        private const val MIN_P = 1.0e-9
        private const val LN_10 = 2.302585092994046

        /** log10(2)：把单侧尾概率归一化成"以均值为零点"的对数似然比。 */
        private const val LN2_LOG10 = 0.3010299956639812

        /** 标准正态分布函数 Φ(z)。 */
        @JvmStatic
        fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / SQRT2))

        /**
         * 误差函数，Abramowitz & Stegun 7.1.26（最大绝对误差 1.5e-7）。
         *
         * <p>自己实现而不是引 commons-math：本项目的 shade 规则对"多一个依赖"很敏感
         * （"谁提供同名类"已经出过两次线上事故），而这里只需要一个 7 项的近似式。</p>
         */
        @JvmStatic
        fun erf(x: Double): Double {
            val sign = if (x < 0.0) -1.0 else 1.0
            val ax = abs(x)
            val t = 1.0 / (1.0 + 0.3275911 * ax)
            val poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
            return sign * (1.0 - poly * exp(-ax * ax))
        }

        private const val SQRT2 = 1.4142135623730951
    }
}

/**
 * 基线学习器（Welford 在线均值/方差，纯逻辑）。
 *
 * <p>用在线算法而不是"把样本全存下来再算"：基线是**长期**统计，样本会无限增长，
 * 全留存既费内存又需要定期重算；Welford 每来一个样本只做常数次运算，
 * 数值稳定性也优于"先求和再求平方和"。</p>
 */
class BaselineLearner {

    private class State {
        var count: Long = 0
        var mean: Double = 0.0
        var m2: Double = 0.0
    }

    private val states = LinkedHashMap<String, State>()

    /** 吃进一次观测；[value] 为 NaN 时忽略（样本不足的指标不该污染基线）。 */
    fun observe(key: String, value: Double) {
        if (value.isNaN()) return
        val state = states.getOrPut(key) { State() }
        state.count++
        val delta = value - state.mean
        state.mean += delta / state.count
        state.m2 += delta * (value - state.mean)
    }

    fun observeAll(observed: Map<String, Double>) {
        for ((key, value) in observed) observe(key, value)
    }

    /** 从已有基线初始化（从库里读回来时用），之后继续增量学习。 */
    fun seed(baselines: Collection<MetricBaseline>) {
        for (baseline in baselines) {
            val state = states.getOrPut(baseline.key) { State() }
            state.count = baseline.samples
            state.mean = baseline.mean
            // 由 sd 反推 m2：sd² = m2 / (n - 1) ⇒ m2 = sd² * (n - 1)
            state.m2 = baseline.sd * baseline.sd * (baseline.samples - 1).coerceAtLeast(0)
        }
    }

    fun sampleCount(key: String): Long = states[key]?.count ?: 0L

    /** 累计吃进的观测条数（排障用：判断"基线攒了多久"）。 */
    fun totalObservations(): Long = states.values.sumOf { it.count }

    fun toModel(minSamples: Long = BaselineModel.DEFAULT_MIN_SAMPLES): BaselineModel =
        BaselineModel(snapshot().associateBy { it.key }, minSamples)

    /** 导出为可落库的基线快照（方向按当前约定固定：这几个指标都是"越小越可疑"）。 */
    fun snapshot(): List<MetricBaseline> = states.entries
        .filter { it.value.count >= 2 }
        .map { (key, state) ->
            val variance = if (state.count > 1) state.m2 / (state.count - 1).toDouble() else 0.0
            MetricBaseline(
                key = key,
                mean = state.mean,
                sd = sqrt(variance),
                samples = state.count,
                direction = MetricDirection.LOWER_SUSPICIOUS
            )
        }

    fun clear() = states.clear()
}
