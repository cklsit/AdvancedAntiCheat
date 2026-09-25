package com.anticheat.core.check.impl.movement

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.PositionListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.update.PositionUpdate

/**
 * 移动速度超限（Speed / LongJump / YPort）。**实验性检测，默认不参与判定。**
 *
 * <h3>判据</h3>
 * 逐次位置更新比较水平位移与 `max-speed-per-tick`，**只累积超出量**，
 * 攒够 [FLAG_BALANCE] 才告警（沿用 [com.anticheat.core.check.impl.reach.ReachA] 的
 * "只累积、不单次判定"）。单次封顶 [MAX_EXCESS_PER_UPDATE]，因此任何一次孤立事件
 * （爆炸、弹射、一次攒包补发）都不可能单独触发告警。
 *
 * <h3>为什么它是实验性的：没有地表模型</h3>
 * 原版允许的水平速度**取决于脚下是什么**：
 * 普通地面疾跑跳跃约 0.42 格/tick，而**蓝冰**上连续疾跑跳跃可以稳定跑到 1 格/tick 以上，
 * 而且是**持续**的——这正是本判据最难处理的一类合法高速。
 * 要把阈值压到能抓住参考实现里那些 0.5~0.8 格/tick 的 Speed 模式，
 * 就必须知道玩家此刻踩的是冰还是土，也就是需要方块与碰撞模型
 * （参考实现侧的核心层有完整的预测引擎，本仓库的还是骨架）。
 *
 * <p>所以默认阈值取 [DEFAULT_MAX_SPEED_PER_TICK] = 1.3，明显高于蓝冰的常见上限：
 * 它抓的是 LongJump、YPort 与参考实现里较激进的那几档 Speed（Vulcan288、HypixelBHop 等），
 * 代价是漏掉贴地小幅加速的那些档位。**这是刻意的取舍**：一个会把冰道玩家判成作弊的
 * 速度检测，比一个漏掉温和加速的速度检测糟糕得多。</p>
 *
 * <p>想收紧请先用 `calibrate: true` 在你自己的服务器上采一段分布，
 * 看清 p99 / max 落在哪里再动阈值；不要凭感觉下调。
 * 与 [com.anticheat.core.check.impl.reach.ReachA] 的标定不同的是，
 * 这里只给出 n / 均值 / max / 超阈值占比——够用来判断"阈值还有多少余量"，
 * 但给不出分位数。要做分位数标定需要先把 `ReachSampler` 从 reach 包里提升成通用采样器。</p>
 *
 * <h3>让路</h3>
 * [PlayerData.movementPhysicsExempt]（载具 / 鞘翅 / **允许飞行** / 液体 /
 * 梯子蜘蛛网 / 迅捷等药水效果）、传送免疫窗口、外力免疫窗口（击退 / 爆炸）。
 * 其中"迅捷药水"整项让路是必需的：迅捷 II 能把合法地面速度抬到与部分 Speed 模式重合，
 * 不给它让路就是误封。
 *
 * <h3>已知盲区</h3>
 * **激流（三叉戟 Riptide）**是客户端自己施加的冲量，服务端不会下发速度包，
 * 因此外力窗口盖不住它。一次激流能到 3 格/tick 以上——远超本阈值。
 * 目前的兜底是余额法：激流只有几拍，攒不够 [FLAG_BALANCE]，随后会被降温吸收。
 * 但连续使用激流赶路（水下交通的常见玩法）会持续命中，这是本检测保持实验性的原因之一。
 */
@CheckData(
    name = "SpeedA",
    decay = 0.05,
    setback = 0.0,
    description = "水平速度持续超出物理上限（Speed / LongJump；实验性，需先标定）",
    experimental = true
)
class SpeedA(player: PlayerData) : Check(player), PositionListener {

    /** 证据累积器（单位：格/tick）。 */
    private var balance = 0.0

    @Volatile
    private var maxSpeed: Double = DEFAULT_MAX_SPEED_PER_TICK

    @Volatile
    private var calibrate: Boolean = false

    @Volatile
    private var calibrateIntervalSeconds: Int = DEFAULT_CALIBRATE_INTERVAL_SECONDS

    // ---- 标定采样（只在 calibrate 打开时写入，避免常态下的无用开销）----
    private var sampleCount = 0L
    private var sampleSum = 0.0
    private var sampleMax = 0.0
    private var sampleAbove = 0L
    private var lastSummaryMillis = 0L

