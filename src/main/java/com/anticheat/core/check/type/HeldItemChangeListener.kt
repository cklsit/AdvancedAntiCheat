package com.anticheat.core.check.type

import com.anticheat.core.util.update.HeldItemUpdate

/**
 * 手持槽位切换监听（`HELD_ITEM_CHANGE`）。运行在 **Netty 网络线程**。
 */
interface HeldItemChangeListener {

    fun onHeldItemChange(update: HeldItemUpdate)
}
