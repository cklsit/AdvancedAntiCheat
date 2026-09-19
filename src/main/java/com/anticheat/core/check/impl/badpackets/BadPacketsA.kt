package com.anticheat.core.check.impl.badpackets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.PositionListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.PositionUpdate
import kotlin.math.abs

/**
 * 非法位置更新。
 *
 * <p>判据刻意收得极窄——只抓**正常客户端不可能产生的值**：</p>
 * 1. NaN / Infinity：没有任何原版客户端会发出这种坐标；
 * 2. 单包位移超过 [MAX_DELTA]：原版疾跑跳跃约 0.7 格/tick，
 *    爆炸击退、鞘翅、激流都在 5 格/tick 以内，10 格是很宽的安全线。
 *
 * <p>并且对「服务端刚传送过」的窗口整体让路：传送后客户端的下一条位置包
 * 会带几百格真实位移，这是位移类检测的头号假阳性来源。</p>
 */
@CheckData(
    name = "BadPacketsA",
    decay = 0.05,
    setback = 0.0,
    description = "位置更新含非法值（NaN/Inf）或单包位移超出物理上限"
)
class BadPacketsA(player: PlayerData) : Check(player), PositionListener {

    override fun onPositionUpdate(update: PositionUpdate) {
        if (update.hasInvalidValue) {
            flag("非法坐标 deltaX=" + update.deltaX + " deltaY=" + update.deltaY + " deltaZ=" + update.deltaZ)
            return
        }

        val sinceTeleport = AntiCheatCore.tickManager.currentTick - player.lastTeleportTick
        if (sinceTeleport < TELEPORT_IMMUNITY_TICKS) {
            reward()
            return
        }

        if (update.deltaXZ > MAX_DELTA || abs(update.deltaY) > MAX_DELTA) {
            flag(
                "位移超限 deltaXZ=" + format(update.deltaXZ) +
                    " deltaY=" + format(update.deltaY) +
                    " onGround=" + update.onGround
            )
        } else {
            reward()
        }
    }

    private fun format(value: Double): String = String.format("%.3f", value)

    companion object {
        /** 单包水平 / 垂直位移上限（格/tick）。 */
        const val MAX_DELTA = 10.0

        /** 服务端传送后的免疫窗口（tick）。40 tick = 2 秒，足够覆盖传送回包与客户端补正。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L
    }
}
