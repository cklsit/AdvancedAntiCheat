package com.anticheat.core.check.type

import com.anticheat.core.util.update.InventoryClickUpdate

/**
 * 窗口点击监听（`CLICK_WINDOW`）。运行在 **Netty 网络线程**。
 *
 * <p>该回调的频率完全由客户端控制：作弊客户端可以在一个 tick 内发上千次点击。
 * 实现里**不要**做 O(n) 以上的工作，也不要在回调内直接打日志。</p>
 *
 * <p>若需要读玩家真实的背包/窗口状态，必须回主线程——
 * 客户端上报的 `windowId` 并不可信（[com.anticheat.core.check.impl.inventory.InventoryA]
 * 就是专门检测这种不可信的）。</p>
 */
interface InventoryClickListener {

    fun onInventoryClick(update: InventoryClickUpdate)
}
