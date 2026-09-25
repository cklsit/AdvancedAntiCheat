package com.anticheat.core.check.impl.world

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.BlockDigListener
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.CoreMath
import com.anticheat.core.util.update.BlockDigUpdate

/**
 * 大范围瞬间破坏（nuker / 秒杀群破）。
 *
 * <h3>为什么 [BreakRestartA] 覆盖不到它</h3>
 * [BreakRestartA] 的判据是"**同一个**方块被反复重启挖掘"，方块坐标一变就重置状态。
 * 而 nuker 类作弊恰好相反：它对**周围每一个方块各下手一次**，
 * 于是每个坐标都只出现一次，那条判据永远看不到异常。
 * 参考实现的 `Nuker`（Legit / Instant 两档）与 `Fucker`、`PacketMine` 都是这个形状。
 *
 * <h3>判据一：同一 tick 内开始挖掘多个方块（强判据）</h3>
 * 原版一次左键只对应一个 `START_DIGGING`，而客户端的点击处理是每 tick 一轮，
 * 因此**一个 tick 内不可能对三个不同方块下手**。这与 `FastPlaceA` 的
 * "同 tick 多次放置"同级：原版客户端产生不了，给较高权重。
 *
 * <p>门槛取 [MAX_STARTS_PER_TICK] = 3 而不是 1：火把、花、竹子这类**瞬间破坏**的方块，
 * 生存模式下一击即破，客户端会把 START 与 FINISH 连着发，
 * 高频点击（抖动点击约 16 CPS、蝴蝶点击约 20 CPS）确实能在一个 tick 里塞进两次。
 * 创造模式的一击即破更密集，但创造模式下 `getAllowFlight()` 为 true，
 * 会走 [PlayerData.movementPhysicsExempt] 整体让路。</p>
 *
 * <h3>判据二：挖掘的方块不在视线内（弱判据，余额法）</h3>
 * 原版要求准星落在方块上才能开挖。方块边长 1 格、最大交互距离 4.5 格，
 * 张角只有约 13 度；默认阈值给到 60 度，把"转身与挖掘之间的一拍错位"
 * 和"服务端眼睛位置最多落后一 tick"都盖住了。
 *
 * <p>参考实现的 `Nuker` 有 Legit 档（会真的把视角转过去），那一档命中的是判据一；
 * 不转视角的档位命中的是判据二。两条合起来才覆盖完整。</p>
 *
 * <h3>让路</h3>
 * - **服务端权威状态还没建立**（`serverWorld == null`）：眼睛位置全是初值 0，
 *   算出来的夹角没有意义；
 * - **传送免疫窗口**：传送后眼睛位置与客户端视角必然错开；
 * - **[PlayerData.movementPhysicsExempt]**：创造 / 旁观 / 骑乘等。
 *
 * <p>方块与眼睛几乎重合时（玩家站在自己要挖的方块里，例如挖脚下的雪层）
 * 方向向量长度趋近 0，夹角同样没有意义——[MIN_JUDGED_DISTANCE] 以下的样本直接跳过。</p>
 */
@CheckData(
    name = "NukerA",
    decay = 0.05,
    setback = 0.0,
    description = "同一 tick 内对多个方块下手 / 挖掘不在视线内的方块（nuker）"
)
class NukerA(player: PlayerData) : Check(player), BlockDigListener, ServerTickListener {

    /** 判据二的证据累积器。 */
    private var balance = 0.0

    @Volatile
    private var maxAngle: Double = DEFAULT_MAX_ANGLE

    @Volatile
    private var maxStartsPerTick: Int = MAX_STARTS_PER_TICK

