package com.anticheat.core.check.impl.badpackets

import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.RotationListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.RotationUpdate

/**
 * 非法朝向。
 *
 * <p>pitch 有明确定义域 [-90, 90]，原版客户端（含全部模组）不可能越界；
 * yaw 无界，因此**只判 pitch**。这类判据不会产生假阳性，适合作为框架跑通的样例检测。</p>
 */
@CheckData(
    name = "BadPacketsB",
    decay = 0.05,
    setback = 0.0,
    description = "朝向含非法值（pitch 越界 / NaN / Infinity）"
)
class BadPacketsB(player: PlayerData) : Check(player), RotationListener {

    override fun onRotationUpdate(update: RotationUpdate) {
        if (update.hasInvalidValue) {
            flag(
                "非法朝向 yaw=" + format(update.toYaw) +
                    " pitch=" + format(update.toPitch) +
                    " deltaYaw=" + format(update.deltaYaw)
            )
        } else {
            reward()
        }
    }

    private fun format(value: Float): String = String.format("%.3f", value)
}
