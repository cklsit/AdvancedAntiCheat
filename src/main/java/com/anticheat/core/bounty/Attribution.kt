package com.anticheat.core.bounty

import com.anticheat.core.check.impl.aim.AimC
import com.anticheat.core.check.impl.aim.RotationSnap
import com.anticheat.core.check.impl.autoclicker.AutoClickerD
import com.anticheat.core.check.impl.autoclicker.ClickStreaks
import com.anticheat.core.check.impl.movement.SpeedB
import com.anticheat.core.util.math.CoreMath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 归因：从「完成目标却没被抓到」反推「该收紧哪个判据、收紧到多少」。
 *
 * <h3>为什么必须做归因</h3>
 * `BYPASSED` 只说"没抓到"，**不说该调谁**。没有归因的自动调参等于闭着眼睛拧旋钮：
 * 把 `SpeedB` 的阈值降到地板也不会让一个瞬转型绕过被抓住第二次。
 * 所以自动化的第一步不是"改配置"，而是**回答"改哪一个"**。
 *
 * <h3>做法：把证据重放给各判据的纯逻辑实现</h3>
 * 把案例的逐 tick 采样喂给每个可重放判据，算出"这次行为会让该判据累积到多少分"，
 * 再除以它的当前阈值得到**接近度**（ratio）：
 *
 * - `ratio >= 1.0`：该判据**本来就会命中** → 不需要调（说明绕过发生在别处）；
 * - `ratio ∈ [minAttribution, 1.0)`：**最可能的归因目标**；
 * - `ratio < minAttribution`：离得太远，不属于该判据负责的维度。
 *
 * <h3>⚠ 重放必须复用判据自己的实现，不能另写一份</h3>
 * 这是本类最重要的一条纪律。判据的得分高度依赖**窗口口径**：
 * `AutoClickerD` 在运行时按 40 tick 窗口统计，若重放图省事用整段采样去算，
 * 得分会明显偏高（长段不被窗口截断），接近度被高估，**归因就会指向错误的检测**。
 * 因此这里一律调用 [RotationSnap] / [ClickStreaks] 的同一个实现，
 * 并把窗口长度取自同一个常量（[AutoClickerD.WINDOW_TICKS] / [SpeedB.WINDOW_TICKS]）。
 *
 * <p>为此本类刻意依赖 `core.check.impl.*`，与 `Check` → [BountyHooks] 的反向依赖
 * 构成包级循环。**这是有意接受的**：宁可包循环，也不能让归因与判据各写一份口径
 * —— 两份实现迟早会漂移，而漂移的结果是"调整了一个根本不相关的检测"。</p>
 *
 * <h3>能力边界</h3>
 * 只有**判据已纯逻辑化、且输入能用逐 tick 采样表达**的检测能重放。
 * `ReachA` / `FlyA` / `NukerA` 这类需要实体位置与方块的判据不在其中，
 * 它们只能继续走人工闸门。见 [REPLAYS] 的清单。
 */
object Attribution {

    /** 参与自动调参的配置键（与 config.yml 的 `core.checks.<检测>` 下同名）。 */
    const val PARAM_AIMC = "flag-balance"
    const val PARAM_AUTOCLICKER_D = "flag-vl"
    const val PARAM_SPEEDB = "max-avg-speed-per-tick"

    /** 一个判据在本次证据上的表现。 */
    class Candidate(
        val checkName: String,
        /** config.yml 里可调的参数键。 */
        val paramKey: String,
        /** 本次证据会让该判据累积到的分数。 */
        val score: Double,
        /** 该判据当前的阈值。 */
        val threshold: Double
    ) {
        /** 接近度：`>= 1.0` 表示按当前阈值本来就会命中。 */
        val ratio: Double
            get() = if (threshold <= 0.0) Double.NaN else score / threshold

        /** 是否已经能命中（无需调整）。 */
        val alreadyCaught: Boolean
            get() = ratio.isFinite() && ratio >= 1.0

        override fun toString(): String =
            checkName + "(" + paramKey + "=" + format(threshold) + ", 得分=" + format(score) +
                ", 接近度=" + format(ratio * 100.0) + "%)"
    }

    /** 一次具体的调整建议。 */
    class Proposal(
        val checkName: String,
        val paramKey: String,
        val oldValue: Double,
        val newValue: Double,
        val score: Double,
        val ratio: Double,
        /** 该参数在代码里声明的硬下限。 */
        val floor: Double,
        /** 是否被硬下限截断（截断说明"证据要求的收紧幅度超过了允许范围"）。 */
        val clampedByFloor: Boolean
    ) {
        override fun toString(): String =
            checkName + "." + paramKey + ": " + format(oldValue) + " -> " + format(newValue) +
                "（证据得分 " + format(score) + "，接近度 " + format(ratio * 100.0) + "%" +
                (if (clampedByFloor) "，已触及硬下限 " + format(floor) else "") + "）"
    }