    override fun onBlockDig(update: BlockDigUpdate) {
        if (!update.isStart) return
        if (!canJudge()) {
            balance = 0.0
            return
        }

        val dx = update.blockX + BLOCK_CENTER - player.serverEyeX
        val dy = update.blockY + BLOCK_CENTER - player.serverEyeY
        val dz = update.blockZ + BLOCK_CENTER - player.serverEyeZ
        val distanceSq = dx * dx + dy * dy + dz * dz
        // 几乎重合时方向无意义；顺带把"远得离谱"的样本也排掉（那属于数据不同步）
        if (distanceSq < MIN_JUDGED_DISTANCE * MIN_JUDGED_DISTANCE) {
            reward()
            return
        }

        val angle = CoreMath.angleOffViewDegrees(player.yaw, player.pitch, dx, dy, dz)
        if (angle <= maxAngle) {
            balance = (balance - DECAY_PER_HIT).coerceAtLeast(0.0)
            reward()
            return
        }

        balance += 1.0
        if (balance > FLAG_BALANCE) {
            balance -= FLAG_BALANCE * 0.5
            flag(
                "挖掘 " + update.blockX + "," + update.blockY + "," + update.blockZ +
                    " 时该方块偏离视线 " + String.format("%.1f", angle) + " 度（上限 " +
                    String.format("%.1f", maxAngle) + "，距离 " +
                    String.format("%.2f", kotlin.math.sqrt(distanceSq)) + " 格）",
                ANGLE_WEIGHT
            )
        }
    }

    override fun onServerTick() {
        if (!canJudge()) return
        val starts = player.digStartsThisTick
        if (starts > maxStartsPerTick) {
            flag(
                "同一 tick 内对 " + starts + " 个方块发起挖掘（原版一次左键只能开始 1 个）",
                IMPOSSIBLE_WEIGHT
            )
        }
    }

    private fun canJudge(): Boolean {
        // 权威状态还没建立：眼睛位置全是初值 0，夹角算出来是垃圾值
        if (player.serverWorld == null) return false
        if (player.movementPhysicsExempt) return false
        return AntiCheatCore.tickManager.currentTick - player.lastTeleportTick >= TELEPORT_IMMUNITY_TICKS
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        maxAngle = manager.optionDouble(configName, "max-angle", DEFAULT_MAX_ANGLE)
        maxStartsPerTick = manager.optionInt(configName, "max-starts-per-tick", MAX_STARTS_PER_TICK)
    }

    companion object {
        /** 方块中心相对整数坐标的偏移。 */
        const val BLOCK_CENTER = 0.5

        /**
         * 单 tick 内允许的 `START_DIGGING` 次数。
         *
         * <p>理论上就是 1；给到 3 是为了容忍瞬间破坏方块 + 高频点击的组合
         * （见类注释）。想更严可以下调，但**下调到 1 会把抖动点击挖火把判成作弊**。</p>
         */
        const val MAX_STARTS_PER_TICK = 3

        /**
         * 允许的"方块 vs 视线"夹角（度）。
         *
         * <p>几何上限只有约 13 度，60 度是很宽的余量，用来吸收
         * 转身与挖掘之间的一拍错位、以及服务端眼睛位置最多落后一 tick。
         * nuker 不转视角时夹角通常在 90 度以上，余量再大也盖不住。</p>
         */
        const val DEFAULT_MAX_ANGLE = 60.0

        /** 小于该距离（格）时方向向量没有意义，跳过判定。 */
        const val MIN_JUDGED_DISTANCE = 0.9

        /** 判据二的累积线：需要连续多次不在视线内的挖掘。 */
        const val FLAG_BALANCE = 4.0

        /** 判据二每次合规挖掘的降温量。 */
        const val DECAY_PER_HIT = 0.5

        /** 判据二的权重（统计推断类，留了被衰减掉的空间）。 */
        const val ANGLE_WEIGHT = 1.0

        /** 判据一的权重（原版不可能产生的协议级异常，与 FastPlaceA 同档）。 */
        const val IMPOSSIBLE_WEIGHT = 3.0

        /** 传送后的免疫时长（tick）。与其它位移类检测保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L
    }
}
