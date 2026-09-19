package com.anticheat.core.check

import com.anticheat.core.player.PlayerData

/**
 * 可重载处理器基类。对齐 Grim 的 `GrimProcessor`：
 * 所有「每玩家一份实例、可热重载」的组件都从这里派生。
 */
abstract class CoreProcessor(val player: PlayerData) {

    /** 配置热重载。默认无操作，子类按需覆盖。 */
    open fun reload() {
    }
}
