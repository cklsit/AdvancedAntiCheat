package com.anticheat.core.db

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.manager.CheckManager
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog

/**
 * **数据库层与反作弊运行时的唯一接缝**。
 *
 * <p>这是 db 包里唯一知道 `PlayerData` / `Check` 的文件——刻意如此：
 * 仓储与模型保持"纯数据"，把"从哪里取字段、往哪里回灌"集中在一处，
 * 免得每个调用点各写一遍映射（那种写法下，加一个字段就要改五处、漏一处就少一列）。</p>
 *
 * <h3>线程纪律</h3>
 * - [recordFlag] 可能在 **Netty 线程**被调用 → 只做入队（[DatabaseService.onViolation] 不阻塞）；
 * - [onLogin]/[onLogout] 含多次查询 → 必须由调用方投递到**异步线程**（本类内部再自行判断）；
 * - 需要回主线程的动作（踢人、改豁免）一律经 `AntiCheatCore.scheduler.runOnMainThread`。
 */
object DatabaseGlue {

    /** 从一次 flag 生成落库记录。所有字段取**判定那一刻**的值（事后无法重建）。 */
    fun recordFlag(player: PlayerData, check: Check, amount: Double, verbose: String) {
        val database = AntiCheatCore.database ?: return
        if (!database.isReady) return

        // 命中率统计（分母在 CheckManager 每 tick 记，分子在这里记）
        database.noteCheckFlag(check.checkName, amount, player.uuid)

        val config = AntiCheatCore.configManager
        val position = player.serverPosition
        val punished = config.punishmentEnabled && check.violations >= config.punishmentThreshold
        val input = ViolationInput(
            uuid = player.uuid,
            name = player.name,
            checkName = check.checkName,
            checkGroup = groupOf(check.checkName),
            vl = check.violations,
            vlDelta = amount,
            severity = RiskScorer.severityOf(amount, check.experimental),
            createdAt = System.currentTimeMillis(),
            dayBucket = Sql.dayBucket(System.currentTimeMillis()),
            serverName = database.settings.serverName,
            world = player.serverWorld,
            x = position.x,
            y = position.y,
            z = position.z,
            ping = player.ping,
            tps = AntiCheatCore.tickManager.tps.toFloat(),
            clientVersion = runCatching { player.clientVersion.name }.getOrNull(),
            packetType = player.lastPacketType,
            detail = verbose.take(JSON_DETAIL_LIMIT),
            experimental = check.experimental,
            punished = punished,
            punishAction = if (punished) config.punishmentAction else null
        )
        database.onViolation(input)
    }

    /**
     * 玩家接入：在**异步线程**建档案 / 记 IP / 查封禁 / 查白名单。
     *
     * <p>查到白名单 → 标记豁免（回主线程写，因为 `PlayerData.exempt` 会被主线程读）；</p>
     * <p>查到生效封禁 → 记一条告警（**不在这里踢人**：封禁的执行权在 BanManager 手里，
     * 两处都踢会出现"踢两次、理由还不一样"）。</p>
     */
    fun onLogin(player: PlayerData, rawIp: String?) {
        val database = AntiCheatCore.database ?: return
        if (!database.isReady) return
        val now = System.currentTimeMillis()
        AntiCheatCore.scheduler.runAsync {
            val info = database.onSessionStart(player.uuid, player.name, rawIp, now) ?: return@runAsync
            if (info.whitelisted && !player.exempt) {
                player.exempt = true
                CoreLog.debug("数据库白名单命中，已豁免: " + player.name)
            }
            val ban = info.ban
            if (ban != null) {
                AntiCheatCore.platformServer.sendConsoleMessage(
                    AntiCheatCore.configManager.alertPrefix + "数据库记录显示 " + player.name +
                        " 处于生效封禁中: " + BanRepository.describe(ban)
                )
            }
        }
    }

