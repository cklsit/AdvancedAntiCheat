package com.anticheat.core.check.impl.reach

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData

/**
 * 攻击距离超限（Reach）。
 *
 * <h3>判据</h3>
 * 原版生存模式的上限是 **3.0 格**（从眼睛到目标命中盒最近点的距离，
 * 且原版还会把盒子外扩 0.1）。这里用服务端权威的眼睛位置与目标位置算距离，
 * 结果超过 `max-reach + tolerance` 才计入证据。
 *
 * <h3>为什么"不误报"要靠三件事同时成立</h3>
 * 1. **[TargetTracker] 的延迟补偿**：在玩家与目标各自最近 8 tick 的位置之间取
 *    **最小**距离，等于替玩家做了一次"最有利"的对齐。100ms 延迟下奔跑的目标
 *    会位移 0.6 格，不做补偿的话它能单独制造一次假阳性；
 * 2. **只累积、不单次判定**：每次超出只累加"超出量"（且单项封顶），
 *    累积超过 [FLAG_BALANCE] 才告警。单次偶发（GC 抖动、位置同步一拍）会被
 *    [DECAY_PER_TICK] 的持续降温吃掉；
 * 3. **多道让路**：骑乘 / 滑翔 / 传送窗口 / 刚登录 / 高延迟（>400ms）
 *    一律不判定，见 [TargetTracker.canJudge]。
 *
 * <h3>为什么阈值给到 3.85 而不是 3.0</h3>
 * `3.0` 是**理论**上限，而实际可观测的偏差还包括：服务端与客户端的位置差
 * （1 tick 的移动）、朝向/位置的量化、目标自身的姿态变化、
 * 以及"取历史最小"这个近似本身的残差。把阈值压在 3.0 会得到一条
 * **一定会误报**的检测。因此默认 `max-reach: 3.0` + `tolerance: 0.85`。
 *
 * <p>这个容差是**可调**的，而且建议先宽后紧：上线后先只看告警里的实测距离，
 * 观察一到两周的真实分布，再逐步收紧 `tolerance`。同样地，
 * 想更严就把 `tolerance` 降到 0.3 左右，但请同时把
 * `core.checks.ReachA.enabled` 之外的处罚保持关闭。</p>
 *
 * <p>参考实现：intave 的 `AttackRaytrace`（同样是"眼到命中盒"的距离 + 延迟补偿）；
 * Grim 的 `Reach`（同样用动态容差）。两者都需要实体位置历史，
 * 本实现用"取历史最小"替代它们的按延迟配对，换取更低的误报率。</p>
 */
@CheckData(
    name = "ReachA",
    decay = 0.05,
    setback = 0.0,
    description = "攻击距离超出原版上限（已做延迟补偿，只累积不单次判定）"
)
class ReachA(player: PlayerData) : Check(player), ServerTickListener {

    /** 首次使用时解析：避免依赖 CheckManager 登记表的先后顺序。 */
    private val tracker: TargetTracker? by lazy { player.checkManager.get(TargetTracker::class.java) }

    /** 证据累积器（单位：格）。 */
    private var balance = 0.0

    @Volatile
    private var maxReach: Double = DEFAULT_MAX_REACH

    @Volatile
    private var tolerance: Double = DEFAULT_TOLERANCE

    override fun onServerTick() {
        val tracker = this.tracker
        if (tracker == null) return
        tracker.ensureSampled()

        if (!tracker.canJudge()) {
            balance = 0.0
            reward()
            return
        }

        val eyes = tracker.eyes()
        val threshold = maxReach + tolerance
        var flagged = false

        for ((_, track) in tracker.tracks()) {
            if (!track.attackedThisTick) continue

            // 历史未填满就不判定：样本不足时"最小值"没有意义，
            // 而且此时最可能给出偏大的距离（补偿还没生效）
            if (!eyes.isFull || !track.history.isFull) continue

            val distance = eyes.minDistanceToBoxes(track.history, track.box.halfWidth, track.box.height)
            if (distance == Double.MAX_VALUE || distance > MAX_SANE_DISTANCE) continue
            if (distance <= threshold) continue

            balance += (distance - threshold).coerceAtMost(MAX_EXCESS_PER_HIT)

            if (balance > FLAG_BALANCE) {
                // 只扣掉一半而不是清零：让"持续超距"能连续告警，
                // 同时避免同一个累积量被反复用来触发多次
                balance -= FLAG_BALANCE * 0.5
                flagged = true
                flag(
                    "攻击距离 " + format(distance) + " 格（上限 " + format(maxReach) +
                        " + 容差 " + format(tolerance) + " = " + format(threshold) +
                        "，超出 " + format(distance - threshold) + "）目标=" + track.typeName +
                        " ping=" + player.ping + "ms",
                    VIOLATION_WEIGHT
                )
            }
        }

        if (!flagged) {
            balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)
            reward()
        }
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        maxReach = manager.optionDouble(configName, "max-reach", DEFAULT_MAX_REACH)
        tolerance = manager.optionDouble(configName, "tolerance", DEFAULT_TOLERANCE)
    }

    private fun format(value: Double): String = String.format("%.2f", value)

    companion object {
        /** 原版生存模式上限（格）。 */
        const val DEFAULT_MAX_REACH = 3.0

        /**
         * 额外容差（格）。默认 0.85，覆盖延迟补偿的残差与各类量化误差。
         * **这是本检测最重要的调参入口。**
         */
        const val DEFAULT_TOLERANCE = 0.85

        /**
         * 距离超过该值视为数据不可信（位置严重不同步），直接跳过判定。
         *
         * <p>16 格远超任何真实攻击场景：出现这种数说明目标与服务端状态严重分叉，
         * 此时据此判定没有意义，而且更可能是我们自己的数据出了问题。</p>
         */
        const val MAX_SANE_DISTANCE = 16.0

        /** 单次最多计入的证据量（格）。防止一次异常值直接顶满。 */
        const val MAX_EXCESS_PER_HIT = 1.5

        /** 累积到该值才告警（格）。 */
        const val FLAG_BALANCE = 3.0

        /** 每 tick 的降温量（格）。约 1 格/秒。 */
        const val DECAY_PER_TICK = 0.05

        const val VIOLATION_WEIGHT = 1.0
    }
}
