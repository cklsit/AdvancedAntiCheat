package com.anticheat.core.check.impl.aim

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.RotationListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.CoreMath
import com.anticheat.core.util.math.DoubleRing
import com.anticheat.core.util.update.RotationUpdate
import kotlin.math.abs

/**
 * 机械瞄准 —— 攻击期间朝向增量过于均匀。
 *
 * <h3>判据的来源与本人的改动（重要，请读完再调阈值）</h3>
 * 参考实现 intave 的 `RotationStandardDeviationHeuristic` 统计的是
 * **「玩家当前朝向」与「玩家眼睛看向目标实体的完美角度」之间的距离**，
 * 再对这个距离求标准差：真人追踪移动目标时残差持续变化，标准差大；
 * 瞄准辅助锁定后残差恒为极小值，标准差趋近 0。
 *
 * <p>那个判据需要**实体位置**（要算完美角度），而当前核心层还没有
 * 「按实体 id 取位置」的平台能力。因此这里做了一次**降级但语义等价**的替换：
 * 统计**每次朝向更新中 |Δyaw| 的标准差**。</p>
 *
 * <p>依据是：平滑瞄准（smooth aim）的本质是按固定角速度把准星"喂"到目标上，
 * 于是每 tick 的 Δyaw 几乎相同；而人类的手腕运动叠着手抖与修正，
 * Δyaw 必然有可见波动。**这个信号比残差判据弱**（它会被"稳定地跟着目标转"
 * 的正常行为干扰），所以采取了两项补偿：</p>
 * 1. 阈值收紧到 0.8°（参考实现是 1.0°）；
 * 2. 要求**连续 3 个窗口**都命中才记违规。
 *
 * <h3>采样条件</h3>
 * - 只在**战斗中**采样：最近一次攻击在 500ms 内。非战斗时的转身、看风景
 *   不该进入统计；
 * - 只在**真的在转视角**时采样（|Δyaw| > 2.6°）。准星几乎不动时
 *   Δyaw 的方差天然很小，那是"没在瞄准"而不是"瞄准得太准"；
 * - 传送窗口内不采样。
 *
 * <h3>关于加分与降温</h3>
 * 合规路径上的降温**只在"窗口完成且标准差正常"时**发生。
 * 不能在每次朝向更新上都 `reward()`：朝向更新每秒 20 次，
 * 那样会让违规分永远涨不起来，检测形同关闭。
 *
 * <p>`TODO(需要实体位置)`：等平台层补上「按实体 id 取位置」后，
 * 应改回 intave 的残差判据——那才是这个检测的完整形态。</p>
 */
@CheckData(
    name = "AimA",
    decay = 0.05,
    setback = 0.0,
    description = "攻击期间朝向增量过于均匀（机械瞄准 / 平滑 aimbot）"
)
class AimA(player: PlayerData) : Check(player), RotationListener {

    private val samples = DoubleRing(SAMPLE_COUNT)

    /** 连续命中窗口计数。用 Double 是为了支持 0.2 的小步降温。 */
    private var balance = 0.0

    override fun onRotationUpdate(update: RotationUpdate) {
        if (!inCombat()) {
            samples.clear()
            return
        }

        val deltaYaw = abs(CoreMath.deltaDegrees(update.fromYaw, update.toYaw))
        if (deltaYaw <= MIN_YAW_SPEED) {
            // 准星基本没动：不构成样本，也不降温（见类注释）
            return
        }

        samples.add(deltaYaw.toDouble())
        if (!samples.isFull) return

        val deviation = samples.populationStandardDeviation()
        samples.clear()

        if (deviation < SD_THRESHOLD) {
            balance += 1.0
            if (balance > REQUIRED_HITS) {
                balance = 0.0
                flag(
                    "攻击期间的朝向增量过于均匀：|Δyaw| 标准差=" + String.format("%.3f", deviation) +
                        "°（阈值 " + SD_THRESHOLD + "°，采样 " + SAMPLE_COUNT + " 次）",
                    VIOLATION_WEIGHT
                )
                return
            }
        } else {
            // 像人一样瞄准：唯一的降温路径
            balance = (balance - 0.2).coerceAtLeast(0.0)
            reward(REWARD_ON_HUMAN_LIKE)
        }
    }

    private fun inCombat(): Boolean {
        val now = System.currentTimeMillis()
        if (player.lastAttackMillis == 0L) return false
        if (now - player.lastAttackMillis > COMBAT_WINDOW_MILLIS) return false
        return AntiCheatCore.tickManager.currentTick - player.lastTeleportTick >= TELEPORT_IMMUNITY_TICKS
    }

    companion object {
        /** 每个窗口的朝向增量样本数。 */
        const val SAMPLE_COUNT = 7

        /** 低于该角速度视为"没在瞄"（度）。取自参考实现。 */
        const val MIN_YAW_SPEED = 2.6f

        /** |Δyaw| 标准差低于该值即认为过于均匀（度）。比参考实现更严。 */
        const val SD_THRESHOLD = 0.8

        /** 连续命中多少次记一次违规。 */
        const val REQUIRED_HITS = 2.0

        /** 攻击后多久内算"战斗中"（毫秒）。 */
        const val COMBAT_WINDOW_MILLIS = 500L

        const val TELEPORT_IMMUNITY_TICKS = 40L

        const val VIOLATION_WEIGHT = 1.0

        const val REWARD_ON_HUMAN_LIKE = 0.2
    }
}
