package com.anticheat.core.check.impl.combat

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.HeldItemChangeListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.HeldItemUpdate

/**
 * 挖掘开始后立刻切换手持槽位（auto-tool / 挖掘加速）。
 *
 * <h3>判据</h3>
 * 原版玩家挖方块的动作序列是「按下左键 → 一直按住 → 方块破坏」。
 * 切换手持槽位需要松开左键去按数字键或滚轮，**动作上不可能与开始挖掘
 * 精确对齐到同一个 tick**，更不可能连续多次精确对齐。
 *
 * <p>而"自动换工具"类作弊（auto-tool）的典型实现是：用最快工具发起挖掘，
 * 然后在方块即将破坏的瞬间换成目标物品——它必须让切换与挖掘状态机
 * 在 tick 级别精确同步，于是呈现出"挖掘开始后 0~1 tick 内必定切槽"的规律。</p>
 *
 * <h3>为什么要求连续 3 次</h3>
 * 单次精确对齐可能只是巧合（玩家一边跑一边疯狂换手）。连续 3 次同规律的
 * 对齐，概率上已经不是手能做到的。这条判据权重刻意保持低（1.0）：
 * 它的价值在于和其它挖掘类判据（[com.anticheat.core.check.impl.world.BreakRestartA]）
 * 互相佐证，而不是单独定性。
 *
 * <h3>与参考实现的差异</h3>
 * 参考实现 intave `check/combat/heuristics/other/ToolSwitchHeuristic`
 * 的判定条件涉及"上次挖掘停止的 tick"与"上次槽位是否相同"，
 * 依赖它的 `AttackMetadata`/`MovementMetadata` 两个上下文对象。
 * 这里把它简化为**只用我们确实维护的状态**（是否正在挖掘、
 * 距开始挖掘过了几 tick）——语义更窄但可解释、可复现，
 * 且不会因为缺少上下文而给出无依据的结论。
 */
@CheckData(
    name = "ToolSwitchA",
    decay = 0.1,
    setback = 0.0,
    description = "挖掘开始后 1 tick 内切换手持槽位（自动换工具）"
)
class ToolSwitchA(player: PlayerData) : Check(player), HeldItemChangeListener {

    private var streak = 0

    override fun onHeldItemChange(update: HeldItemUpdate) {
        if (!player.breakingBlock) {
            streak = 0
            reward()
            return
        }

        val sinceStart = AntiCheatCore.tickManager.currentTick - player.digStartTick
        if (sinceStart > MAX_TICKS_AFTER_DIG_START) {
            streak = 0
            reward()
            return
        }

        streak++
        if (streak >= REQUIRED_STREAK) {
            streak = 0
            flag(
                "第 " + REQUIRED_STREAK + " 次在挖掘开始后 " + sinceStart +
                    " tick 内切换手持槽位（" + update.previousSlot + " -> " + update.slot + "）",
                VIOLATION_WEIGHT
            )
        }
    }

    companion object {
        /** "刚刚开始挖掘"的 tick 窗口。 */
        const val MAX_TICKS_AFTER_DIG_START = 1L

        /** 需要连续命中多少次。 */
        const val REQUIRED_STREAK = 3

        const val VIOLATION_WEIGHT = 1.0
    }
}
