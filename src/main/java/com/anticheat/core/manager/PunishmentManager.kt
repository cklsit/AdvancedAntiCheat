package com.anticheat.core.manager

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.db.LadderStep
import com.anticheat.core.events.FlagEvent
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.LadderPolicy
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 违规后的处罚决策中心。对齐 Grim 的 `PunishmentManager`。
 *
 * <p>设计上**默认只告警、不处罚**（`core.punishment.enabled: false`）：
 * 骨架阶段的检测阈值还没经过真机标定，直接开踢/开封会造成批量误伤。</p>
 *
 * <h3>分档</h3>
 * 处罚动作由**惩罚阶梯**决定：取 `punishment_ladder` 里满足 `min-vl <= 当前 VL` 的最高一档。
 * 没有阶梯、或 VL 还没到最低档时，回落到 `core.punishment.threshold` +
 * `core.punishment.action`（扁平配置，用于"不想分档"的部署）。
 *
 * <p>阶梯缓存在内存里（[updateLadder] 由 `DatabaseGlue.syncPolicy` 在启动/重载/维护时刷新）：
 * 本类的方法可能跑在 **Netty 线程**上，违规路径上查库会阻塞收包线程。</p>
 *
 * <h3>幂等</h3>
 * 门控用的是「(玩家, 检测) 维度的执行冷却表」，且**故意不随玩家离线清空**——
 * 清掉就变成「重登即可绕过处罚」，这是本项目在旧架构上已经踩过的坑。
 */
class PunishmentManager {

    private val lastActionAt = ConcurrentHashMap<String, Long>()

    /** 当前生效的惩罚阶梯（启动/重载/维护时刷新）。空 = 不分档。 */
    @Volatile
    private var ladder: List<LadderStep> = emptyList()

    /** 未知动作只告警一次：同一条配置写错重复刷屏不提供额外信息。 */
    private val unknownActionWarned = AtomicBoolean(false)

    /** 由 `DatabaseGlue.syncPolicy` 调用（启动 / `/ac reload` / 周期维护）。 */
    fun updateLadder(steps: List<LadderStep>) {
        ladder = steps
    }

    /** 当前阶梯（供命令/日志展示，让"库里到底几档、各档什么动作"不必去翻数据库）。 */
    fun currentLadder(): List<LadderStep> = ladder

    fun handleViolation(player: PlayerData, check: Check) {
        if (player.exempt) return

        AntiCheatCore.alertManager.handleAlert(player, check, "")

        val config = AntiCheatCore.configManager
        if (!config.punishmentEnabled) return

        val step = LadderPolicy.selectStep(ladder, check.violations)
        // 配了阶梯就以阶梯为准；没命中任何一档则回落扁平阈值
        val floor = step?.minVl ?: config.punishmentThreshold
        if (check.violations < floor) return

        val action = (step?.action ?: config.punishmentAction).lowercase(Locale.ROOT)
        // 只告警这一档**不占冷却键**：否则 VL 后来涨到更高档时，
        // 会被前面那次"什么都没做"的冷却挡住，阶梯就升不上去了。
        if (action == ACTION_ALERT) return

        val key = player.uuid.toString() + ":" + check.configName
        val now = System.currentTimeMillis()
        val last = lastActionAt[key] ?: 0L
        if (now - last < config.punishmentCooldownMs) return
        lastActionAt[key] = now

        execute(player, check, step, action)
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

    private fun execute(player: PlayerData, check: Check, step: LadderStep?, action: String) {
        val config = AntiCheatCore.configManager
        val reason = LadderPolicy.expandTemplate(
            step?.reason ?: config.punishmentKickMessage,
            player.name,
            check.checkName,
            check.violations,
            step?.duration
        )

        when (action) {
            ACTION_BAN -> {
                val durationMillis = LadderPolicy.parseDurationMillis(step?.duration)
                if (durationMillis == LadderPolicy.INVALID_MILLIS) {
                    CoreLog.warn(
                        "惩罚阶梯的 duration 无法解析（按永久封禁处理）: " + step?.duration +
                            " check=" + check.checkName
                    )
                }
                banAndKick(player, check, durationMillis, reason)
            }
            ACTION_COMMAND -> {
                val command = LadderPolicy.expandTemplate(
                    config.punishmentCommandTemplate,
                    player.name,
                    check.checkName,
                    check.violations,
                    step?.duration
                )
                // 命令分发会读世界/玩家状态 → 必须回主线程
                AntiCheatCore.scheduler.runOnMainThread {
                    AntiCheatCore.platformServer.dispatchConsoleCommand(command)
                }
            }
            ACTION_KICK -> kick(player, reason)
            else -> {
                // 未知动作按 kick 兜底并告警：静默什么都不做，会让管理员以为阶梯已经生效
                if (unknownActionWarned.compareAndSet(false, true)) {
                    CoreLog.warn("惩罚阶梯里出现未知动作（按 kick 处理）: " + action)
                }
                kick(player, reason)
            }
        }

        AntiCheatCore.platformServer.sendConsoleMessage(
            config.alertPrefix + "处罚 " + player.name +
                " check=" + check.checkName +
                " VL=" + String.format(Locale.ROOT, "%.2f", check.violations) +
                " action=" + action
        )
    }

    /**
     * 写库封禁 + 踢出。
     *
     * <p>两步缺一不可：只写库不踢人，玩家会一直玩到下次重连；只踢人不写库，重连就回来了。
     * 「重连也进不来」由登录路径保证——`DatabaseGlue.onLogin` 查到生效封禁会直接踢掉。</p>
     *
     * @param durationMillis [LadderPolicy.PERMANENT_MILLIS] 或负数 = 永久
     */
    private fun banAndKick(player: PlayerData, check: Check, durationMillis: Long, reason: String) {
        val now = System.currentTimeMillis()
        val expiresAt = if (durationMillis > 0L) now + durationMillis else null
        val database = AntiCheatCore.database
        // 写库是 IO，放异步；踢人回主线程（在 kick() 里做）
        AntiCheatCore.scheduler.runAsync {
            database?.banUuid(player.uuid, player.name, reason, "AntiCheat/" + check.configName, expiresAt, now, null)
        }
        kick(player, reason)
    }

    private fun kick(player: PlayerData, reason: String) {
        // 收包链路在 Netty 线程，踢人必须回主线程
        AntiCheatCore.scheduler.runOnMainThread { player.platformPlayer.kick(reason) }
    }

    companion object {
        /** 只告警、不做动作。 */
        const val ACTION_ALERT = "alert"

        /** 踢出。 */
        const val ACTION_KICK = "kick"

        /** 写库封禁（可带时长）+ 踢出。 */
        const val ACTION_BAN = "ban"

        /** 执行 `core.punishment.command-template`。 */
        const val ACTION_COMMAND = "command"
    }
}
