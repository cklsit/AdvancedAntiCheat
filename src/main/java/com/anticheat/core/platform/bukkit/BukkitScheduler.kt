package com.anticheat.core.platform.bukkit

import com.anticheat.core.platform.api.PlatformScheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Bukkit 调度器实现。全部方法在 1.8.8 与 1.21.x 上语义一致。
 */
class BukkitScheduler(private val plugin: Plugin) : PlatformScheduler {

    override fun runOnMainThread(task: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            // 高频路径：已经在主线程时不必再排一次任务队列
            runCatching { task.run() }
        } else {
            Bukkit.getScheduler().runTask(plugin, task)
        }
    }

    override fun runAsync(task: Runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task)
    }

    override fun runLater(task: Runnable, delayTicks: Long): Int =
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks).taskId

    override fun runTimer(task: Runnable, delayTicks: Long, periodTicks: Long): Int =
        Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks).taskId

    override fun runTimerAsync(task: Runnable, delayTicks: Long, periodTicks: Long): Int =
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks).taskId

    override fun cancelTask(taskId: Int) {
        runCatching { Bukkit.getScheduler().cancelTask(taskId) }
    }

    override fun isPrimaryThread(): Boolean = Bukkit.isPrimaryThread()
}
