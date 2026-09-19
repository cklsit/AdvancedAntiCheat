package com.anticheat.core.check.type

/**
 * 服务端 tick 结束回调（主线程，20Hz）。
 *
 * <p>只用于「每 tick 收敛一次状态」的检测；**不要在实现里每 tick 打日志或发消息**，
 * 这个项目历史上因为 10Hz/20Hz 循环里的无条件输出出过两次刷屏事故。</p>
 */
interface ServerTickListener {

    fun onServerTick()
}
