package com.anticheat.core.platform

import com.anticheat.core.platform.api.PlatformScheduler
import com.anticheat.core.platform.api.PlatformServer
import org.bukkit.plugin.Plugin

/**
 * 平台实现注入点。
 *
 * <p>核心层（`com.anticheat.core.*`）不直接引用任何 Bukkit 调度器/发送者实现，
 * 只通过本接口拿平台能力。新增平台（Folia / 代理端）时只需再写一份实现。</p>
 */
interface PlatformLoader {

    fun getPlugin(): Plugin

    fun getScheduler(): PlatformScheduler

    fun getPlatformServer(): PlatformServer
}
