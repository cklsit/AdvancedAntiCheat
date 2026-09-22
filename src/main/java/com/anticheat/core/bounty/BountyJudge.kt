package com.anticheat.core.bounty

import kotlin.math.roundToInt

/**
 * 赏金判定结论。
 *
 * <p>比原来多了一个 [INCONCLUSIVE]：原实现只有三种结果，而 `evaluateResult()` 在
 * 没有任何证据时会返回"绕过成功"——于是**每次任务完成都全服广播"实现了潜在绕过"**。
 * "我们不知道"必须是一个显式状态，否则系统会持续输出假结论。</p>
 */
enum class BountyVerdict(val displayName: String, val description: String) {
    /** 现有检测规则有效：触发了仍在运行的检测打分，但没有发现绕过。 */
    DETECTED("检测成功", "你的操作已被现有检测识别"),
    /** 完成了任务目标，且全程没有被检测抓到。 */
    BYPASSED("绕过成功", "完成了目标而未被检测识别"),
    /** 完成目标 + 多维行为显著偏离人类基线。 */
    ZERO_DAY("高危发现", "行为模式超出既有检测与人类基线的覆盖范围"),
    /** 既没完成目标、也没被抓到：本次没有信息量，不发赏金。 */
    INCONCLUSIVE("无结论", "证据不足，本次不计入发现");

    /** 是否算一次"发现"（用于排行榜与案例队列）。 */
    val isFinding: Boolean get() = this == BYPASSED || this == ZERO_DAY
}

/** 判定置信度。写进案例，供管理员审核时排序。 */
enum class BountyConfidence(val displayName: String) {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低")
}

/**
 * 判定参数（来自 config.yml 的 `bounty.*`）。
 *
 * @param detectedMinVl 判定"被检测到"所需的 VL 门槛。默认与惩罚阶梯首档的证据门槛一致（8.0）——
 *   两处用同一个量级，是为了避免出现"惩罚认它、赏金不认它"这种自相矛盾的口径。
 */
class BountyTuning(
    val detectedMinVl: Double = 8.0,
    val bypassAnomalyThreshold: Double = 55.0,
    val zeroDayAnomalyThreshold: Double = 80.0,
    val baseReward: Int = 1,
    val bypassRewardMultiplier: Double = 1.0,
    val zeroDayReward: Int = 500
)

/** 判定输入。 */
class JudgeInput(
    /** 本任务期间触发过的检测次数（含低分命中）。 */
    val flags: Int,
    /** 本任务期间的最高 VL。 */
    val maxVl: Double,
    /** 结构化任务目标是否达成；null = 该任务没有可机判的目标。 */
    val objectiveMet: Boolean?,
    val anomaly: AnomalyResult
)

/** 判定输出。 */
class JudgeResult(
    val verdict: BountyVerdict,
    val confidence: BountyConfidence,
    /** 一句话说明判定依据（写进玩家提示、案例与审计）。 */
    val reason: String,
    val reward: Int
)

/**
 * 赏金判定（纯逻辑，可离线单测）。
 *
 * <h3>判定顺序为什么是这样</h3>
 * 1. **检测证据优先**。检测在沙箱里是照常打分的（只关惩罚、不关检测），
 *    所以"被抓到"是最可靠的一手证据；它直接给出 [BountyVerdict.DETECTED] 与保底赏金。
 * 2. **没被抓到 + 完成了目标**才算"绕过"。这里刻意**不用异常分作为准入条件**：
 *    跑了 10 秒杀戮光环杀掉 5 个傀儡却没有任何检测报警，本身就是绕过——
 *    异常分只决定它是普通绕过还是高危（[BountyVerdict.ZERO_DAY]），以及置信度。
 * 3. **目标未达成**时给 [BountyVerdict.INCONCLUSIVE]：什么都没证明，不给赏金。
 *
 * <p>"基线未就绪"被显式标注成低置信，而**不是**被当成异常分 0 从而否掉绕过判定——
 * 那是把"我们不知道"伪装成"他很清白"。</p>
 */
object BountyJudge {

