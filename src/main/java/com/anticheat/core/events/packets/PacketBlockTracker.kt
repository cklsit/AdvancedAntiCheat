package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.update.BlockDigUpdate
import com.anticheat.core.util.update.BlockPlaceUpdate
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging

/**
 * 方块类动作包解析：挖掘（`PLAYER_DIGGING`）与放置（`PLAYER_BLOCK_PLACEMENT`）。
 *
 * <p>**`PLAYER_DIGGING` 这个包名极具误导性**：它同时承载挖掘状态机、丢物品、
 * 松开右键、与副手交换四类语义。这里在入口就按动作分流，
 * 只把真正的挖掘动作写进 [com.anticheat.core.player.PlayerData.breakingBlock]，
 * 否则「丢一个物品」会被当成「结束挖掘」，挖矿计时类检测全部失准。</p>
 *
 * <p>挖掘的墙钟与 tick 两套时间戳都要维护：</p>
 * - 墙钟（`lastDigStartMillis`）给「结束挖掘后仍需忽略挥手的噪声窗口」用；
 * - tick（`digStartTick`）给「重启挖掘的间隔」用——该判据衡量的是客户端
 *   在两个游戏刻之间重启挖掘，用墙钟会被服务端卡顿带偏。</p>
 */
object PacketBlockTracker {

    /** @return true 表示本包已被本追踪器消费 */
    fun handle(event: PacketReceiveEvent): Boolean {
        return when (event.packetType) {
            PacketType.Play.Client.PLAYER_DIGGING -> {
                handleDig(event)
                true
            }

            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT -> {
                handlePlace(event)
                true
            }

            else -> false
        }
    }

    private fun handleDig(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val wrapper = WrapperPlayClientPlayerDigging(event)
            val position = wrapper.blockPosition
            val update = BlockDigUpdate(
                action = wrapper.action,
                faceId = wrapper.blockFaceId,
                blockX = position?.x ?: 0,
                blockY = position?.y ?: 0,
                blockZ = position?.z ?: 0
            )

            val now = System.currentTimeMillis()
            if (update.isStart) {
                data.breakingBlock = true
                data.lastDigStartMillis = now
                data.digStartTick = AntiCheatCore.tickManager.currentTick
                // 本 tick 计数：nuker 类作弊的特征是"一 tick 内对多个方块下手"，
                // 墙钟时间戳数不出这个（同一 tick 内可以出现多个不同时刻）
                data.digStartsThisTick++
            } else if (update.isFinish || update.isCancel) {
                data.breakingBlock = false
                data.lastDigStopMillis = now
            }

            data.checkManager.onBlockDig(update)
        } catch (t: Throwable) {
            CoreLog.debug("挖掘包处理异常: " + t.message)
        }
    }

    private fun handlePlace(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val wrapper = WrapperPlayClientPlayerBlockPlacement(event)
            val position = wrapper.blockPosition
            val update = BlockPlaceUpdate(
                hand = wrapper.hand,
                faceId = wrapper.faceId,
                blockX = position?.x ?: 0,
                blockY = position?.y ?: 0,
                blockZ = position?.z ?: 0
            )

            data.checkManager.onBlockPlace(update)
        } catch (t: Throwable) {
            CoreLog.debug("放置包处理异常: " + t.message)
        }
    }
}
