package com.anticheat.core.check.impl.world

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.BlockDigListener
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.BlockDigUpdate

/**
 * 同一方块被反复重启挖掘（fastbreak / nuker）。
 *
 * <h3>判据</h3>
 * 原版挖方块的状态机是「对同一个方块只发**一次** `START_DIGGING`，
 * 之后等方块破坏（`FINISH_DIGGING`）」。要再发一次 START，玩家必须
 * 松开左键再按下——两条 START 之间至少隔着他自己的按键动作。
 *
 * <p>fastbreak / nuker 类作弊为了不等方块硬度计时，会**高频重启挖掘**，
 * 让服务端的破坏进度计算不断被重置或累加，从而做到接近瞬破。
 * 它的特征就是"同一个方块位置在极短间隔内被反复 START"。</p>
 *
 * <h3>余额法而不是计数</h3>
 * 这里沿用和 [com.anticheat.core.check.impl.timer.TimerBalance] 同样的思路：
 *
 * - 重启间隔 ≥ 6 tick 视为**合规**（参考实现里原版间隔期望值就是 6 tick），
 *   余额乘 0.9 缓慢降温；
 * - 间隔 < 6 tick 时 `余额 += (6 - 间隔)`。间隔越短加得越多：
 *   连续 4 次"同 tick 重启"就能累积到 24，越过 20 的线。
 *
 * <p>比起"统计 N 秒内重启次数"，余额法能把"偶尔因为手滑重启一次"
 * 完全吸收掉（一次 +5 很快被合规重启的 0.9 乘回去），
 * 而对"持续高频重启"给出确定的累积。</p>
 *
 * <h3>为什么必须限定同一方块</h3>
 * 玩家边跑边挖时，每碰到一个新方块都会发 START，间隔可能只有 1~2 tick。
 * 那是**完全正常**的行为。只有**同一个方块坐标**被反复重启才是作弊信号——
 * 所以这里把方块坐标一起比较，坐标不同直接重置状态。
 *
 * <p>参考 intave `check/world/breakspeedlimiter/RestartCheck`
 * （期望间隔 6.0 tick、合规线 5.5、余额上限 20.0）。</p>
 */
@CheckData(
    name = "BreakRestartA",
    decay = 0.05,
    setback = 0.0,
    description = "同一个方块被反复重启挖掘（fastbreak / nuker）"
)
class BreakRestartA(player: PlayerData) : Check(player), BlockDigListener, ServerTickListener {

    private var lastStartTick = 0L

    private var lastX = NO_BLOCK

    private var lastY = NO_BLOCK

    private var lastZ = NO_BLOCK

    private var balance = 0.0

    override fun onBlockDig(update: BlockDigUpdate) {
        if (!update.isStart) return

        val tick = AntiCheatCore.tickManager.currentTick
        val sameBlock = update.blockX == lastX && update.blockY == lastY && update.blockZ == lastZ

        if (sameBlock && lastStartTick > 0L) {
            val delay = tick - lastStartTick
            if (delay >= COMPLIANT_RESTART_TICKS) {
                balance *= COMPLIANT_DECAY
            } else {
                balance += EXPECTED_RESTART_TICKS - delay
                if (balance > MAX_BALANCE) {
                    balance = 0.0
                    flag(
                        "同一方块 " + update.blockX + "," + update.blockY + "," + update.blockZ +
                            " 在 " + delay + " tick 内被重启挖掘（期望间隔 " +
                            EXPECTED_RESTART_TICKS + " tick）",
                        VIOLATION_WEIGHT
                    )
                }
            }
        } else if (!sameBlock) {
            // 换了方块：上一次的累积不再代表"同一方块的高频重启"，逐步释放
            balance *= COMPLIANT_DECAY
        }

        lastStartTick = tick
        lastX = update.blockX
        lastY = update.blockY
        lastZ = update.blockZ
    }

    override fun onServerTick() {
        // 长时间不挖掘时让余额自然回落，避免上周的证据残留到本周
        if (balance > 0.0) {
            balance *= IDLE_DECAY
            if (balance < 0.01) balance = 0.0
        }
        if (AntiCheatCore.tickManager.currentTick % 20L == 0L && balance == 0.0) {
            reward()
        }
    }

    companion object {
        /** 表示"还没有记录过任何方块"，与真实坐标 0 区分开（0 是合法坐标）。 */
        const val NO_BLOCK = Int.MIN_VALUE

        /** 原版期望的重启间隔（tick）。 */
        const val EXPECTED_RESTART_TICKS = 6.0

        /** 间隔大于等于它视为合规重启。 */
        const val COMPLIANT_RESTART_TICKS = 6L

        /** 合规重启时余额的衰减系数。 */
        const val COMPLIANT_DECAY = 0.9

        /** 每分钟无挖掘时的余额衰减系数（每 tick 一次）。 */
        const val IDLE_DECAY = 0.98

        /** 余额上限，超过即判定。 */
        const val MAX_BALANCE = 20.0

        const val VIOLATION_WEIGHT = 5.0
    }
}
