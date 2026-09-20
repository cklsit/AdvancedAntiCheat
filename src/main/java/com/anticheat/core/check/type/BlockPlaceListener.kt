package com.anticheat.core.check.type

import com.anticheat.core.util.update.BlockPlaceUpdate

/**
 * 方块放置监听（`PLAYER_BLOCK_PLACEMENT`）。运行在 **Netty 网络线程**。
 */
interface BlockPlaceListener {

    fun onBlockPlace(update: BlockPlaceUpdate)
}
