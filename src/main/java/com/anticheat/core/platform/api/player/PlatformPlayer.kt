package com.anticheat.core.platform.api.player

import java.util.UUID

/**
 * 玩家抽象。
 *
 * <p>刻意只暴露 1.8.8 与 1.21 都存在的语义，且**不暴露 Location/World 类型**——
 * 否则核心层会被 Bukkit 类型绑死，平台层也就失去意义了。</p>
 */
interface PlatformPlayer {

    val uuid: UUID

    val name: String

    fun sendMessage(message: String)

    fun hasPermission(permission: String): Boolean

    /** 按世界名 + 裸坐标传送；世界不存在返回 false。 */
    fun teleportTo(world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean

    fun kick(reason: String)

    fun isOnline(): Boolean

    /** 服务端权威位置快照；玩家不在线返回 null。**只能在主线程调用**。 */
    fun getServerSnapshot(): ServerSnapshot?
}
