package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.events.packets.PacketProcessor
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority

/**
 * 把核心层的事件处理器注册进 PacketEvents。
 *
 * <p>优先级用 `NORMAL`：`LOWEST` 会被其它插件抢先改写包内容，
 * `HIGHEST`/`MONITOR` 则拿不到后续插件的修改结果。反作弊需要看到「最终形态」的包，
 * 但又要保留否决能力，`NORMAL` 是 Grim 的选择。</p>
 */
class PacketManager : StartableInitable, StoppableInitable {

    private val processor = PacketProcessor()
    private var handle: PacketListenerCommon? = null

    override fun start() {
        val api = AntiCheatCore.packetEvents
            ?: throw IllegalStateException("PacketEvents 未初始化，无法注册收包监听")
        handle = api.eventManager.registerListener(processor, PacketListenerPriority.NORMAL)
        CoreLog.info("核心层收包监听已注册")
    }

    override fun stop() {
        val api = AntiCheatCore.packetEvents
        val current = handle
        if (api != null && current != null) {
            runCatching { api.eventManager.unregisterListener(current) }
                .onFailure { CoreLog.warn("注销收包监听失败: " + it.message) }
        }
        handle = null
    }
}
