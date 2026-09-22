package com.anticheat.core.manager

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.db.AuditRow
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
 * <h3>分档依据是"第几次被抓"，不是 VL</h3>
 * 违规分（VL）是**会话内**的量：被踢下线后重连，检测实例重建、VL 归零。
 * 若按 VL 分档，只要"踢"这一档比"封"低，被踢的人重连后 VL 归零 →
 * **永远到不了封禁档**（踢—重连—再踢的死循环）。所以升档依据是跨会话的
 * [PlayerData.punishmentCount]（登录时从库里数被处罚过的违规条数），
 * 而 `min-vl` 退化为**该档的证据门槛**：越重的处罚要求越高的 VL。
 *
 * <h3>幂等</h3>
 * 门控用的是「(玩家, 检测) 维度的执行冷却表」，且**故意不随玩家离线清空**——
 * 清掉就变成「重登即可绕过处罚」，这是本项目在旧架构上已经踩过的坑。
 *
 * <h3>两条落库链路</h3>
 * - **违规行**：本方法返回真实动作，由 `Check.flag` 转交 `DatabaseGlue.recordFlag`
 *   写进 `violation.punished` / `punish_action`（不再靠阈值推断）；
 * - **审计**：处罚动作本身写一条 `audit_log`（谁被罚、依据哪条检测、什么动作）。
 */
class PunishmentManager {

    private val lastActionAt = ConcurrentHashMap<String, Long>()

    /** 当前生效的惩罚阶梯（启动/重载/维护时刷新）。空 = 不分档，退回扁平配置。 */
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

    /**
     * 处理一次违规。
     *
     * @return 本次实际执行的处罚动作（`kick` / `ban` / `command`），
     *   null = 没处罚（未开启、豁免、额度不够、冷却中、或只告警档）。
     *   返回值会被写进触发它的那条 `violation` 记录，所以必须如实反映"真的做了什么"。
     */
    fun handleViolation(player: PlayerData, check: Check): String? {
        if (player.exempt) return null

        AntiCheatCore.alertManager.handleAlert(player, check, "")

        val config = AntiCheatCore.configManager
        if (!config.punishmentEnabled) return null

        // 第几次被抓 = 历史被处罚次数 + 本会话已处罚次数
        val offenseIndex = player.punishmentCount + 1
        val step = LadderPolicy.selectStep(ladder, check.violations, offenseIndex)

        val action: String = if (ladder.isEmpty()) {
            // 没配阶梯：退回扁平配置（兼容"不想分档"的部署）
            if (check.violations < config.punishmentThreshold) return null
            config.punishmentAction.lowercase(Locale.ROOT)
        } else {
            // 配了阶梯：VL 没到这一档的证据门槛 → 本次不处罚（不够格就不给处罚）
            step ?: return null
            step.action.lowercase(Locale.ROOT)
        }

        // 只告警这一档**不占冷却键**：否则 VL 后来涨上去、次数再增加时，
        // 会被前面那次"什么都没做"的冷却挡住，阶梯就升不上去了。
        if (action == ACTION_ALERT) return null

        val key = player.uuid.toString() + ":" + check.configName
        val now = System.currentTimeMillis()
        val last = lastActionAt[key] ?: 0L
        if (now - last < config.punishmentCooldownMs) return null
        lastActionAt[key] = now

        execute(player, check, step, action, offenseIndex)
        // 本会话内再被抓就升一档
        player.punishmentCount = offenseIndex
        return action
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

    private fun execute(player: PlayerData, check: Check, step: LadderStep?, action: String, offenseIndex: Int) {
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
                " action=" + action +
                " 第" + offenseIndex + "次"
        )
        writeAudit(player, check, action, step?.duration, offenseIndex)
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

    /**
     * 处罚留痕（`audit_log`）。
     *
     * <p>为什么不写不行：`violation` 表回答"检测到了什么"，`audit_log` 回答
     * **"谁在什么时候对谁做了什么动作"**。核心层的处罚原本只打一行控制台消息，
     * 关服后就没有任何地方能查"这个玩家是因为哪条检测、第几次被踢/被封的"。</p>
     *
     * <p>口径与旧 `AuditManager` 经适配层写入的行保持一致（`operator=AntiCheat`、
     * `operator_role=0`）：`type` 固定 `anticheat_punish`、`result` 是实际动作，
     * 便于按动作筛选。</p>
     */
    private fun writeAudit(player: PlayerData, check: Check, action: String, duration: String?, offenseIndex: Int) {
        val database = AntiCheatCore.database ?: return
        val detail = "check=" + check.checkName +
            " VL=" + String.format(Locale.ROOT, "%.2f", check.violations) +
            (if (duration != null) " duration=" + LadderPolicy.describeDuration(duration) else "") +
            " 第" + offenseIndex + "次"
        // 审计写库是 IO，放异步（处罚已经执行完，审计写失败不能反过来影响处罚）
        AntiCheatCore.scheduler.runAsync {
            database.saveAudit(
                AuditRow(
                    id = null,
                    timestamp = System.currentTimeMillis(),
                    operator = OPERATOR,
                    operatorRole = 0,
                    type = AUDIT_TYPE,
                    target = player.name,
                    ip = null,
                    result = action,
                    detail = detail
                )
            )
        }
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

        /** 审计行里的 operator（与旧 AuditManager 的口径一致）。 */
        const val OPERATOR = "AntiCheat"

        /** 审计行的 type：按动作筛处罚记录时用 `type = 'anticheat_punish'`。 */
        const val AUDIT_TYPE = "anticheat_punish"
    }
}
