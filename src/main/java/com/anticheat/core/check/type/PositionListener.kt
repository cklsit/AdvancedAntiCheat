package com.anticheat.core.check.type

import com.anticheat.core.util.update.PositionUpdate

/**
 * 位置更新监听。由【收包 → 位置解析】链路触发，同一 tick 内可能触发多次
 * （1.8 客户端会把 flying 与 position 拆成多个包）。
 */
interface PositionListener {

    fun onPositionUpdate(update: PositionUpdate)
}
