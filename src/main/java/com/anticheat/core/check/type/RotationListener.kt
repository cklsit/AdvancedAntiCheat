package com.anticheat.core.check.type

import com.anticheat.core.util.update.RotationUpdate

/**
 * 朝向更新监听。
 */
interface RotationListener {

    fun onRotationUpdate(update: RotationUpdate)
}
