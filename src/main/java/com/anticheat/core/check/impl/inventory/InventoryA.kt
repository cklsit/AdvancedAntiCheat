package com.anticheat.core.check.impl.inventory

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.InventoryClickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.InventoryClickUpdate

/**
 * 未打开窗口却点击容器。
 *
 * <h3>判据</h3>
 * 服务端发出 `OPEN_WINDOW` 之后客户端才可能去点那个窗口。因此
 * 「客户端点击了 windowId 非 0 的窗口，而服务端侧记录的状态是"没有任何窗口打开"」
 * 只可能来自：作弊客户端的自动整理/自动拾取模块，或者被改坏的状态机。
 *
 * <h3>为什么状态由包层自己维护</h3>
 * 判据比较的是「**客户端认为**的状态」与「**服务端认为**的状态」。
 * 如果去问 Bukkit `getOpenInventory()`，拿到的永远是服务端真实状态，
 * 那这个检测就退化成恒真或恒假，毫无意义。所以
 * [PlayerData.inventoryOpen] / [PlayerData.openWindowId] 由
 * [com.anticheat.core.events.packets.PacketInventoryTracker] 从
 * 服务端发包（`OPEN_WINDOW`）与客户端收包（`CLOSE_WINDOW`）自己拼出来。
 *
 * <h3>三道让路</h3>
 * 1. `windowId == 0` 是玩家自己的背包，任何时候点击都合法；
 * 2. `windowId` 与服务端记录的 id 一致时放行（正常情况下必然一致）；
 * 3. 刚登录的前 100 tick 完全放行——登录阶段服务端的 `OPEN_WINDOW`
 *    与客户端的第一个点击可能在网络上交错，那属于包序竞态而非作弊。
 *
 * <p>状态机刻意做成"只会在服务端发包时置为已打开"，
 * 因此一旦我们漏掉了某个 `OPEN_WINDOW`，后果是**漏判**而不是误判——
 * 这个方向的错误是可以接受的。</p>
 */
@CheckData(
    name = "InventoryA",
    decay = 0.05,
    setback = 0.0,
    description = "在服务端未打开任何容器窗口的情况下点击了容器"
)
class InventoryA(player: PlayerData) : Check(player), InventoryClickListener {

    override fun onInventoryClick(update: InventoryClickUpdate) {
        // 自身背包：任何时刻都合法
        if (update.isPlayerInventory) {
            reward()
            return
        }

        // 服务端确实开着窗口，且 id 对得上
        if (player.inventoryOpen && update.windowId == player.openWindowId) {
            reward()
            return
        }

        // 登录初期的包序竞态
        val sinceJoin = AntiCheatCore.tickManager.currentTick - player.joinTick
        if (sinceJoin < JOIN_GRACE_TICKS) {
            reward()
            return
        }

        flag(
            "点击 windowId=" + update.windowId + " slot=" + update.slot +
                "，但服务端记录为未开窗（openWindowId=" + player.openWindowId + "）",
            VIOLATION_WEIGHT
        )
    }

    companion object {
        /** 登录后的放行时长（tick）。 */
        const val JOIN_GRACE_TICKS = 100L

        const val VIOLATION_WEIGHT = 5.0
    }
}
