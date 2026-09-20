package com.anticheat.core.check.impl.reach

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog

/**
 * 攻击距离超限（Reach）。
 *
 * <h3>判据</h3>
 * 原版生存模式的上限是 **3.0 格**（从眼睛到目标命中盒最近点的距离，
 * 且原版还会把盒子外扩 0.1）。这里用服务端权威的眼睛位置与目标位置算距离，
 * 结果超过 `max-reach + 有效容差` 才计入证据。
 *
 * <h3>容差不是常数</h3>
 * 有效容差由 [ReachTolerance] 按玩家延迟算出：低延迟玩家 0.35 格，
 * 400ms 玩家 0.80 格。**一条固定容差必然在"收紧漏判"与"放松误报"之间二选一**，
 * 按延迟分档才能两头都站住。想退化成固定容差，把
 * `core.checks.ReachA.ping-slack` 设为 0 即可。
 *
 * <h3>为什么"不误报"要靠三件事同时成立</h3>
 * 1. **[TargetTracker] 的延迟补偿**：在玩家与目标各自最近 8 tick 的位置之间取
 *    **最小**距离。这个"取最小"有一个可证的边界：客户端出手时看到的目标位置、
 *    以及它自己当时的位置，都落在服务端这 8 tick 窗口内，只要单程延迟不超过
 *    400ms（即 RTT ≤ 800ms）这一对样本就一定在窗口里。它算出的距离**就是客户端
 *    当时看到的距离**。换句话说，合法攻击测出来的值不会因为延迟而虚大——
 *    这一点由 `ReachCalibrationTest` 的仿真固定住；
 * 2. **只累积、不单次判定**：每次超出只累加"超出量"（且单项封顶
 *    [MAX_EXCESS_PER_HIT]），累积超过 [FLAG_BALANCE] 才告警。因为单项封顶小于
 *    阈值，**任何一次孤立异常都不可能单独触发告警**（有测试守着这条）；
 * 3. **多道让路**：骑乘 / 滑翔 / 传送窗口 / 刚登录 / 高延迟（>400ms）
 *    一律不判定，见 [TargetTracker.canJudge]。
 *
 * <h3>阈值怎么标定（不要凭感觉改）</h3>
 * 把 `core.checks.ReachA.calibrate` 打开，[ReachSampler] 会按
 * `calibrate-interval-seconds` 打出实测距离的 `n / p50 / p95 / p99 / max`
 * 以及"距阈值还有多远"。观察几个周期后：若 p99 与阈值间距远大于 0.2 格，
 * 说明还有收紧空间（每次降 0.05~0.1）；若出现 `超阈值 > 0` 但当事人明显正常，
 * 那是数据源问题（该世界实体索引异常 / 目标频繁换世界），**先查原因再动阈值**。
 *
 * <p>参考实现：intave 的 `AttackRaytrace`（同样是"眼到命中盒"的距离 + 延迟补偿）；
 * Grim 的 `Reach`（同样用动态容差）。两者都需要实体位置历史，
 * 本实现用"取历史最小"替代它们的按延迟配对，换取更低的误报率。</p>
 */
@CheckData(
    name = "ReachA",
    decay = 0.05,
    setback = 0.0,
    description = "攻击距离超出原版上限（按延迟动态容差，只累积不单次判定）"
)
class ReachA(player: PlayerData) : Check(player), ServerTickListener {

    /** 首次使用时解析：避免依赖 CheckManager 登记表的先后顺序。 */
    private val tracker: TargetTracker? by lazy { player.checkManager.get(TargetTracker::class.java) }

    /** 证据累积器（单位：格）。 */
    private var balance = 0.0

    @Volatile
    private var maxReach: Double = DEFAULT_MAX_REACH

    /** 基础容差（低延迟档）。 */
    @Volatile
    private var tolerance: Double = ReachTolerance.BASE

    /** 高延迟档的额外放宽量；0 = 固定容差。 */
    @Volatile
    private var pingSlack: Double = ReachTolerance.MAX_PING_SLACK

    @Volatile
    private var calibrate: Boolean = false

    @Volatile
    private var calibrateIntervalSeconds: Int = DEFAULT_CALIBRATE_INTERVAL_SECONDS

    /** 上一次输出标定汇总的墙钟毫秒。用墙钟而不是 tick：间隔是给人看的。 */
    private var lastSummaryMillis = 0L

    /** 只在标定模式下写入，避免常态下的无用开销。 */
    private val sampler = ReachSampler()

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
        val effectiveTolerance = ReachTolerance.effective(player.ping, tolerance, pingSlack)
        val threshold = maxReach + effectiveTolerance
        // 允许的样本年龄按延迟给：给太大等于假设"客户端能看到很旧的目标位置"，
        // 会把作弊者的实测距离一起削掉（漏判）；给太小会排除掉客户端真正用过的那一对（误报）。
        // 两侧都由 ReachCalibrationTest 的标定表盯着。
        val maxAge = ReachTolerance.maxSampleAgeTicks(player.ping)
        var flagged = false

