package com.anticheat.core.check.type

import com.github.retrooper.packetevents.event.PacketReceiveEvent

/**
 * 收包监听。运行在 **Netty 网络线程**，禁止直接调用 Bukkit 世界/实体 API。
 */
interface PacketReceiveListener {

    fun onPacketReceive(event: PacketReceiveEvent)
}