    /**
     * @param taskBounty 该任务的名义赏金（来自任务目录），仅在绕过档用于计算实际奖励
     */
    @JvmStatic
    @JvmOverloads
    fun judge(input: JudgeInput, tuning: BountyTuning, taskBounty: Int = 10): JudgeResult {
        // ---- 1) 检测证据 ----
        if (input.maxVl >= tuning.detectedMinVl) {
            return JudgeResult(
                verdict = BountyVerdict.DETECTED,
                confidence = BountyConfidence.HIGH,
                reason = "被检测识别（最高 VL " + format(input.maxVl) + " ≥ " +
                    format(tuning.detectedMinVl) + "，命中 " + input.flags + " 次）",
                reward = tuning.baseReward
            )
        }

        // ---- 2) 明确没达成目标：没有信息量 ----
        if (input.objectiveMet == false) {
            return JudgeResult(
                verdict = BountyVerdict.INCONCLUSIVE,
                confidence = BountyConfidence.LOW,
                reason = "未达成任务目标，且未被判定为异常（最高 VL " + format(input.maxVl) + "）",
                reward = 0
            )
        }

        // ---- 3) 未被抓到：先看多维异常 ----
        // 这一支对 objectiveMet == null（自由测试，没有可机判的目标）同样成立：
        // 文档里"自由测试"的判据本来就是"出现系统未记录的异常行为模式"——异常分即判据。
        val anomaly = input.anomaly
        if (anomaly.ready && anomaly.score >= tuning.zeroDayAnomalyThreshold) {
            return JudgeResult(
                verdict = BountyVerdict.ZERO_DAY,
                confidence = BountyConfidence.HIGH,
                reason = "完成目标且未被检测识别，多维行为异常分 " + format(anomaly.score) +
                    " ≥ " + format(tuning.zeroDayAnomalyThreshold) + "（" + topAnomalyText(anomaly) + "）",
                reward = tuning.zeroDayReward
            )
        }
        if (anomaly.ready && anomaly.score >= tuning.bypassAnomalyThreshold) {
            return JudgeResult(
                verdict = BountyVerdict.BYPASSED,
                confidence = BountyConfidence.MEDIUM,
                reason = "完成目标且未被检测识别，异常分 " + format(anomaly.score) +
                    " ≥ " + format(tuning.bypassAnomalyThreshold) + "（" + topAnomalyText(anomaly) + "）",
                reward = scaledBypassReward(taskBounty, tuning)
            )
        }
        // ---- 4) 没有异常旁证：只剩"完成目标"这一项 ----
        if (input.objectiveMet == true) {
            // 置信度恒为 LOW：目标本身是**可以不作弊也能完成**的（从 A 点走到 B 点、
            // 或人肉硬打），所以它的正确处置是进人工复核队列，而不是自动给高额赏金。
            val detail = if (anomaly.ready) {
                "异常分 " + format(anomaly.score) + " 低于绕过线 " + format(tuning.bypassAnomalyThreshold)
            } else {
                "人类基线尚未就绪（样本不足），本次仅有「完成目标而未被抓到」这一项证据"
            }
            return JudgeResult(
                verdict = BountyVerdict.BYPASSED,
                confidence = BountyConfidence.LOW,
                reason = "完成目标且未被检测识别；" + detail + "，需人工复核",
                reward = scaledBypassReward(taskBounty, tuning)
            )
        }

        // ---- 5) 无目标 + 行为正常：本次没有信息量 ----
        return JudgeResult(
            verdict = BountyVerdict.INCONCLUSIVE,
            confidence = BountyConfidence.LOW,
            reason = "该任务没有可机判的目标" +
                (if (anomaly.ready) "" else "，且人类基线尚未就绪") +
                "，行为未偏离基线（最高 VL " + format(input.maxVl) + "）",
            reward = 0
        )
    }

    /** 绕过档的实际奖励 = 任务名义赏金 × 倍率（至少 1）。 */
    @JvmStatic
    fun scaledBypassReward(taskBounty: Int, tuning: BountyTuning): Int {
        val base = if (taskBounty < 1) 1 else taskBounty
        val scaled = (base * tuning.bypassRewardMultiplier).roundToInt()
        return if (scaled < 1) 1 else scaled
    }

    /** 把异常分的主要来源写成一句话（证据摘要用）。 */
    @JvmStatic
    fun topAnomalyText(anomaly: AnomalyResult): String {
        val top = anomaly.topContributors(2)
        if (top.isEmpty()) return "无可用指标"
        return top.joinToString("；") { contribution ->
            contribution.key + "=" + String.format("%.4f", contribution.observed) +
                "（基线 " + String.format("%.4f", contribution.baselineMean) +
                "，z=" + String.format("%.1f", contribution.z) + "）"
        }
    }

    private fun format(value: Double): String = String.format("%.2f", value)
}
