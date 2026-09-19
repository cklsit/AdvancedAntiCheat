package com.anticheat.core.check.type

import com.github.retrooper.packetevents.event.PacketSendEvent

/**
 * 发包监听。运行在 **Netty 网络线程**。
 */
interface PacketSendListener {

    fun onPacketSend(event: PacketSendEvent)
}
