package com.anticheat.core.check.impl.badpackets

import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.BlockDigListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.BlockDigUpdate
import com.github.retrooper.packetevents.protocol.player.DiggingAction

/**
 * 非法释放物品。
 *
 * <h3>判据</h3>
 * `RELEASE_USE_ITEM`（松开右键）这个包里带一个"作用面"字段。
 * **原版客户端在这个动作上永远只发送 `DOWN`（0）**——因为松手跟朝哪个面无关，
 * 客户端直接把字段留空。任何非 0 值都只可能来自手工构造包的作弊客户端。
 *
 * <h3>为什么判据收得这么窄</h3>
 * 这个包同时被「丢物品」「换副手」「挖掘结束」复用，但只有 `RELEASE_USE_ITEM`
 * 的面字段是**原版保证恒定**的。对其它动作同样断言面值会产生假阳性
 * （例如 `SWAP_ITEM_WITH_OFFHAND` 在某些版本上会带真实面值），所以这里只查一种动作。
 *
 * <p>参考 intave `check/other/protocolscanner/InvalidRelease`（判据同为
 * "非 DOWN 即违规"）；权重从 3 降到 3.0 是同一量纲下直接照搬——
 * 这类判据的价值在于**为其它检测提供佐证**，不在于单独封禁。</p>
 */
@CheckData(
    name = "BadPacketsC",
    decay = 0.05,
    setback = 0.0,
    description = "松开右键包携带了非法的作用面（原版只会发送 DOWN）"
)
class BadPacketsC(player: PlayerData) : Check(player), BlockDigListener {

    override fun onBlockDig(update: BlockDigUpdate) {
        if (update.action != DiggingAction.RELEASE_USE_ITEM) {
            // 不是我们要看的动作，视为一次正常的通过动作
            reward()
            return
        }

        if (update.faceId == DOWN_FACE_ID) {
            reward()
            return
        }

        flag("release 作用面=" + update.faceId + "（原版恒为 " + DOWN_FACE_ID + "）", VIOLATION_WEIGHT)
    }

    companion object {
        /** `BlockFace.DOWN` 在协议里的编号。 */
        const val DOWN_FACE_ID = 0

        const val VIOLATION_WEIGHT = 3.0
    }
}