    /** 玩家离线：累加在线时长（走异步，不阻塞登出流程）。 */
    fun onLogout(player: PlayerData) {
        val database = AntiCheatCore.database ?: return
        if (!database.isReady) return
        val playtime = ((System.currentTimeMillis() - player.sessionStartMillis) / 1000L).coerceAtLeast(0L)
        AntiCheatCore.scheduler.runAsync { database.onSessionEnd(player.uuid, playtime, System.currentTimeMillis()) }
    }

    /**
     * 把代码里的检测登记进 `check_rule`（首次写入 config.yml 的默认值，
     * 之后只更新"代码事实"；库里的策略字段不会被覆盖）。
     */
    fun syncCheckRules(by: String = "startup"): Int {
        val database = AntiCheatCore.database ?: return 0
        if (!database.isReady) return 0
        val seeds = seedsFromOnlinePlayers() ?: seedsFromCatalog()
        return database.syncCheckRules(seeds, by)
    }

    /** 有在线玩家时用真实实例（值最准：decay/setback 已被配置下发过）。 */
    private fun seedsFromOnlinePlayers(): List<RuleRepository.CheckSeed>? {
        val checkManager = AntiCheatCore.playerDataManager.all().firstOrNull()?.checkManager ?: return null
        val checks = checkManager.checks()
        if (checks.isEmpty()) return null
        return checks.map { check ->
            RuleRepository.CheckSeed(
                checkName = check.checkName,
                enabled = check.isEnabled,
                decay = check.decay,
                setback = check.setbackVl,
                experimental = check.experimental,
                description = check.description,
                thresholds = AntiCheatCore.configManager.thresholdsOf(check.configName)
            )
        }
    }

    /**
     * 没有玩家在线时的兜底：从 [CheckManager.CHECK_CLASSES] 反射读元数据。
     *
     * <p>开服前（或空服时）也能把规则表填好，管理员不需要"等有人进来"才能改阈值。</p>
     */
    private fun seedsFromCatalog(): List<RuleRepository.CheckSeed> {
        val config = AntiCheatCore.configManager
        return CheckManager.CHECK_CLASSES.mapNotNull { type ->
            val data = type.getAnnotation(CheckData::class.java) ?: return@mapNotNull null
            val configName = data.configName.takeIf { it.isNotEmpty() && it != "DEFAULT" } ?: data.name
            RuleRepository.CheckSeed(
                checkName = data.name,
                enabled = config.isCheckEnabled(configName),
                decay = data.decay,
                setback = data.setback,
                experimental = data.experimental,
                description = data.description,
                thresholds = config.thresholdsOf(configName)
            )
        }
    }

    /**
     * 把库里的规则回灌到配置层（**库优先**）。
     *
     * <p>启动时与每次 `/ac reload` 之后都要调用：`CoreConfigManager.load()` 会清空覆盖表，
     * 不重新灌一次的话，管理员在库里调好的阈值会在下一次重载后被配置文件的默认值顶掉。</p>
     */
    fun applyRulesFromDatabase(): Int {
        val database = AntiCheatCore.database ?: return 0
        if (!database.isReady) return 0
        val rules = database.loadCheckRules()
        if (rules.isEmpty()) return 0
        AntiCheatCore.configManager.applyDatabaseRules(rules)
        return rules.size
    }

    /** 检测名 → 分组（落库的 `check_group`，看板按它聚合）。 */
    private fun groupOf(checkName: String): String = when {
        checkName.startsWith("BadPackets") -> "protocol"
        checkName.startsWith("Inventory") -> "inventory"
        checkName.startsWith("Timer") -> "timer"
        checkName.startsWith("AutoClicker") -> "autoclicker"
        checkName.startsWith("Aim") -> "aim"
        checkName.startsWith("Reach") -> "raytrace"
        checkName.startsWith("NoSwing") || checkName.startsWith("ToolSwitch") -> "combat"
        checkName.startsWith("Break") || checkName.startsWith("FastPlace") -> "world"
        else -> "other"
    }

    /** `detail` 列只留前 4000 字符：verbose 可能很长，但没必要为它把行撑大。 */
    const val JSON_DETAIL_LIMIT = 4000
}
