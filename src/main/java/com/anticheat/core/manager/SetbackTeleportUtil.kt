package com.anticheat.core.manager

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog

/**
 * 拉回（setback）。对齐 Grim 的 `SetbackTeleportUtil`。
 *
 * <p>真正可靠的 setback 需要「事务确认」来保证服务端与客户端的位置认知一致
 * （Grim 用 window-confirmation / ping 往返做对齐）。本骨架**不发送任何额外包**，
 * 只把玩家传送回最近一次合规落点——先保证不产生新的状态分歧。</p>
 *
 * <p>两条硬约束：</p>
 * 1. 传送必须在**主线程**执行（收包在 Netty 线程）；
 * 2. 必须做**时间幂等**——同一个 tick 内多个检测同时越界时只能拉回一次，
 *    否则会出现「拉回 → 客户端回包 → 再拉回」的抖动循环。</p>
 */
class SetbackTeleportUtil(private val player: PlayerData) {

    @Volatile
    private var lastSetbackAt: Long = 0L

    /**
     * @return true 表示本次真的执行了拉回
     */
    fun executeViolationSetback(): Boolean {
        val world = player.setbackWorld ?: return false
        val target = player.setbackPosition

        val now = System.currentTimeMillis()
        if (now - lastSetbackAt < SETBACK_COOLDOWN_MS) return false
        lastSetbackAt = now

        AntiCheatCore.scheduler.runOnMainThread {
            val ok = player.platformPlayer.teleportTo(world, target.x, target.y, target.z, player.yaw, player.pitch)
            if (!ok) {
                CoreLog.debug("setback 失败（世界不可用）: " + player.name + " -> " + world)
            }
        }
        return true
    }

    fun reset() {
        lastSetbackAt = 0L
    }

    companion object {
        /** 两次拉回的最小间隔。低于 500ms 会让客户端反复回弹，体感极差。 */
        const val SETBACK_COOLDOWN_MS = 500L
    }
}
