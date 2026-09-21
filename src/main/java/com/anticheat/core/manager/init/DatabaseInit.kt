package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.db.DatabaseGlue
import com.anticheat.core.db.DatabaseService
import com.anticheat.core.db.DatabaseSettings
import com.anticheat.core.manager.CheckManager
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
        // 启动后**立刻**做一次维护（一次性异步任务），不等重复定时器的第一次触发。
        // 理由有两条：
        // 1. 规则表与过期封禁应该在开服时就正确，而不是"等满一个维护周期"；
        // 2. 一次性任务与重复任务走的是不同的调度路径，重复定时器在某些多插件环境下的
        //    首次触发时间不受我们控制（实测生产环境里 20 秒的周期没有按期运行），
        //    而"开机就把该做的做完"不依赖那个周期。
        AntiCheatCore.scheduler.runAsync(Runnable { maintenance(database, settings) })

        // 必须把这两条定时器的存在打出来：否则"维护任务有没有跑"在日志里完全不可观测，
        // 而它负责的正是"过期封禁落状态 / 风险重算 / 规则登记"这三件静默生效的事。
        CoreLog.info(
            "数据库定时任务已启动（刷写每 " + settings.violation.flushIntervalMs + "ms / " +
                "维护每 " + settings.stats.riskRefreshMinutes + " 分钟）"
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

    /**
     * 周期性维护：过期封禁落状态 / 风险重算 / 规则登记。
     *
     * <p>**每次实际执行都写一行 INFO**：这三件事都是"静默生效"的
     * （不写日志的话，管理员只能靠"数据库里有没有数据"反推它跑没跑），
     * 而排查"阈值改了不生效""封禁过期了还生效"时，第一眼看的就是这一行。</p>
     */
    private fun maintenance(database: DatabaseService, settings: DatabaseSettings) {
        if (!database.isReady) return
        val now = System.currentTimeMillis()
        val intervalMs = settings.stats.riskRefreshMinutes * 60_000L
        if (now - lastMaintenanceAt < intervalMs) return
        lastMaintenanceAt = now

        val expired = runCatching { database.expireOverdue(now) }.getOrDefault(-1)
        val rescored = runCatching { database.refreshRisks(now) }.getOrDefault(-1)

        // 规则登记：用"库里的规则数 != 检测类目录的大小"当触发条件，只在真的不一致时写库。
        // 刻意**不要求有玩家在线**——检测目录（CheckManager.CHECK_CLASSES）不依赖实例，
        // 否则空服时管理员没法通过数据库改阈值。
        val expected = CheckManager.CHECK_CLASSES.size
        val known = runCatching { database.loadCheckRules().size }.getOrDefault(-1)
        var registered = 0
        if (known != expected) {
            registered = runCatching { DatabaseGlue.syncCheckRules("periodic") }.getOrDefault(-1)
        }

        CoreLog.info(
            "数据库维护完成：过期封禁 " + expired + " 条 / 风险重算 " + rescored + " 人 / " +
                "检测规则 " + (if (known < 0) "读取失败" else known.toString() + " 项") +
                (if (registered > 0) "（本次登记 " + registered + " 项）" else "")
        )
    }

    /** 读 `database:` 段（扁平键值表，解析在 [DatabaseSettings] 里）。 */
    private fun readSection(): Map<String, Any?>? {
        val config = runCatching { AntiCheatCore.plugin.config }.getOrNull() ?: return null
        val section: ConfigurationSection = config.getConfigurationSection("database") ?: return null
        return section.getValues(true)
    }
}