    override fun onPositionUpdate(update: PositionUpdate) {
        if (update.hasInvalidValue) return

        val currentTick = AntiCheatCore.tickManager.currentTick
        if (player.movementPhysicsExempt ||
            currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS ||
            currentTick - player.lastExternalVelocityTick < VELOCITY_IMMUNITY_TICKS
        ) {
            balance = 0.0
            reward()
            return
        }

        val speed = update.deltaXZ
        if (calibrate) {
            // 标定要收**全部**样本（不只是违规值）：只看超出阈值的部分，
            // 永远无法知道阈值离误报还有多远
            sample(speed)
            maybePrintCalibration()
        }

        if (speed <= maxSpeed) {
            balance = (balance - DECAY_PER_UPDATE).coerceAtLeast(0.0)
            reward()
            return
        }

        balance += (speed - maxSpeed).coerceAtMost(MAX_EXCESS_PER_UPDATE)
        if (balance > FLAG_BALANCE) {
            balance -= FLAG_BALANCE * 0.5
            flag(
                "水平速度 " + format(speed) + " 格/tick，超过上限 " + format(maxSpeed) +
                    "（累积 " + format(balance) + "）onGround=" + update.onGround +
                    " 疾跑=" + player.sprinting,
                VIOLATION_WEIGHT
            )
        }
    }

    private fun sample(speed: Double) {
        sampleCount++
        sampleSum += speed
        if (speed > sampleMax) sampleMax = speed
        if (speed > maxSpeed) sampleAbove++
    }

    /**
     * 标定模式下定期把分布打到控制台。
     *
     * <p>输出后清空：每个周期给出的是"这一段时间"的分布。
     * 累积开机以来的值会被早期的一次激流或爆炸永久拉高，看不出当前状态。</p>
     */
    private fun maybePrintCalibration() {
        if (!calibrate || sampleCount == 0L) return
        val now = System.currentTimeMillis()
        if (lastSummaryMillis == 0L) {
            lastSummaryMillis = now
            return
        }
        val interval = calibrateIntervalSeconds.coerceAtLeast(MIN_CALIBRATE_INTERVAL_SECONDS) * 1000L
        if (now - lastSummaryMillis < interval) return
        lastSummaryMillis = now

        val summary = "n=" + sampleCount +
            " avg=" + format(sampleSum / sampleCount) +
            " max=" + format(sampleMax) +
            " | 上限=" + format(maxSpeed) +
            " 超上限=" + sampleAbove + " (" + format(100.0 * sampleAbove / sampleCount) + "%)" +
            " | max 距上限 " + format(maxSpeed - sampleMax)
        try {
            CoreLog.info("[SpeedA 标定] " + player.name + " " + summary)
        } finally {
            sampleCount = 0L
            sampleSum = 0.0
            sampleMax = 0.0
            sampleAbove = 0L
        }
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        maxSpeed = manager.optionDouble(configName, "max-speed-per-tick", DEFAULT_MAX_SPEED_PER_TICK)
        calibrate = manager.optionBoolean(configName, "calibrate", false)
        calibrateIntervalSeconds = manager.optionInt(
            configName, "calibrate-interval-seconds", DEFAULT_CALIBRATE_INTERVAL_SECONDS
        )
        if (!calibrate) {
            sampleCount = 0L
            sampleSum = 0.0
            sampleMax = 0.0
            sampleAbove = 0L
        }
    }

    private fun format(value: Double): String = String.format("%.3f", value)

    companion object {
        /**
         * 默认单 tick 水平速度上限（格/tick）。
         *
         * <p>1.3 的依据是"要明显高于蓝冰疾跑跳跃的常见上限（约 1.0）"，
         * 而不是某个作弊档位的速度。普通地面疾跑跳跃只有约 0.42，
         * 也就是说这个阈值离合法的地面移动有 3 倍余量——余量这么大是有代价的
         * （漏掉温和加速），换来的是**不需要地表模型也不会误报**。见类注释。</p>
         */
        const val DEFAULT_MAX_SPEED_PER_TICK = 1.3

        /**
         * 单次最多计入的证据量（格/tick）。
         *
         * <p>必须**小于** [FLAG_BALANCE]，这是"单次异常不可能告警"这条性质的前提：
         * 激流与爆炸的瞬时速度可以到 3 以上，不封顶的话一次就能顶到告警线。</p>
         */
        const val MAX_EXCESS_PER_UPDATE = 0.5

        /** 累积到该值才告警（格/tick）。 */
        const val FLAG_BALANCE = 2.0

        /** 每个合规更新的降温量。 */
        const val DECAY_PER_UPDATE = 0.05

        const val VIOLATION_WEIGHT = 1.0

        /** 标定汇总的默认间隔（秒）。与 ReachA 一致。 */
        const val DEFAULT_CALIBRATE_INTERVAL_SECONDS = 300

        /** 标定间隔的下限（秒）。低于 30 秒的输出没有统计意义，只是在刷日志。 */
        const val MIN_CALIBRATE_INTERVAL_SECONDS = 30

        /** 传送后的免疫时长（tick）。与其它位移类检测保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L

        /** 击退 / 爆炸后的免疫时长（tick）。 */
        const val VELOCITY_IMMUNITY_TICKS = 15L
    }
}