    /**
     * 可重放判据登记表。
     *
     * <p>新增一个可重放判据需要三件事同时具备：判据逻辑已抽成不依赖平台类型的类、
     * 判据的阈值是可配置项（不是编译期常量）、以及它在代码里声明了硬下限。</p>
     */
    private val REPLAYS: List<Replay> = listOf(
        Replay("AimC", PARAM_AIMC, ::scoreAimC),
        Replay("AutoClickerD", PARAM_AUTOCLICKER_D, ::scoreAutoClickerD),
        Replay("SpeedB", PARAM_SPEEDB, ::scoreSpeedB)
    )

    /** 某个检测声明的自动调参硬下限；未声明返回 null（= 该检测不参与自动调参）。 */
    @JvmStatic
    fun floorOf(checkName: String): Double? = when (checkName) {
        "AimC" -> AimC.AUTO_TUNE_FLOOR
        "AutoClickerD" -> AutoClickerD.AUTO_TUNE_FLOOR_FLAG_VL
        "SpeedB" -> SpeedB.AUTO_TUNE_FLOOR
        else -> null
    }

    /**
     * 对证据做归因。
     *
     * @param samples 任务期间的逐 tick 采样（调用方负责裁掉任务开始之前的）
     * @param thresholds 检测名 → 当前阈值（调用方从 `CoreConfigManager` 读，注意**库优先**）
     * @return 全部候选，按接近度降序；阈值缺失的检测会被跳过
     */
    @JvmStatic
    fun candidates(
        samples: List<BountySample>,
        thresholds: Map<String, Double>
    ): List<Candidate> {
        if (samples.size < MIN_SAMPLES) return emptyList()

        val out = ArrayList<Candidate>(REPLAYS.size)
        for (replay in REPLAYS) {
            val threshold = thresholds[replay.checkName] ?: continue
            if (threshold <= 0.0) continue
            val score = replay.score(samples, threshold)
            if (score.isNaN() || score <= 0.0) continue
            out.add(Candidate(replay.checkName, replay.paramKey, score, threshold))
        }
        out.sortByDescending { if (it.ratio.isFinite()) it.ratio else Double.NEGATIVE_INFINITY }
        return out
    }

    /**
     * 从候选算出具体的调整建议。
     *
     * <h3>三个约束共同决定新值，取其中最保守的一个</h3>
     * 1. **语义要求**：新阈值必须低到让本次证据能命中，即 ≤ `score × safetyMargin`；
     * 2. **单次幅度**：一次最多收紧 `maxStep`（默认 10%），即 ≥ `threshold × (1 - maxStep)`；
     * 3. **硬下限**：不得低于 [floorOf] 声明的值。
     *
     * <p>取三者中**最大**的值（= 最接近旧值的那一个），于是：
     * 证据要求的幅度再大，一次也只走 `maxStep` —— 需要多轮才收敛。
     * 这是刻意的保守：归因本身有偏差（窗口对齐、样本量），
     * 一次调到位会把归因偏差直接变成误报。</p>
     *
     * @return null 表示"没有实质收紧"，包括：该判据本来就能命中、
     *   或者三个约束算出来的新值并未低于旧值
     */
    @JvmStatic
    @JvmOverloads
    fun propose(
        candidate: Candidate,
        floor: Double = floorOf(candidate.checkName) ?: Double.NEGATIVE_INFINITY,
        maxStep: Double = DEFAULT_MAX_STEP,
        safetyMargin: Double = DEFAULT_SAFETY_MARGIN
    ): Proposal? {
        if (candidate.alreadyCaught) return null
        if (!candidate.ratio.isFinite() || candidate.ratio <= 0.0) return null

        val old = candidate.threshold
        val needed = candidate.score * safetyMargin
        val stepped = old * (1.0 - maxStep.coerceIn(0.0, MAX_MAX_STEP))
        val desired = maxOf(stepped, needed, floor)

        // 容差是必需的，不是保险起见：上面三条分支全都带浮点误差
        // （实测 60 * 0.9 = 54.000000000000004、50 * 1.1 = 55.00000000000001），
        // 直接比较会把"恰好落在下限上"判成"高于下限"，
        // 于是 clampedByFloor 撒谎、desired >= old 也可能把"没收紧"当成有效调整。
        if (desired >= old - EPSILON) return null

        return Proposal(
            checkName = candidate.checkName,
            paramKey = candidate.paramKey,
            oldValue = old,
            newValue = desired,
            score = candidate.score,
            ratio = candidate.ratio,
            floor = floor,
            clampedByFloor = desired <= floor + EPSILON
        )
    }

    // ------------------------------------------------------------------ 各判据的重放

