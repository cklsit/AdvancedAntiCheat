package com.anticheat.core.util.update

import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow

/**
 * 一次窗口点击（`CLICK_WINDOW`）。
 *
 * <p>三个字段的语义容易搞混：</p>
 * - [windowId]：0 = **玩家自己的背包**（未打开任何容器）；非 0 = 某个已打开的容器。
 *   多数"未开窗却点击"的判据就靠它区分；
 * - [slot]：1.9+ 里副手是 45，装备栏是 5..8；1.8 没有副手，编号也不同；
 * - [transactionId]：客户端为这次点击分配的事务号，**1.17 之前该字段不存在**，
 *   此时 [hasTransaction] 为 false。凡是拿它做时间差/序号判据的检测，
 *   必须先用 [hasTransaction] 让路，否则在 1.8 上会读到恒定值。
 */
class InventoryClickUpdate(
    val windowId: Int,
    val slot: Int,
    val button: Int,
    val clickType: WrapperPlayClientClickWindow.WindowClickType,
    val transactionId: Int,
    val hasTransaction: Boolean
) {

    /** 玩家自己的背包（未开容器）。 */
    val isPlayerInventory: Boolean = windowId == PLAYER_INVENTORY_WINDOW_ID

    val isShiftClick: Boolean = clickType == WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE

    val isPickup: Boolean = clickType == WrapperPlayClientClickWindow.WindowClickType.PICKUP

    override fun toString(): String =
        "InventoryClickUpdate(window=" + windowId + ", slot=" + slot +
            ", button=" + button + ", type=" + clickType + ")"

    companion object {
        /** 玩家自身背包的窗口 id。 */
        const val PLAYER_INVENTORY_WINDOW_ID = 0

        /** 1.9+ 的副手槽位号。 */
        const val OFFHAND_SLOT = 45
    }
}
