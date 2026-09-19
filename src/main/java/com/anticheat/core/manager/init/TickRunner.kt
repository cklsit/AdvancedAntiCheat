package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import kotlin.math.abs

/**
 * 每 tick 刷新玩家权威状态。对齐 Grim 的 `TickRunner`。
 *
 * <p>为什么必须有它：客户端上报的位置包在 1.8 里**不含世界名**，也不代表服务端认可的位置
 * （插件传送、载具、地板门都可能让两者分叉）。setback 与「客户端到底有没有撒谎」
 * 都必须基于服务端权威值，而这个值只能在主线程读。</p>
 *
 * <p>顺带在这里做传送检测：服务端传送后客户端的下一条位置包会带几百格位移，
 * 所有位移类检测都要靠 `lastTeleportTick` 让路。</p>
 */
class TickRunner : StartableInitable, StoppableInitable {

    private var taskId: Int = -1

    override fun start() {
        taskId = AntiCheatCore.scheduler.runTimer(Runnable { tick() }, 1L, 1L)
        CoreLog.info("TickRunner 已启动（每 tick 刷新权威位置 / 每 20 tick 刷新延迟）")
    }

    override fun stop() {
        if (taskId >= 0) {
            AntiCheatCore.scheduler.cancelTask(taskId)
            taskId = -1
        }
    }

    private fun tick() {
        val tick = AntiCheatCore.tickManager.nextTick()
        val refreshPing = tick % PING_REFRESH_TICKS == 0L
        for (data in AntiCheatCore.playerDataManager.all()) {
            if (!data.alive) continue
            try {
                refresh(data, tick, refreshPing)
            } catch (t: Throwable) {
                // 单个玩家刷新失败不能影响其余玩家
                CoreLog.debug("tick 刷新失败 " + data.name + ": " + t.message)
                data.resetTickCounters()
            }
        }
    }

    private fun refresh(data: PlayerData, tick: Long, refreshPing: Boolean) {
        try {
            val snapshot = data.platformPlayer.getServerSnapshot() ?: return
            val hadPosition = data.serverWorld != null
            if (hadPosition) {
                val moved = abs(snapshot.x - data.serverPosition.x) +
                    abs(snapshot.y - data.serverPosition.y) +
                    abs(snapshot.z - data.serverPosition.z)
                if (moved > TELEPORT_DISTANCE) {
                    data.lastTeleportTick = tick
                }
            }
            data.refreshServerState(snapshot.world, snapshot.x, snapshot.y, snapshot.z, snapshot.onGround)

            if (refreshPing) {
                data.ping = runCatching {
                    AntiCheatCore.packetEvents?.playerManager?.getPing(data.user.channel) ?: 0
                }.getOrDefault(0)
            }

            data.checkManager.onServerTick()
        } finally {
            data.resetTickCounters()
        }
    }

    companion object {
        /** 单 tick 内服务端权威位置跳变超过该值即判定为「被传送」。 */
        const val TELEPORT_DISTANCE = 8.0

        /** 延迟刷新周期（tick）。20 = 每秒一次，避免每 tick 都去问服务端。 */
        const val PING_REFRESH_TICKS = 20L
    }
}