        for ((_, track) in tracker.tracks()) {
            if (!track.attackedThisTick) continue

            // 历史未填满就不判定：样本不足时"最小值"没有意义，
            // 而且此时最可能给出偏大的距离（补偿还没生效）
            if (!eyes.isFull || !track.history.isFull) continue

            val distance = eyes.minDistanceToBoxes(
                track.history, track.box.halfWidth, track.box.height, maxAge, maxAge
            )
            if (distance == Double.MAX_VALUE || distance > MAX_SANE_DISTANCE) continue

            // 标定模式：把**每一次合格判定的实测值**都收下来（不只是违规值），
            // 否则永远看不出"离误报有多近"
            if (calibrate) sampler.add(distance)

            if (distance <= threshold) continue

            balance += (distance - threshold).coerceAtMost(MAX_EXCESS_PER_HIT)

            if (balance > FLAG_BALANCE) {
                // 只扣掉一半而不是清零：让"持续超距"能连续告警，
                // 同时避免同一个累积量被反复用来触发多次
                balance -= FLAG_BALANCE * 0.5
                flagged = true
                flag(
                    "攻击距离 " + format(distance) + " 格（上限 " + format(maxReach) +
                        " + 容差 " + ReachTolerance.describe(player.ping, tolerance, pingSlack) +
                        " = " + format(threshold) +
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

        maybePrintCalibration(threshold)
    }

    /**
     * 标定模式下定期把样本分布打到控制台。
     *
     * <p>输出后清空样本：每个周期给出的是"这一段时间"的分布，
     * 而不是开机以来的累积——后者会被早期的一次异常永久拉高，看不出当前状态。</p>
     */
    private fun maybePrintCalibration(threshold: Double) {
        if (!calibrate || sampler.isEmpty) return
        val now = System.currentTimeMillis()
        if (lastSummaryMillis == 0L) {
            lastSummaryMillis = now
            return
        }
        val interval = calibrateIntervalSeconds.coerceAtLeast(MIN_CALIBRATE_INTERVAL_SECONDS) * 1000L
        if (now - lastSummaryMillis < interval) return
        lastSummaryMillis = now

        val name = player.name
        try {
            CoreLog.info(
                "[ReachA 标定] " + name + " ping=" + player.ping + "ms " +
                    sampler.summary(threshold, maxReach)
            )
        } finally {
            sampler.reset()
        }
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        maxReach = manager.optionDouble(configName, "max-reach", DEFAULT_MAX_REACH)
        tolerance = manager.optionDouble(configName, "tolerance", ReachTolerance.BASE)
        pingSlack = manager.optionDouble(configName, "ping-slack", ReachTolerance.MAX_PING_SLACK)
        calibrate = manager.optionBoolean(configName, "calibrate", false)
        calibrateIntervalSeconds = manager.optionInt(
            configName, "calibrate-interval-seconds", DEFAULT_CALIBRATE_INTERVAL_SECONDS
        )
        if (!calibrate) sampler.reset()
    }

    private fun format(value: Double): String = String.format("%.2f", value)

    companion object {
        /** 原版生存模式上限（格）。 */
        const val DEFAULT_MAX_REACH = 3.0

        /**
         * 距离超过该值视为数据不可信（位置严重不同步），直接跳过判定。
         *
         * <p>16 格远超任何真实攻击场景：出现这种数说明目标与服务端状态严重分叉，
         * 此时据此判定没有意义，而且更可能是我们自己的数据出了问题。</p>
         */
        const val MAX_SANE_DISTANCE = 16.0

        /**
         * 单次最多计入的证据量（格）。
         *
         * <p>必须**小于** [FLAG_BALANCE]，这是"单次异常不可能告警"这条性质的前提：
         * 满额的两次命中之间还夹着每 tick 的降温，因此实际需要连续多次超距。</p>
         */
        const val MAX_EXCESS_PER_HIT = 1.5

        /** 累积到该值才告警（格）。 */
        const val FLAG_BALANCE = 3.0

        /** 每 tick 的降温量（格）。约 1 格/秒。 */
        const val DECAY_PER_TICK = 0.05

        const val VIOLATION_WEIGHT = 1.0

        /** 标定汇总的默认间隔（秒）。5 分钟一次，足够看出分布又不会刷屏。 */
        const val DEFAULT_CALIBRATE_INTERVAL_SECONDS = 300

        /** 标定间隔的下限（秒）。低于 30 秒的输出没有统计意义，只是在刷日志。 */
        const val MIN_CALIBRATE_INTERVAL_SECONDS = 30
    }
}
