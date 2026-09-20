package com.anticheat.core.platform.bukkit

import com.anticheat.core.platform.Platform
import com.anticheat.core.platform.PlatformLoader
import com.anticheat.core.platform.api.PlatformScheduler
import com.anticheat.core.platform.api.PlatformServer
import org.bukkit.plugin.Plugin

/**
 * Bukkit 平台的装载器：把 Plugin 实例翻译成核心层需要的三种能力。
 */
class BukkitPlatformLoader(private val plugin: Plugin) : PlatformLoader {

    private val scheduler: PlatformScheduler = BukkitScheduler(plugin)
    private val server: PlatformServer = BukkitPlatformServer(plugin)

    override fun getPlugin(): Plugin = plugin

    override fun getScheduler(): PlatformScheduler = scheduler

    override fun getPlatformServer(): PlatformServer = server

    companion object {
        /** 当前实现唯一支持的平台；保留方法是为了以后加 Folia/代理端时的分支点。 */
        fun detect(): Platform = Platform.BUKKIT
    }
}
