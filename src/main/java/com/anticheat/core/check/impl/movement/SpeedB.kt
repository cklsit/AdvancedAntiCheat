package com.anticheat.core.check.impl.movement

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.PositionListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.math.DoubleRing
import com.anticheat.core.util.update.PositionUpdate

/**
 * 持续超速 —— 滑动窗口的**平均水平速度**超出物理上限。
 *
 * <h3>与 [SpeedA] 的分工：为什么需要两个速度检测</h3>
 * [SpeedA] 判的是**逐次**位移，因此它对"一两个 tick 的巨大位移"最敏感，
 * 代价是必须把阈值抬到 1.3 并保持实验性（默认不参与判定）——
 * 因为单次位移里混着两类与作弊无关的尖峰：
 *
 * <ol>
 *   <li>**客户端攒包/时序抖动**：多个 tick 的位移被合并到一条位置包送达，
 *       服务端读到的是"一拍走了两三拍的路"。实测证据包里单 tick 峰值
 *       可达 2.05 格，而同一段数据的 20 tick 平均值只有 0.75 格
 *       ——也就是说**单 tick 极值在这类环境下根本不能用来判定**（见下）；</li>
 *   <li>**击退 / 爆炸 / 弹射**：合法的瞬时冲量，已有外力免疫窗口兜底。</li>
 * </ol>
 *
 * <p>本检测换成**窗口平均值**，把上面第 1 类噪声天然抹平：一次 2 格的合并位移
 * 摊到 20 拍的窗口里只贡献 0.1。于是它能在保默认启用的同时，专注抓
 * **持续**超速（Speed / LongJump / YPort 这类"每一拍都比原版快"的模式）——
 * 这类作弊的平均值会稳定压在阈值以上，而正常玩家的平均值离阈值很远。</p>
 *
 * <h3>阈值依据（不是估的）</h3>
 * 证据包（赏金沙箱逐 tick 采样）实测：
 *
 * <table>
 *   <tr><th>场景</th><th>单 tick 峰值</th><th>20 tick 窗口平均</th></tr>
 *   <tr><td>主世界战斗（正常疾跑）</td><td>0.561</td><td><b>0.256</b></td></tr>
 *   <tr><td>战斗 + 飞行类作弊动作</td><td>2.051</td><td><b>0.751</b></td></tr>
 * </table>
 *
 * <p>普通地面疾跑的理论值约 0.28 格/tick，上面的 0.256 与之吻合；
 * 含飞行作弊动作的那段平均值 0.75 仍在合法范围内（蓝冰上连续疾跑跳跃
 * 可以稳定超过 1.0）。默认阈值 [DEFAULT_MAX_AVG_SPEED] = 1.5
 * 因此对合法玩法留了约 **2 倍**余量，抓的是"持续 2 格/tick 以上"的档位。</p>
 *
 * <h3>已知误报边界：冰面交通</h3>
 * 本仓库还没有地表模型（不知道玩家踩的是冰还是土），所以**蓝冰 / 浮冰长途交通**
 * 是本检测唯一现实的误报来源：冰道上连续疾跑跳跃可以稳定超过 1.0，
 * 熟练玩家可能接近 1.5。上线前请打开 `calibrate: true` 观察几个周期，
 * 确认自己服务器上"窗口平均"的实际分布（尤其是冰道玩家的 p99）再决定是否上调。</p>
 *
 * <h3>让路</h3>
 * 与 [SpeedA] 一致：[PlayerData.movementPhysicsExempt]（载具 / 鞘翅 / 允许飞行 /
 * 液体 / 梯子蜘蛛网等改写运动的方块 / 缓降漂浮等药水效果）、传送免疫窗口、
 * 外力（击退 / 爆炸）免疫窗口。迅捷药水整体让路是必需的——
 * 迅捷 II 能把合法速度抬到与部分作弊档位重合。</p>
 */
@CheckData(
    name = "SpeedB",
    decay = 0.05,
    setback = 0.0,
    description = "滑动窗口平均水平速度持续超出物理上限（Speed / LongJump）"
)
class SpeedB(player: PlayerData) : Check(player), PositionListener {

    /** 窗口内的水平位移样本（格/tick）。 */
    private val window = DoubleRing(WINDOW_TICKS)

    /** 证据累积器（单位：格/tick）。 */
    private var balance = 0.0

