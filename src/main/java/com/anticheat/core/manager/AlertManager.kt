package com.anticheat.core.manager

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.events.AlertEvent
import com.anticheat.core.platform.api.player.PlatformPlayer
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 告警下发。对齐 Grim 的 `AlertManagerImpl`，但做了两处本项目特有的收敛：
 *
 * 1. **按玩家节流**：同一玩家在 `core.alerts.min-interval` 内只出一条，
 *    否则一个每 tick 触发的检测能在几秒内把聊天栏刷满（本项目出过两次刷屏事故）。
 * 2. **只发给有权限的人**：遍历在线玩家而非全局广播，避免把检测细节泄漏给普通玩家。
 */
class AlertManager {

    private val lastAlertAt = ConcurrentHashMap<UUID, Long>()

    /**
     * @return true 表示本次告警实际下发（未开启 / 被节流时为 false）
     */
    fun handleAlert(player: PlayerData, check: Check, verbose: String): Boolean {
        val config = AntiCheatCore.configManager
        if (!config.alertsEnabled) return false

        // 节流判定留在调用线程（ConcurrentHashMap，天然线程安全）——
        // 放到主线程去判会让高频检测把任务队列灌满
        val now = System.currentTimeMillis()
        val last = lastAlertAt[player.uuid] ?: 0L
        if (now - last < config.alertMinIntervalMs) return false
        lastAlertAt[player.uuid] = now

        val text = buildAlertText(player, check, verbose, config.alertVerbose)
        val prefix = config.alertPrefix
        val permission = config.alertPermission

        // 下发必须回主线程：本方法由收包链路（Netty 线程）调用，
        // 而 getOnlinePlayers()/sendMessage() 都不是线程安全的 Bukkit API，
        // 在异步线程上遍历在线玩家是 ConcurrentModificationException 的经典来源。
        // 已做了每秒一次的节流，因此这里每玩家最多每秒产生一个任务。
        AntiCheatCore.scheduler.runOnMainThread { dispatch(player, prefix + text, permission) }
        return true
    }

    /** 主线程执行的实际下发。 */
    private fun dispatch(player: PlayerData, text: String, permission: String) {
        AntiCheatCore.platformServer.sendConsoleMessage(text)

        for (id in AntiCheatCore.platformServer.getOnlinePlayerIds()) {
            if (id == player.uuid) continue
            val viewer: PlatformPlayer = AntiCheatCore.platformServer.getPlayer(id) ?: continue
            if (viewer.hasPermission(permission)) {
                viewer.sendMessage(text)
            }
        }
    }

    /** 玩家离线后清理节流表，避免长期运行下的内存滞留。 */
    fun forget(uuid: UUID) {
        lastAlertAt.remove(uuid)
    }

    fun clear() {
        lastAlertAt.clear()
    }

    private fun buildAlertText(player: PlayerData, check: Check, verbose: String, showVerbose: Boolean): String {
        val sb = StringBuilder()
        sb.append(player.name)
            .append(" 触发 ").append(check.checkName)
            .append(" VL=").append(String.format("%.2f", check.violations))
            .append(" ping=").append(player.ping).append("ms")
            .append(" [").append(player.clientVersion.name).append("]")
        if (showVerbose && verbose.isNotEmpty()) {
            sb.append(" | ").append(verbose)
        }
        return sb.toString()
    }

    private fun debugLog(message: String) = CoreLog.debug(message)

    /** 供 [AntiCheatCore] 注册事件总线监听时使用。 */
    fun onAlert(event: AlertEvent) {
        debugLog("alert " + event.check.checkName + " -> " + event.player.name)
    }
}
