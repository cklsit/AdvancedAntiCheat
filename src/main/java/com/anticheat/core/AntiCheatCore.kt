package com.anticheat.core

import com.anticheat.core.events.AlertEvent
import com.anticheat.core.events.CoreEventBus
import com.anticheat.core.events.FlagEvent
import com.anticheat.core.manager.AlertManager
import com.anticheat.core.manager.InitManager
import com.anticheat.core.manager.PunishmentManager
import com.anticheat.core.manager.TickManager
import com.anticheat.core.manager.config.CoreConfigManager
import com.anticheat.core.manager.init.Initable
import com.anticheat.core.manager.player.PlayerDataManager
import com.anticheat.core.platform.Platform
import com.anticheat.core.platform.PlatformLoader
import com.anticheat.core.platform.api.PlatformScheduler
import com.anticheat.core.platform.api.PlatformServer
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.PacketEventsAPI
import org.bukkit.plugin.java.JavaPlugin

/**
 * 核心层全局门面。对齐 Grim 的 `GrimAPI`。
 *
 * <p>用法（插件 onEnable）：</p>
 * ```
 * AntiCheatCore.load(new BukkitPlatformLoader(this));
 * AntiCheatCore.start();
 * ```
 * onDisable 调 [stop]。
 *
 * <p>设计取舍：全核心层通过这个单例取用管理器，而不是层层传参。
 * 这样做的好处是新增检测/管理器不需要改任何构造签名；
 * 代价是单元测试无法注入替身——因此**所有值得测的逻辑都被刻意挪到了
 * 不依赖本类的纯类里**（例如 [com.anticheat.core.check.ViolationData]、
 * [com.anticheat.core.util.update.PositionUpdate]）。</p>
 *
 * <p>生命周期方法都带 `@JvmStatic`：旧体系是 Java，直接 `AntiCheatCore.start()`
 * 比 `AntiCheatCore.INSTANCE.start()` 更符合调用方的直觉。</p>
 */
object AntiCheatCore {

    /** 核心层版本串，写进启动日志便于对照排障。 */
    const val VERSION: String = "core-1.0.0"

    var platform: Platform = Platform.BUKKIT
        private set

    private var loaderOrNull: PlatformLoader? = null

    val eventBus = CoreEventBus()

    val configManager = CoreConfigManager()

    val alertManager = AlertManager()

    val punishmentManager = PunishmentManager()

    val playerDataManager = PlayerDataManager()

    val tickManager = TickManager()

    /** 由 [com.anticheat.core.manager.init.PacketEventsInit] 装载后写入。 */
    @Volatile
    var packetEvents: PacketEventsAPI<*>? = null

    private var initManager: InitManager? = null

    @Volatile
    var initialized: Boolean = false
        private set

    @get:JvmStatic
    val plugin: JavaPlugin
        get() = loaderOrNull?.getPlugin() as? JavaPlugin
            ?: throw IllegalStateException("AntiCheatCore 尚未 load()")

    @get:JvmStatic
    val scheduler: PlatformScheduler
        get() = loaderOrNull?.getScheduler()
            ?: throw IllegalStateException("AntiCheatCore 尚未 load()")

    @get:JvmStatic
    val platformServer: PlatformServer
        get() = loaderOrNull?.getPlatformServer()
            ?: throw IllegalStateException("AntiCheatCore 尚未 load()")

    // ------------------------------------------------------------------ 生命周期

    @JvmStatic
    @JvmOverloads
    fun load(platformLoader: PlatformLoader, vararg extraInitables: Initable) {
        if (initialized) return

        loaderOrNull = platformLoader
        platform = Platform.BUKKIT
        configManager.load()

        if (!configManager.enabled) {
            CoreLog.info("core.enabled=false，核心层保持关闭（旧检测体系不受影响）")
            return
        }

        val manager = InitManager(extraInitables.toList())
        manager.load()
        initManager = manager
        initialized = true
        CoreLog.info("核心层 " + VERSION + " 已装载（" + platform + "）")
    }

    @JvmStatic
    fun start() {
        if (!initialized) return

        eventBus.subscribe(AlertEvent::class.java) { alertManager.onAlert(it) }
        eventBus.subscribe(FlagEvent::class.java) { punishmentManager.onFlagCancelled(it) }

        initManager?.start()
        CoreLog.info("核心层已启动")
    }

    @JvmStatic
    fun stop() {
        if (!initialized) return
        runCatching { initManager?.stop() }
        playerDataManager.clear()
        alertManager.clear()
        punishmentManager.clear()
        eventBus.clear()
        initManager = null
        initialized = false
        CoreLog.info("核心层已停止")
    }

    /** 供 `/ac reload` 调用：重载配置并把新阈值下发到每个在线玩家的检测实例。 */
    @JvmStatic
    fun reload() {
        if (!initialized) return
        configManager.load()
        for (data in playerDataManager.all()) {
            data.checkManager.reload()
        }
    }

    /** Java 侧查询运行态用（Kotlin 内部请直接用 [initialized]）。 */
    @JvmStatic
    fun isInitialized(): Boolean = initialized
}
