package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent
import com.github.retrooper.packetevents.protocol.player.User

/**
 * 玩家接入 / 离线。
 *
 * <p>刻意用 PacketEvents 的 UserLogin/UserDisconnect 而不是 Bukkit 的 PlayerJoinEvent：
 * 前者在协议层触发，**早于任何包**，能保证「第一个位置包到达时状态容器已就绪」；
 * 后者在登录流程尾部，中间那几拍的位置包会因为没有 PlayerData 而被直接丢弃。</p>
 */
object PacketPlayerTracker {

    /**
     * 复用旧体系的白名单权限节点：命中即整体豁免。
     * 这样「/ac config 里的可信白名单」对核心层同样生效，不需要第二套名单。
     */
    const val EXEMPT_PERMISSION = "anticheat.bypass"

    fun onLogin(event: UserLoginEvent) {
        val user: User = event.user ?: return
        val uuid = user.uuid ?: return

        // 登录回调在 Netty 线程，而 Bukkit.getPlayer / hasPermission 不是线程安全的：
        // 统一回主线程建状态容器。此前的几个包会被丢弃（无害，位置状态从第一个包才开始累积）。
        AntiCheatCore.scheduler.runOnMainThread {
            try {
                createPlayerData(user, uuid)
            } catch (t: Throwable) {
                CoreLog.error("接入玩家失败: " + t.message, t)
            }
        }
    }

    private fun createPlayerData(user: User, uuid: java.util.UUID) {
        if (AntiCheatCore.playerDataManager.get(uuid) != null) return
        val platformPlayer = AntiCheatCore.platformServer.getPlayer(uuid) ?: return

        val config = AntiCheatCore.configManager
        val data = PlayerData(user, platformPlayer)
        data.joinTick = AntiCheatCore.tickManager.currentTick
        data.alertsEnabled = platformPlayer.hasPermission(config.alertPermission)
        data.experimentalChecks = config.experimentalChecks
        data.exempt = platformPlayer.hasPermission(EXEMPT_PERMISSION)

        AntiCheatCore.playerDataManager.add(data)

        // 立即构建检测实例并下发配置：放到第一个包到达时才建会引入一次可见的判定空窗
        data.checkManager

        CoreLog.debug("接入 " + data.name + " 客户端=" + data.clientVersion + " 豁免=" + data.exempt)
    }

    fun onDisconnect(event: UserDisconnectEvent) {
        try {
            val uuid = event.user?.uuid ?: return
            val data = AntiCheatCore.playerDataManager.remove(uuid) ?: return
            data.alive = false
            AntiCheatCore.alertManager.forget(uuid)
            AntiCheatCore.punishmentManager.forget(uuid)
            CoreLog.debug("移除 " + data.name)
        } catch (t: Throwable) {
            CoreLog.error("离线清理失败: " + t.message, t)
        }
    }
}
