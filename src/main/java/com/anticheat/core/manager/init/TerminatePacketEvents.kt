package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.PacketEvents

/**
 * 拆卸 PacketEvents：把注入的 handler 从 Netty 管道里摘掉，并释放资源。
 *
 * <p>必须放在 stop 链路的**最后**执行——此时收包回调已经不会再触发，
 * 摘管道才是安全的。</p>
 */
class TerminatePacketEvents : StoppableInitable {

    override fun stop() {
        val api = AntiCheatCore.packetEvents
        if (api == null) {
            CoreLog.debug("PacketEvents 未装载，跳过拆卸")
            return
        }
        runCatching { PacketEvents.getAPI().terminate() }
            .onFailure { CoreLog.warn("PacketEvents 拆卸异常: " + it.message) }
        AntiCheatCore.packetEvents = null
    }
}
