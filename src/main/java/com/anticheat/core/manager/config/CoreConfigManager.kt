package com.anticheat.core.manager.config

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.db.CheckRuleRow
import com.anticheat.core.db.LadderStep
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.LadderPolicy
import com.anticheat.core.util.WhitelistPolicy
import com.anticheat.core.util.WhitelistSeed
import org.bukkit.configuration.ConfigurationSection
import java.util.concurrent.ConcurrentHashMap

/**
 * 核心层配置读取。对齐 Grim 的 `BaseConfigManager`。
 *
 * <p>所有取值都带默认值，且**默认值必须与 config.yml 里写的一致**——
 * 否则「管理员删掉某个键」会静默改变行为，这正是本项目历史上出现过的问题
 * （代码读 A、配置写 B，管理员关了开关却还在跑）。</p>
 */
class CoreConfigManager {

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var debug: Boolean = false
        private set

    @Volatile
    var experimentalChecks: Boolean = false
        private set

    // ---------------- 告警 ----------------

    @Volatile
    var alertsEnabled: Boolean = true
        private set

    @Volatile
    var alertPermission: String = "anticheat.notify"
        private set

    @Volatile
    var alertMinIntervalMs: Long = 1000L
        private set

    @Volatile
    var alertPrefix: String = "\u00a78[\u00a7cAAC\u00a78] \u00a77"
        private set

    @Volatile
    var alertVerbose: Boolean = false
        private set

    // ---------------- 处罚 ----------------

    @Volatile
    var punishmentEnabled: Boolean = true
        private set

    @Volatile
    var punishmentThreshold: Double = 20.0
        private set

    @Volatile
    var punishmentAction: String = "kick"
        private set

    @Volatile
    var punishmentCooldownMs: Long = 30000L
        private set

    @Volatile
    var punishmentKickMessage: String = "\u00a7c反作弊检测到异常行为（%check% VL=%vl%）"
        private set

    /**
     * 惩罚阶梯（`core.punishment.ladder`）。空列表 = 不分档：
     * 超 [punishmentThreshold] 就按 [punishmentAction] 处理。
     *
     * <p>这里只是“播种源”：启动时若库里的阶梯为空会用它写入数据库，
     * 之后判定读的是库（改库即生效）。</p>
     */
    @Volatile
    var punishmentLadder: List<LadderStep> = emptyList()
        private set

    /** `action=command` 时执行的命令模板（`core.punishment.command-template`）。 */
    @Volatile
    var punishmentCommandTemplate: String = DEFAULT_COMMAND_TEMPLATE
        private set

    /** 声明式白名单（`core.whitelist`）：启动/重载时把缺失的条目补进数据库。 */
    @Volatile
    var whitelistSeeds: List<WhitelistSeed> = emptyList()
        private set

    /** 每检测的覆盖项，键为 checkName。 */
    private val checkEnabledOverride = ConcurrentHashMap<String, Boolean>()
    private val checkDecayOverride = ConcurrentHashMap<String, Double>()
    private val checkSetbackOverride = ConcurrentHashMap<String, Double>()

    /**
     * 每个检测的**原始配置段**（`core.checks.<名字>`）。
     *
     * <p>为什么要把整段留下来：不同检测的可调参数完全不同（CPS 上限、余额缓冲、
     * 统计窗口长度……），在管理器里为每一项都写一个具名属性会让这里迅速膨胀成
     * 几十个字段，且每加一个检测就要改三个文件。改成检测自己去问
     * [optionInt]/[optionDouble]/[optionBoolean]，新增检测只需要在 config.yml
     * 的 `core.checks.<名字>` 下加键，不必再动管理器。</p>
     */
    private val checkSections = ConcurrentHashMap<String, ConfigurationSection>()

    /**
     * 数据库下发的逐检测阈值（`check_rule.thresholds`）。
     *
     * <p>**优先于 config.yml**：管理员可以直接改库生效，不必登服务器改文件再重启。
     * 这也是"库里的策略字段是权威"那条约定的落地处（见 `RuleRepository` 的说明）。</p>
     */
    private val dbThresholds = ConcurrentHashMap<String, Map<String, String>>()

    /** 读一个逐检测的整数参数；数据库 > config.yml > [def]。 */
    fun optionInt(checkConfigName: String, key: String, def: Int): Int =
        dbThresholds[checkConfigName]?.get(key)?.trim()?.toIntOrNull()
            ?: checkSections[checkConfigName]?.getInt(key, def)
            ?: def

    fun optionDouble(checkConfigName: String, key: String, def: Double): Double =
        dbThresholds[checkConfigName]?.get(key)?.trim()?.toDoubleOrNull()
            ?: checkSections[checkConfigName]?.getDouble(key, def)
            ?: def

    fun optionBoolean(checkConfigName: String, key: String, def: Boolean): Boolean =
        dbThresholds[checkConfigName]?.get(key)?.trim()?.toBooleanStrictOrNull()
            ?: checkSections[checkConfigName]?.getBoolean(key, def)
            ?: def

    /**
     * 取某个检测的全部阈值，用于登记进 `check_rule`。
     *
     * <p>把 config.yml 里 `core.checks.<名字>` 下除 enabled/decay/setback 之外的键
     * 原样带过去——它们是"这个检测的可调参数"，正是管理员需要在库里看到并调整的东西。</p>
     */
    /** 某个检测当前是否启用（规则登记时需要，避免为一个不存在的检测写一行 enabled=true）。 */
    fun isCheckEnabled(checkConfigName: String): Boolean =
        checkEnabledOverride[checkConfigName] ?: true

