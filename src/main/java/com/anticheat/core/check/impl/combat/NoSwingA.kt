package com.anticheat.core.check.impl.combat

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData

/**
 * 攻击但没有挥手（NoSwing）。
 *
 * <h3>判据</h3>
 * 原版客户端的一次攻击必然由左键触发，而左键会**同时**发出两个包：
 * `ANIMATION`（挥手动画）与 `INTERACT_ENTITY(ATTACK)`。
 * 客户端若只发攻击包、抑制挥手包，唯一目的就是**隐藏攻击动作的视觉表现**
 * （俗称 silent aura / no-swing）——观战者看不到挥臂，回放里也难取证。
 *
 * <h3>为什么要求"连续 2 个 tick"</h3>
 * 两个包在同一 tick 内发出，但我们的 tick 边界是主线程的调度点，
 * 而包到达是网络线程的事。极端情况下挥手包可能刚好落在下一个 tick 里，
 * 造成一次性的错位。真实攻击者每 tick 都攻击，抑制挥手的话**每个 tick**
 * 都缺挥手；因此要求连续 2 个 tick 命中，既保留了检出能力，
 * 又完全消除了 tick 边界错位带来的假阳性。
 *
 * <h3>不会误伤的情形</h3>
 * - 只挥手不攻击（空挥、挖掘、放方块）：攻击数为 0，直接放行；
 * - 攻击数大于挥手数：这是**正常**的——按住左键自动连点时，
 *   攻击受 10 tick 冷却限制，挥手却不受，所以挥手只会更多不会更少；
 * - 传送窗口内：包序可能被打乱，整体让路。
 *
 * <p>参考 intave `check/combat/heuristics/other/NoSwingHeuristic`
 * （同样是"本 tick 有攻击无挥手"，这里额外加了一道连续性与传送让路）。</p>
 */
@CheckData(
    name = "NoSwingA",
    decay = 0.1,
    setback = 0.0,
    description = "攻击了却没有任何挥手包（silent aura / 隐藏挥臂）"
)
class NoSwingA(player: PlayerData) : Check(player), ServerTickListener {

    private var strike = 0

    override fun onServerTick() {
        val attacks = player.attacksThisTick

        if (attacks <= 0) {
            strike = 0
            reward()
            return
        }

        if (player.swingsThisTick > 0) {
            strike = 0
            reward()
            return
        }

        // 传送后包序不可信
        if (AntiCheatCore.tickManager.currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS) {
            strike = 0
            reward()
            return
        }

        strike++
        if (strike >= REQUIRED_TICKS) {
            strike = 0
            flag(
                "连续 " + REQUIRED_TICKS + " 个 tick 攻击 " + attacks +
                    " 次却没有任何挥手包（swingsThisTick=" + player.swingsThisTick + "）",
                VIOLATION_WEIGHT
            )
        }
    }

    companion object {
        /** 需要连续多少个 tick 命中才判定。 */
        const val REQUIRED_TICKS = 2

        const val TELEPORT_IMMUNITY_TICKS = 40L

        const val VIOLATION_WEIGHT = 2.0
    }
}
