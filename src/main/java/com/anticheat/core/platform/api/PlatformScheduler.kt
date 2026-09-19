package com.anticheat.core.platform.api

/**
 * 调度器抽象。
 *
 * <p>核心层绝大多数事件来自 Netty 网络线程（收包）或 PacketEvents 的工作线程，
 * <b>绝对不能</b>在这些线程里直接碰 Bukkit 实体/世界 API。任何传送、踢人、
 * 广播都必须经 [runOnMainThread] 回到服务端主线程。</p>
 */
interface PlatformScheduler {

    /** 已在主线程则直接执行，否则投递到主线程。高频调用点用这个，避免多一次任务入队。 */
    fun runOnMainThread(task: Runnable)

    fun runAsync(task: Runnable)

    /** @return 任务 id，可用于 [cancelTask] */
    fun runLater(task: Runnable, delayTicks: Long): Int

    /** @return 任务 id，可用于 [cancelTask] */
    fun runTimer(task: Runnable, delayTicks: Long, periodTicks: Long): Int

    /** @return 任务 id，可用于 [cancelTask] */
    fun runTimerAsync(task: Runnable, delayTicks: Long, periodTicks: Long): Int

    /**
     * 取消单个任务。
     *
     * <p>**不要用「取消本插件全部任务」来代替它**：旧检测体系与核心层共用同一个
     * Plugin 实例，全局取消会连带掐掉旧体系的重载/衰减等定时任务。</p>
     */
    fun cancelTask(taskId: Int)

    fun isPrimaryThread(): Boolean
}
