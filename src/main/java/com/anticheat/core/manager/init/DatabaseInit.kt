package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.db.DatabaseGlue
import com.anticheat.core.db.DatabaseService
import com.anticheat.core.db.DatabaseSettings
import com.anticheat.core.util.CoreLog
import org.bukkit.configuration.ConfigurationSection

/**
 * 数据库的启停与定时任务。
 *
 * <h3>职责边界</h3>
 * - **启停**：建连接、跑迁移、装配仓储；失败**不抛给生命周期**（核心层其它能力
 *   继续工作），并按 `reconnect-delay-seconds` 定时重试，连上后自动补建表结构；
 * - **刷写**：按 `violation.flush-interval-ms` 把批量缓冲写进库；
 * - **重算**：按 `stats.risk-refresh-minutes` 重算风险分与窗口计数，
 *   顺带把过期封禁落状态、把检测规则登记进 `check_rule`。
 *
 * <p>为什么这些任务不放进 `TickRunner`：它们全都要读数据库（阻塞 IO），
 * 放在主线程会直接吃掉 tick。这里一律走**异步定时器**，
 * 只把"改运行时状态"的动作（如回灌规则）投回主线程。</p>
 */
class DatabaseInit : StartableInitable, StoppableInitable {

    private var service: DatabaseService? = null

    private var retryTaskId = -1

    private var flushTaskId = -1

    private var maintenanceTaskId = -1

    private var lastMaintenanceAt = 0L

    override fun start() {
        val settings = DatabaseSettings.from(readSection())
        if (!settings.enabled) {
            CoreLog.info("database.enabled=false，持久化关闭（反作弊判定不受影响）")
            return
        }

        val dataFolder = runCatching { AntiCheatCore.plugin.dataFolder }.getOrNull()
        if (dataFolder == null) {
            CoreLog.warn("拿不到插件数据目录，持久化关闭")
            return
        }

        val created = DatabaseService(settings, dataFolder)
        service = created
        AntiCheatCore.database = created

        val database = created
        if (database.tryStart()) {
            onReady(database)
        } else {
            scheduleRetry(database)
        }

        // 刷写：固定 5 秒一次（配置里的 flush-interval-ms 由这个节拍决定实际间隔）
        val flushPeriodTicks = (settings.violation.flushIntervalMs / 50L).coerceAtLeast(20L)
        flushTaskId = AntiCheatCore.scheduler.runTimerAsync(
            Runnable { runCatching { database.flushNow() } },
            flushPeriodTicks,
            flushPeriodTicks
        )
        // 维护：每 20 秒看一次是否到了重算周期
        maintenanceTaskId = AntiCheatCore.scheduler.runTimerAsync(
            Runnable { maintenance(database, settings) },
            400L,
            400L
        )
    }

    override fun stop() {
        if (flushTaskId >= 0) AntiCheatCore.scheduler.cancelTask(flushTaskId)
        if (maintenanceTaskId >= 0) AntiCheatCore.scheduler.cancelTask(maintenanceTaskId)
        if (retryTaskId >= 0) AntiCheatCore.scheduler.cancelTask(retryTaskId)
        flushTaskId = -1
        maintenanceTaskId = -1
        retryTaskId = -1
        service = null
        // AntiCheatCore.stop() 负责 database.stop()（先刷残留再关池）
    }

    private fun onReady(database: DatabaseService) {
        if (database.state == DatabaseService.State.FAILED) return
        // 回灌规则要改配置层状态：投回主线程做，避免与 /ac reload 并发改同一批 map
        AntiCheatCore.scheduler.runOnMainThread {
            val applied = runCatching { DatabaseGlue.applyRulesFromDatabase() }.getOrDefault(0)
            if (applied > 0) CoreLog.info("已从数据库应用 " + applied + " 条检测规则（库优先于 config.yml）")
        }
    }

    private fun scheduleRetry(database: DatabaseService) {
        val seconds = database.settings.reconnectDelaySeconds
        val periodTicks = seconds * 20L
        retryTaskId = AntiCheatCore.scheduler.runTimerAsync(
            Runnable {
                if (database.tryStart()) {
                    if (retryTaskId >= 0) {
                        AntiCheatCore.scheduler.cancelTask(retryTaskId)
                        retryTaskId = -1
                    }
                    onReady(database)
                }
            },
            periodTicks,
            periodTicks
        )
    }

    /** 周期性维护：风险重算 / 过期封禁 / 规则登记。 */
    private fun maintenance(database: DatabaseService, settings: DatabaseSettings) {
        if (!database.isReady) return
        val now = System.currentTimeMillis()
        val intervalMs = settings.stats.riskRefreshMinutes * 60_000L
        if (now - lastMaintenanceAt < intervalMs) return
        lastMaintenanceAt = now

        runCatching { database.expireOverdue(now) }
        runCatching { database.refreshRisks(now) }

        // 规则登记需要至少一个在线玩家（检测实例是每玩家创建的）。
        // 用"库里的规则数 != 在线的检测数"当触发条件，只在真的不一致时写库。
        val online = AntiCheatCore.playerDataManager.all()
        if (online.isNotEmpty()) {
            val checks = online.first().checkManager.checks()
            val known = runCatching { database.loadCheckRules().size }.getOrDefault(0)
            if (checks.size != known) {
                val touched = runCatching { DatabaseGlue.syncCheckRules("periodic") }.getOrDefault(0)
                if (touched > 0) CoreLog.info("已登记 " + touched + " 个检测的规则到数据库")
            }
        }
    }

    /** 读 `database:` 段（扁平键值表，解析在 [DatabaseSettings] 里）。 */
    private fun readSection(): Map<String, Any?>? {
        val config = runCatching { AntiCheatCore.plugin.config }.getOrNull() ?: return null
        val section: ConfigurationSection = config.getConfigurationSection("database") ?: return null
        return section.getValues(true)
    }
}
