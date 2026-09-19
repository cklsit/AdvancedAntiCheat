package com.anticheat.core.events.packets

import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent

/**
 * PacketEvents 监听入口。
 *
 * <p>只做路由，不放任何判定逻辑：这个类的每个回调都在 Netty 线程上执行，
 * 一旦抛异常会直接污染连接，所以所有下游实现都必须自带 try/catch。</p>
 */
class PacketProcessor : PacketListener {

    override fun onUserLogin(event: UserLoginEvent) {
        PacketPlayerTracker.onLogin(event)
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        PacketPlayerTracker.onDisconnect(event)
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        PacketPlayerUpdate.handle(event)
    }

    override fun onPacketSend(event: PacketSendEvent) {
        // 预留：后续做发包级检测（假方块 / 客户端状态欺骗）时在这里分派
    }
}