    /**
     * 复现 [AimC] 的逐 tick 累积：三拍指纹 + 权重 + 每 tick 降温。
     *
     * <p>战斗门用"本拍或之前 [COMBAT_WINDOW_TICKS] 拍内有过攻击"——
     * 运行时是 `now - lastAttackMillis <= 300ms`，300ms 正好是 6 拍。</p>
     */
    private fun scoreAimC(samples: List<BountySample>, unusedThreshold: Double): Double {
        val motions = ArrayList<Double>(samples.size)
        val combat = ArrayList<Boolean>(samples.size)
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            if (current.seq - previous.seq != 1L) {
                motions.add(Double.NaN)
                combat.add(false)
                continue
            }
            motions.add(abs(CoreMath.deltaDegrees(previous.yaw, current.yaw)).toDouble())
            combat.add(attackWithin(samples, i, COMBAT_WINDOW_TICKS))
        }

        var balance = 0.0
        var peak = 0.0
        for (i in 1 until motions.size - 1) {
            // 与 AimC.onServerTick 一致：每 tick 先降温，再判定
            balance = (balance - AimC.DECAY_PER_TICK).coerceAtLeast(0.0)

            val twoAgo = motions[i - 1]
            val middle = motions[i]
            val current = motions[i + 1]
            if (twoAgo.isNaN() || middle.isNaN() || current.isNaN()) continue
            if (!combat[i]) continue
            if (!RotationSnap.isSnap(twoAgo, middle, current)) continue

            balance += RotationSnap.weight(middle)
            peak = max(peak, balance)
        }
        return peak
    }

    /**
     * 复现 [AutoClickerD] 的窗口统计。
     *
     * <p>运行时的窗口是**不重叠**的连续 [AutoClickerD.WINDOW_TICKS] 拍块，
     * 但块边界取决于玩家何时登录——从采样里推不出来。这里用**滑动窗口**
     * 取最大值，即运行时得分的**上界**。</p>
     *
     * <p>方向是刻意选的：高估得分 → 接近度偏高 → 更可能被选中。
     * 代价由 [propose] 的单次幅度上限与硬下限吸收，而低估会让归因永远选不中。
     * 另外自动调参本来就有观察期回滚兜底。</p>
     */
    private fun scoreAutoClickerD(samples: List<BountySample>, unusedThreshold: Double): Double {
        val acted = ArrayList<Boolean>(samples.size)
        val multi = ArrayList<Boolean>(samples.size)
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            if (current.seq - previous.seq != 1L) {
                acted.add(false)
                multi.add(false)
                continue
            }
            acted.add(current.attacked)
            multi.add(false) // 采样里没有"同 tick 多次"的信息，按无双击处理（对得分偏保守）
        }

        val window = AutoClickerD.WINDOW_TICKS
        if (acted.size < window) return 0.0

        val actedArray = acted.toBooleanArray()
        val multiArray = multi.toBooleanArray()
        var best = 0.0
        for (start in 0..actedArray.size - window) {
            val sliceActed = actedArray.copyOfRange(start, start + window)
            val sliceMulti = multiArray.copyOfRange(start, start + window)
            val vl = ClickStreaks.violationLevel(sliceActed, sliceMulti)
            best = max(best, vl)
        }
        return best
    }

    /** 复现 [SpeedB] 的滑动窗口平均速度，取最大值。 */
    private fun scoreSpeedB(samples: List<BountySample>, unusedThreshold: Double): Double {
        val speeds = ArrayList<Double>(samples.size)
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            if (current.seq - previous.seq != 1L) continue
            speeds.add(hypot(current.x - previous.x, current.z - previous.z))
        }

        val window = SpeedB.WINDOW_TICKS
        if (speeds.size < window) return 0.0

        var best = 0.0
        for (start in 0..speeds.size - window) {
            var sum = 0.0
            for (j in start until start + window) sum += speeds[j]
            best = max(best, sum / window)
        }
        return best
    }

    /** 第 [index] 个采样起、往前 [ticks] 拍内是否发生过攻击。 */
    private fun attackWithin(samples: List<BountySample>, index: Int, ticks: Int): Boolean {
        val from = max(0, index - ticks)
        for (i in from..index) {
            if (samples[i].attacked) return true
        }
        return false
    }

    /** 一个可重放的判据。 */
    private class Replay(
        val checkName: String,
        val paramKey: String,
        val score: (List<BountySample>, Double) -> Double
    )

    /** 样本太少时不做归因（几个 tick 的统计没有意义）。 */
    const val MIN_SAMPLES = 40

    /** AimC 的战斗窗口（tick）。与 AimC.COMBAT_WINDOW_MILLIS = 300ms 对应。 */
    const val COMBAT_WINDOW_TICKS = 6

    /** 单次最大收紧幅度（占当前阈值的比例）。 */
    const val DEFAULT_MAX_STEP = 0.10

    /** 新阈值相对证据得分留多少余量。 */
    const val DEFAULT_SAFETY_MARGIN = 1.10

    /** `max-step` 的绝对上限：配置写大了也不能一次降超过三成。 */
    const val MAX_MAX_STEP = 0.30

    /** 浮点比较容差。见 [propose] 里的说明。 */
    private const val EPSILON = 1.0e-9

    private fun format(value: Double): String =
        if (value.isFinite()) String.format("%.3f", value) else "NaN"
}
