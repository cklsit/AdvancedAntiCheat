package com.anticheat.core.manager.config

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.util.CoreLog
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
    var punishmentEnabled: Boolean = false
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

    /** 每检测的覆盖项，键为 checkName。 */
    private val checkEnabledOverride = ConcurrentHashMap<String, Boolean>()
    private val checkDecayOverride = ConcurrentHashMap<String, Double>()
    private val checkSetbackOverride = ConcurrentHashMap<String, Double>()

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

        punishmentEnabled = config.getBoolean("core.punishment.enabled", false)
        punishmentThreshold = config.getDouble("core.punishment.threshold", 20.0)
        punishmentAction = config.getString("core.punishment.action", "kick") ?: "kick"
        punishmentCooldownMs = config.getLong("core.punishment.cooldown-ms", 30000L)
        punishmentKickMessage =
            config.getString("core.punishment.kick-message", DEFAULT_KICK_MESSAGE) ?: DEFAULT_KICK_MESSAGE

        checkEnabledOverride.clear()
        checkDecayOverride.clear()
        checkSetbackOverride.clear()

        val section = config.getConfigurationSection("core.checks")
        if (section != null) {
            for (name in section.getKeys(false)) {
                val child = section.getConfigurationSection(name) ?: continue
                checkEnabledOverride[name] = child.getBoolean("enabled", true)
                if (child.contains("decay")) checkDecayOverride[name] = child.getDouble("decay")
                if (child.contains("setback")) checkSetbackOverride[name] = child.getDouble("setback")
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
    }

    fun summary(): String =
        "enabled=" + enabled + " alerts=" + alertsEnabled + " punishment=" + punishmentEnabled +
            " checks=" + checkEnabledOverride.size

    companion object {
        const val DEFAULT_ALERT_PERMISSION = "anticheat.notify"
        const val DEFAULT_ALERT_PREFIX = "\u00a78[\u00a7cAAC\u00a78] \u00a77"
        const val DEFAULT_KICK_MESSAGE = "\u00a7c反作弊检测到异常行为（%check% VL=%vl%）"
        const val EXEMPT_PERMISSION_PREFIX = "anticheat.exempt."
    }
}