    @Volatile
    private var maxAvgSpeed: Double = DEFAULT_MAX_AVG_SPEED

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
            window.clear()
            balance = 0.0
            reward()
            return
        }

        window.add(update.deltaXZ)

        // 窗口未填满时不做判定：平均值在样本不足时方差极大，
        // 刚登录或刚让路结束的头几拍会给出毫无意义的均值
        if (!window.isFull) return

        val average = window.mean()

        if (calibrate) {
            // 标定要收**全部**窗口样本（不只是违规值）：只看超阈值的部分，
            // 永远无法知道阈值离误报还有多远
            sample(average)
            maybePrintCalibration()
        }

        if (average <= maxAvgSpeed) {
            balance = (balance - DECAY_PER_UPDATE).coerceAtLeast(0.0)
            reward()
            return
        }

        balance += (average - maxAvgSpeed).coerceAtMost(MAX_EXCESS_PER_UPDATE)
        if (balance > FLAG_BALANCE) {
            balance -= FLAG_BALANCE * 0.5
            flag(
                "窗口平均速度 " + format(average) + " 格/tick，超过上限 " + format(maxAvgSpeed) +
                    "（窗口 " + WINDOW_TICKS + " tick，累积 " + format(balance) + "）" +
                    " onGround=" + update.onGround + " 疾跑=" + player.sprinting,
                VIOLATION_WEIGHT
            )
        }
    }

    private fun sample(average: Double) {
        sampleCount++
        sampleSum += average
        if (average > sampleMax) sampleMax = average
        if (average > maxAvgSpeed) sampleAbove++
    }

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
            " | 上限=" + format(maxAvgSpeed) +
            " 超上限=" + sampleAbove + " (" + format(100.0 * sampleAbove / sampleCount) + "%)" +
            " | max 距上限 " + format(maxAvgSpeed - sampleMax)
        try {
            CoreLog.info("[SpeedB 标定] " + player.name + " " + summary)
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
        maxAvgSpeed = manager.optionDouble(configName, "max-avg-speed-per-tick", DEFAULT_MAX_AVG_SPEED)
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
         * 窗口长度（tick）。
         *
         * <p>1 秒与抖动的时间尺度匹配：够长，能把一次攒包补发摊平；
         * 够短，持续超速几秒内就能被抓住。窗口再长会让告警迟钝，
         * 再短则压不住单拍尖峰（见类注释）。</p>
         */
        const val WINDOW_TICKS = 20

        /**
         * 窗口平均水平速度上限（格/tick）。
         *
         * <p>1.5 的依据是"实测合法窗口平均 ≤ 0.75"，给出约 2 倍余量；
         * 同一份数据里单 tick 峰值为 2.05，本阈值也确实抓不到那种孤立尖峰
         * ——抓它是 [SpeedA] 的职责，本检测只对**持续**超速负责。
         * 想收紧请先用 `calibrate: true` 采到冰道玩家的实际分布。</p>
         */
        const val DEFAULT_MAX_AVG_SPEED = 1.5

        /**
         * 单次最多计入的证据量（格/tick）。
         *
         * <p>必须**小于** [FLAG_BALANCE]，否则一次极端超速就能顶到告警线
         * ——那会让"必须持续超速"这条性质失效。</p>
         */
        const val MAX_EXCESS_PER_UPDATE = 0.5

        /** 累积到该值才告警（格/tick）。 */
        const val FLAG_BALANCE = 1.0

        /** 每个合规更新的降温量。 */
        const val DECAY_PER_UPDATE = 0.05

        const val VIOLATION_WEIGHT = 1.0

        /**
         * 自动调参的硬下限（格/tick）。
         *
         * <p>与 `MovementCheckThresholdsTest` 的断言同源：阈值不得低于 1.2，
         * 否则会压到冰道交通与攒包噪声所在的合法区间。</p>
         */
        const val AUTO_TUNE_FLOOR = 1.2

        /** 标定汇总的默认间隔（秒）。与 SpeedA / ReachA 一致。 */
        const val DEFAULT_CALIBRATE_INTERVAL_SECONDS = 300

        /** 标定间隔的下限（秒）。低于 30 秒的输出没有统计意义，只是在刷日志。 */
        const val MIN_CALIBRATE_INTERVAL_SECONDS = 30

        /** 传送后的免疫时长（tick）。与其它位移类检测保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L

        /** 击退 / 爆炸后的免疫时长（tick）。 */
        const val VELOCITY_IMMUNITY_TICKS = 15L
    }
}
