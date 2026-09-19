package com.anticheat.core.events

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简类型化事件总线。
 *
 * <p>为什么不用 Bukkit 的事件系统：核心层要发的事件（flag / alert）发生在
 * **网络线程**，而 Bukkit 事件必须是主线程；而且这些事件是核心层内部契约，
 * 不应该暴露成服务端全局可监听的事件源。</p>
 */
class CoreEventBus {

    private val listeners = ConcurrentHashMap<Class<*>, MutableList<(Any) -> Unit>>()

    fun <T : Any> subscribe(type: Class<T>, listener: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        listeners.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(listener as (Any) -> Unit)
    }

    /** 无返回值语义的事件投递。 */
    fun <T : Any> fire(event: T) {
        val list = listeners[event.javaClass] ?: return
        for (listener in list) {
            // 单个监听器抛异常不能影响其它监听器与后续检测流程
            runCatching { listener(event) }
        }
    }

    fun clear() {
        listeners.clear()
    }
}

/**
 * flag 事件。监听器把 [cancelled] 置 true 可以**否决**这次违规，
 * 用于给外部模块（例如授权/豁免插件）留一个不下发处罚的钩子。
 */
class FlagEvent(
    val player: com.anticheat.core.player.PlayerData,
    val check: com.anticheat.core.check.Check,
    val verbose: String
) {
    @Volatile
    var cancelled: Boolean = false
}

/**
 * 告警事件：违规已被记入账本、处罚决策已执行后广播。
 */
class AlertEvent(
    val player: com.anticheat.core.player.PlayerData,
    val check: com.anticheat.core.check.Check,
    val text: String,
    val violations: Double
)
