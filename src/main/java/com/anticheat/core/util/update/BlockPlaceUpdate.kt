package com.anticheat.core.util.update

import com.github.retrooper.packetevents.protocol.player.InteractionHand

/**
 * 一次方块放置（`PLAYER_BLOCK_PLACEMENT`）。
 *
 * <p>[faceId] 是放置面（0=DOWN / 1=UP / 2=NORTH …）。
 * 「放置面」是搭桥类检测的关键：自动搭桥（scaffold）几乎总是把方块贴在
 * 自己脚下那一面，而真人会在多个面之间变化。</p>
 */
class BlockPlaceUpdate(
    val hand: InteractionHand,
    val faceId: Int,
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int
) {

    override fun toString(): String =
        "BlockPlaceUpdate(hand=" + hand + ", face=" + faceId +
            ", pos=" + blockX + "," + blockY + "," + blockZ + ")"
}