    fun thresholdsOf(checkConfigName: String): Map<String, Any?> {
        val section = checkSections[checkConfigName] ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (key in section.getKeys(false)) {
            if (key == "enabled" || key == "decay" || key == "setback") continue
            out[key] = section.get(key)
        }
        return out
    }

    /**
     * 把库里的规则应用为覆盖层（由 `DatabaseGlue.applyRulesFromDatabase` 调用）。
     *
     * <p>只覆盖**库里真有值**的项：`decay`/`setback` 在库里是可空列，
     * 空值表示"没设置过"，此时必须回落到 config.yml，而不是把它当成 0。</p>
     */
    fun applyDatabaseRules(rules: Map<String, CheckRuleRow>) {
        dbThresholds.clear()
        for ((name, row) in rules) {
            if (row.thresholds.isNotEmpty()) dbThresholds[name] = row.thresholds
            checkEnabledOverride[name] = row.enabled
            row.decay?.let { checkDecayOverride[name] = it }
            row.setback?.let { checkSetbackOverride[name] = it }
        }
        CoreLog.debug("已应用数据库规则: " + rules.size + " 项")
    }

    fun load() {
        val config = AntiCheatCore.plugin.config

        enabled = config.getBoolean("core.enabled", true)
        debug = config.getBoolean("core.debug", false)
        experimentalChecks = config.getBoolean("core.experimental-checks", false)

        alertsEnabled = config.getBoolean("core.alerts.enabled", true)
        alertPermission = config.getString("core.alerts.permission", DEFAULT_ALERT_PERMISSION) ?: DEFAULT_ALERT_PERMISSION
        alertMinIntervalMs = config.getLong("core.alerts.min-interval-ms", 1000L)
        alertPrefix = config.getString("core.alerts.prefix", DEFAULT_ALERT_PREFIX) ?: DEFAULT_ALERT_PREFIX
        alertVerbose = config.getBoolean("core.alerts.verbose", false)

        punishmentEnabled = config.getBoolean("core.punishment.enabled", true)
        punishmentThreshold = config.getDouble("core.punishment.threshold", 20.0)
        punishmentAction = config.getString("core.punishment.action", "kick") ?: "kick"
        punishmentCooldownMs = config.getLong("core.punishment.cooldown-ms", 30000L)
        punishmentKickMessage =
            config.getString("core.punishment.kick-message", DEFAULT_KICK_MESSAGE) ?: DEFAULT_KICK_MESSAGE
        punishmentLadder = LadderPolicy.parseSteps(config.getMapList("core.punishment.ladder"))
        punishmentCommandTemplate =
            config.getString("core.punishment.command-template", DEFAULT_COMMAND_TEMPLATE) ?: DEFAULT_COMMAND_TEMPLATE
        // expires-in 相对于“本次读取时间”计算，所以 /ac reload 会把未过期的条目续期。
        // 这是刻意的：配置里写 30d 表示“从现在起 30 天”，而不是“自安装那天起 30 天”。
        whitelistSeeds = WhitelistPolicy.parse(config.getList("core.whitelist"), System.currentTimeMillis())

        checkEnabledOverride.clear()
        checkDecayOverride.clear()
        checkSetbackOverride.clear()
        checkSections.clear()

        val section = config.getConfigurationSection("core.checks")
        if (section != null) {
            for (name in section.getKeys(false)) {
                val child = section.getConfigurationSection(name) ?: continue
                checkEnabledOverride[name] = child.getBoolean("enabled", true)
                if (child.contains("decay")) checkDecayOverride[name] = child.getDouble("decay")
                if (child.contains("setback")) checkSetbackOverride[name] = child.getDouble("setback")
                checkSections[name] = child
            }
        }

        CoreLog.debugEnabled = debug
        CoreLog.debug("配置已载入: " + summary())
    }

    /** 把配置作用到单个检测上。[Check] 的 reload 会调用它。 */
    fun applyTo(check: Check) {
        val key = check.configName
        check.isEnabled = checkEnabledOverride[key] ?: true
        check.exemptPermission = AntiCheatCore.platformServer
            .getPlayer(check.player.uuid)
            ?.hasPermission(EXEMPT_PERMISSION_PREFIX + key.lowercase())
            ?: false
        // decay / setback 的覆盖项必须真的下发到账本。
        // 只读不写 = 管理员改了 core.checks.<名字>.decay 却毫无效果，
        // 正是本项目历史上那类「配置写了但不生效」的静默缺陷。
        check.applyTuning(checkDecayOverride[key], checkSetbackOverride[key])
    }

    fun summary(): String =
        "enabled=" + enabled + " alerts=" + alertsEnabled + " punishment=" + punishmentEnabled +
            " checks=" + checkEnabledOverride.size

    companion object {
        const val DEFAULT_ALERT_PERMISSION = "anticheat.notify"
        const val DEFAULT_ALERT_PREFIX = "\u00a78[\u00a7cAAC\u00a78] \u00a77"
        const val DEFAULT_KICK_MESSAGE = "\u00a7c反作弊检测到异常行为（%check% VL=%vl%）"
        const val DEFAULT_COMMAND_TEMPLATE = "ban %player% 反作弊检测：%check% VL=%vl%"
        const val EXEMPT_PERMISSION_PREFIX = "anticheat.exempt."
    }
}
