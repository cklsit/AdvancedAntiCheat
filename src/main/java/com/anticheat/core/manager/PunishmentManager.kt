package com.anticheat.core.manager

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.events.FlagEvent
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 违规后的处罚决策中心。对齐 Grim 的 `PunishmentManager`。
 *
 * <p>设计上**默认只告警、不处罚**（`core.punishment.enabled: false`）：
 * 骨架阶段的检测阈值还没经过真机标定，直接开踢/开封会造成批量误伤。</p>
 *
 * <p>幂等门控用的是「(玩家, 检测) 维度的执行冷却表」，且**故意不随玩家离线清空**——
 * 清掉就变成「重登即可绕过处罚」，这是本项目在旧架构上已经踩过的坑。</p>
 */
class PunishmentManager {

    private val lastActionAt = ConcurrentHashMap<String, Long>()

    fun handleViolation(player: PlayerData, check: Check) {
        if (player.exempt) return

        AntiCheatCore.alertManager.handleAlert(player, check, "")

        val config = AntiCheatCore.configManager
        if (!config.punishmentEnabled) return
        if (check.violations < config.punishmentThreshold) return

        val key = player.uuid.toString() + ":" + check.configName
        val now = System.currentTimeMillis()
        val last = lastActionAt[key] ?: 0L
        if (now - last < config.punishmentCooldownMs) return
        lastActionAt[key] = now

        execute(player, check)
    }

    /** flag 被外部模块否决时的钩子（当前只记 debug）。 */
    fun onFlagCancelled(event: FlagEvent) {
        CoreLog.debug("flag cancelled by listener: " + event.check.checkName + " -> " + event.player.name)
    }

    /**
     * 玩家离线时调用。
     *
     * <p>**刻意不清 [lastActionAt]**：清掉之后玩家重登即可再次触发处罚，
     * 等于把「一次性处罚」变成「可无限重复」。仅清理无意义的历史条目。</p>
     */
    fun forget(@Suppress("UNUSED_PARAMETER") uuid: UUID) {
        // intentionally empty
    }

    fun clear() {
        lastActionAt.clear()
    }

    private fun execute(player: PlayerData, check: Check) {
        val config = AntiCheatCore.configManager
        val reason = config.punishmentKickMessage
            .replace("%check%", check.checkName)
            .replace("%vl%", String.format("%.2f", check.violations))

        // 收包链路在 Netty 线程，踢人必须回主线程
        AntiCheatCore.scheduler.runOnMainThread { player.platformPlayer.kick(reason) }

        AntiCheatCore.platformServer.sendConsoleMessage(
            config.alertPrefix + "处罚 " + player.name +
                " check=" + check.checkName +
                " VL=" + String.format("%.2f", check.violations) +
                " action=" + config.punishmentAction
        )
    }
}
