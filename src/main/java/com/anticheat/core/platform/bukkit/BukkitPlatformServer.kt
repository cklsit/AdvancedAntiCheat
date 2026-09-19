package com.anticheat.core.platform.bukkit

import com.anticheat.core.platform.api.PlatformServer
import com.anticheat.core.platform.api.player.PlatformPlayer
import org.bukkit.Bukkit
import java.util.UUID

/**
 * [PlatformServer] 的 Bukkit 实现。
 */
class BukkitPlatformServer : PlatformServer {

    override fun getPlatformImplementationString(): String =
        Bukkit.getName() + " " + Bukkit.getBukkitVersion() +
            " (Java " + System.getProperty("java.version", "unknown") + ")"

    override fun getOnlinePlayerIds(): Collection<UUID> =
        Bukkit.getOnlinePlayers().map { it.uniqueId }

    override fun getPlayer(uuid: UUID): PlatformPlayer? =
        Bukkit.getPlayer(uuid)?.let { BukkitPlayer(it) }

    override fun getPlayerByName(name: String): PlatformPlayer? =
        Bukkit.getPlayer(name)?.let { BukkitPlayer(it) }

    override fun sendConsoleMessage(message: String) {
        runCatching { Bukkit.getConsoleSender().sendMessage(message) }
    }
}
