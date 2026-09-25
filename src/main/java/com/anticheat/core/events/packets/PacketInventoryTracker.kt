package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.update.HeldItemUpdate
import com.anticheat.core.util.update.InventoryClickUpdate
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow

/**
 * 背包 / 窗口类包解析：点击、关闭、手持槽位，以及服务端侧的「打开窗口」。
 *
 * <p><b>这里必须同时看收包与发包。</b>判断「玩家是否真的开着容器」
 * 只能靠服务端自己发出的 `OPEN_WINDOW`（发包）与客户端回的 `CLOSE_WINDOW`（收包）
 * 组成的状态机，不能去问 Bukkit——问服务端得到的是"真实状态"，
 * 那就永远发现不了"客户端在没开窗的情况下点击容器"这种欺骗。</p>
 *
 * <p>状态机刻意做成**宁松勿严**：任何一方发出关闭都会把状态清掉，
 * 服务端下发 `RESPAWN` 时也清一次（见 [handleServerSend]）。
 * 宁可漏判一次（下次点击会重新触发），也不要因为状态卡在"已打开"
 * 而让后面的所有点击检查失效。</p>
 */
object PacketInventoryTracker {

    /** @return true 表示本包已被本追踪器消费 */
    fun handle(event: PacketReceiveEvent): Boolean {
        return when (event.packetType) {
            PacketType.Play.Client.CLICK_WINDOW -> {
                handleClick(event)
                true
            }

            PacketType.Play.Client.CLOSE_WINDOW -> {
                handleClose(event)
                true
            }

            PacketType.Play.Client.HELD_ITEM_CHANGE -> {
                handleHeldItem(event)
                true
            }

            else -> false
        }
    }

    /**
     * 服务端发包：打开窗口 / 重生。
     *
     * <p>注意同时给 [com.anticheat.core.player.PlayerData.openWindowId] 赋值：
     * 「未开窗点击」判据需要区分「windowId==0 的自身背包」与「真的没开的容器」，
     * 只看一个布尔量做不到。</p>
     *
     * <p>`RESPAWN` 也归这里管，是因为它是窗口状态机的**兜底清位**：
     * 死亡与切换维度都走这个包，而这两种情况下客户端不一定会发 `CLOSE_WINDOW`。
     * 状态卡在"已打开"对 `InventoryA` 只是让它少判几次（无害），
     * 对 [com.anticheat.core.check.impl.movement.InventoryMoveA] 却是致命的——
     * 那会让一个正常玩家此后的**所有移动**都被判成违规。</p>
     */
    fun handleServerSend(event: PacketSendEvent): Boolean {
        return when (event.packetType) {
            PacketType.Play.Server.OPEN_WINDOW -> {
                handleOpenWindow(event)
                true
            }

            PacketType.Play.Server.RESPAWN -> {
                clearWindowState(event)
                true
            }

            else -> false
        }
    }

    private fun handleOpenWindow(event: PacketSendEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            val windowId = WrapperPlayServerOpenWindow(event).containerId
            data.openWindowId = windowId
            data.inventoryOpen = true
        } catch (t: Throwable) {
            CoreLog.debug("打开窗口包处理异常: " + t.message)
        }
    }

    private fun clearWindowState(event: PacketSendEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            data.inventoryOpen = false
            data.openWindowId = InventoryClickUpdate.PLAYER_INVENTORY_WINDOW_ID
        } catch (t: Throwable) {
            CoreLog.debug("清窗口状态异常: " + t.message)
        }
    }

    private fun handleClick(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val wrapper = WrapperPlayClientClickWindow(event)
            val transaction = wrapper.actionNumber
            val update = InventoryClickUpdate(
                windowId = wrapper.windowId,
                slot = wrapper.slot,
                button = wrapper.button,
                clickType = wrapper.windowClickType,
                transactionId = transaction.orElse(NO_TRANSACTION),
                hasTransaction = transaction.isPresent
            )

            data.inventoryClicksThisTick++

            data.checkManager.onInventoryClick(update)
        } catch (t: Throwable) {
            CoreLog.debug("窗口点击包处理异常: " + t.message)
        }
    }

    private fun handleClose(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            // 只要客户端说关了，就把状态清掉；即便 windowId 对不上也不追究
            WrapperPlayClientCloseWindow(event)
            data.inventoryOpen = false
            data.openWindowId = InventoryClickUpdate.PLAYER_INVENTORY_WINDOW_ID
        } catch (t: Throwable) {
            CoreLog.debug("关闭窗口包处理异常: " + t.message)
        }
    }

    private fun handleHeldItem(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val slot = WrapperPlayClientHeldItemChange(event).slot
            data.checkManager.onHeldItemChange(HeldItemUpdate(slot, data.heldSlot))

            data.lastHeldSlot = data.heldSlot
            data.heldSlot = slot
        } catch (t: Throwable) {
            CoreLog.debug("手持槽位包处理异常: " + t.message)
        }
    }

    /** 1.17 之前不存在事务号，用 -1 表示"无"。 */
    private const val NO_TRANSACTION = -1
}
