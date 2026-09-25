package com.anticheat.core.check.impl.movement

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.PositionListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.PositionUpdate

/**
 * 开着容器窗口时自主移动（InventoryMove / 边走边拿）。
 *
 * <h3>判据</h3>
 * 原版在**任何容器界面打开时都会忽略移动输入**：客户端根本不把 WASD 转成位移。
 * 因此"服务端开着窗口 + 客户端持续产生水平位移"这两件事不可能同时成立。
 * 参考实现的 `InventoryMove` 模块专门解掉这个限制（还提供 `UNDETECTABLE` 档位），
 * 典型用法是边打边从箱子里拿东西、或在战斗中一键换装而不损失机动性。
 *
 * <h3>只判水平位移</h3>
 * 窗口打开时**重力仍然生效**：站在高处开箱会照常往下掉。
 * 所以垂直分量完全不参与判据——把它算进来会让每个在边缘开箱的玩家都被判违规。
 *
 * <h3>累积的是"走过的距离"而不是"超标的 tick 数"</h3>
 * 窗口开着时玩家仍然可能被推动：别的玩家撞过来、水冲、活塞、以及刚被击退。
 * 这些都是**短距离、会自行停下**的。所以这里累加实际位移量，
 * 攒够 [FLAG_BALANCE] 格才告警：被撞一下约 0.2~0.3 格，会被降温吸收掉；
 * 而真的用 InventoryMove 走路的人每秒就有 2 格以上。
 *
 * <h3>为什么状态来源是"客户端认为开着窗口"而不是问 Bukkit</h3>
 * 沿用 [com.anticheat.core.events.packets.PacketInventoryTracker] 的窗口状态机
 * （服务端发 `OPEN_WINDOW` 置位、任一方 `CLOSE_WINDOW` 清位）。
 * 这与 `InventoryA` 用同一份状态，两个检测对"窗口是否打开"的判断因此永远一致。
 *
 * <h3>已知盲区（刻意的）</h3>
 * **玩家自己的背包（E 键）不产生 `OPEN_WINDOW`**——那是纯客户端界面，服务端不知情。
 * 所以本检测只覆盖容器（箱子 / 熔炉 / 工作台 / 商人 / 潜影盒 / 马匹背包…），
 * 覆盖不到"开着自身背包移动"。要覆盖它需要客户端主动上报界面状态，
 * 而那正是一个作弊客户端不会给的东西。
 *
 * <h3>状态卡住的防护</h3>
 * 窗口状态机是"宁松勿严"的，但**卡在"已打开"对本检测是致命的**：
 * 那会让一个正常玩家的所有移动都被判违规。除了任一方 `CLOSE_WINDOW` 会清位之外，
 * 服务端下发 `RESPAWN` 时也会清位（死亡与切换维度都会走这个包，
 * 而这两种情况下客户端不一定发 `CLOSE_WINDOW`）。见 `PacketInventoryTracker`。
 */
@CheckData(
    name = "InventoryMoveA",
    decay = 0.05,
    setback = 0.0,
    description = "容器窗口打开期间持续产生水平位移（InventoryMove）"
)
class InventoryMoveA(player: PlayerData) : Check(player), PositionListener {

    /** 证据累积器（单位：格）。 */
    private var balance = 0.0

    /** 本次连续开窗期间累计走过的距离，只用于告警文案。 */
    private var travelled = 0.0

    @Volatile
    private var minTickMovement: Double = MIN_TICK_MOVEMENT

    @Volatile
    private var flagBalance: Double = FLAG_BALANCE

    override fun onPositionUpdate(update: PositionUpdate) {
        // 含非法值的位移交给 BadPacketsA；NaN 会把累加器永久污染
        if (update.hasInvalidValue) return

        if (!player.inventoryOpen) {
            reset()
            reward()
            return
        }

        val currentTick = AntiCheatCore.tickManager.currentTick
        if (currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS ||
            currentTick - player.lastExternalVelocityTick < VELOCITY_IMMUNITY_TICKS
        ) {
            reset()
            reward()
            return
        }

        // 骑乘（含马匹背包）、液体（水流会推着人走）都不是自主移动
        if (player.serverInVehicle || player.serverInLiquid) {
            reset()
            reward()
            return
        }

        // 只看水平：开窗时重力照常生效，垂直位移完全合法
        val horizontal = update.deltaXZ
        if (horizontal < minTickMovement) {
            balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)
            reward()
            return
        }

        balance += horizontal
        travelled += horizontal

        if (balance > flagBalance) {
            // 只扣一半：让"持续走动"能连续告警，同时避免同一份累积量被反复使用
            balance -= flagBalance * 0.5
            flag(
                "容器窗口打开期间自主移动了 " + String.format("%.2f", travelled) +
                    " 格（本 tick " + String.format("%.3f", horizontal) +
                    " 格/tick，原版此时忽略移动输入）",
                VIOLATION_WEIGHT
            )
        }
    }

    private fun reset() {
        balance = 0.0
        travelled = 0.0
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        minTickMovement = manager.optionDouble(configName, "min-tick-movement", MIN_TICK_MOVEMENT)
        flagBalance = manager.optionDouble(configName, "flag-balance", FLAG_BALANCE)
    }

    companion object {
        /**
         * 单 tick 计入证据的最小水平位移（格）。
         *
         * <p>低于它的位移来自被推动后的滑行余量与浮点噪声，不值得累积。
         * 取 0.06：原版行走是 0.1 格/tick、疾跑 0.13，都被计入；
         * 而被撞一下的滑行会在两三拍内掉到这个值以下。</p>
         */
        const val MIN_TICK_MOVEMENT = 0.06

        /** 累积到该距离（格）才告警。约等于正常走路的 1 秒。 */
        const val FLAG_BALANCE = 2.0

        /** 每个"位移不足"的 tick 的降温量（格）。 */
        const val DECAY_PER_TICK = 0.05

        const val VIOLATION_WEIGHT = 1.0

        /** 传送后的免疫时长（tick）。与其它位移类检测保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L

        /** 击退 / 爆炸后的免疫时长（tick）。被推开不是自主移动。 */
        const val VELOCITY_IMMUNITY_TICKS = 15L
    }
}
