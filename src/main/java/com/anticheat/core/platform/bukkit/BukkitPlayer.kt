package com.anticheat.core.platform.bukkit

import com.anticheat.core.platform.api.player.PlatformPlayer
import com.anticheat.core.platform.api.player.ServerSnapshot
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/**
 * [PlatformPlayer] 的 Bukkit 实现。
 *
 * <p>跨版本铁律：这里只用 1.8.8 与 1.21 都存在的方法名
 * （`uniqueId` / `name` / `sendMessage(String)` / `hasPermission` / `teleport` / `kickPlayer`）。
 * 严禁出现 `kick(Component)`、`getPing()`、`showTitle(Title)` 这类高版本专有签名——
 * 编译期在 Paper 上能过，1.8.8 运行期直接 NoSuchMethodError。</p>
 */
class BukkitPlayer(private val player: Player) : PlatformPlayer {

    override val uuid: UUID get() = player.uniqueId

    override val name: String get() = player.name

    override fun sendMessage(message: String) {
        player.sendMessage(message)
    }

    override fun hasPermission(permission: String): Boolean = player.hasPermission(permission)

    override fun teleportTo(world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean {
        val target = Bukkit.getWorld(world) ?: return false
        return player.teleport(Location(target, x, y, z, yaw, pitch))
    }

    override fun kick(reason: String) {
        player.kickPlayer(reason)
    }

    override fun isOnline(): Boolean = player.isOnline

    override fun getServerSnapshot(): ServerSnapshot? {
        if (!player.isOnline) return null
        val location = player.location ?: return null
        val world = location.world ?: return null
        return ServerSnapshot(
            world.name,
            location.x,
            location.y,
            location.z,
            location.yaw,
            location.pitch,
            player.isOnGround
        )
    }
}
