package com.anticheat.core.check.type

import com.anticheat.core.util.update.BlockDigUpdate

/**
 * 挖掘 / 用物品动作监听（`PLAYER_DIGGING`）。运行在 **Netty 网络线程**。
 *
 * <p>实现里请先看 [BlockDigUpdate.isDigging]，否则会把「丢物品」「松开右键」
 * 混进挖掘状态机。</p>
 */
interface BlockDigListener {

    fun onBlockDig(update: BlockDigUpdate)
}
