package com.anticheat.core.util.update

import com.github.retrooper.packetevents.protocol.player.DiggingAction

/**
 * 一次挖掘 / 用物品动作（`PLAYER_DIGGING`）。
 *
 * <p>这个包同时承载三类完全不同的语义，判据必须先按 [action] 分流：</p>
 * - `START_DIGGING` / `CANCELLED_DIGGING` / `FINISHED_DIGGING`：挖方块的状态机；
 * - `DROP_ITEM` / `DROP_ITEM_STACK`：丢弃物品（**不是挖掘**）；
 * - `RELEASE_USE_ITEM`：松开右键（**不是挖掘**，且原版只会带 DOWN 面）。
 *
 * <p>[blockX]/[blockY]/[blockZ] 只有挖掘动作才有意义，其余动作客户端会填 0，
 * 不要把它们当成真实坐标使用。</p>
 */
class BlockDigUpdate(
    val action: DiggingAction,
    val faceId: Int,
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int
) {

    val isStart: Boolean = action == DiggingAction.START_DIGGING

    val isCancel: Boolean = action == DiggingAction.CANCELLED_DIGGING

    val isFinish: Boolean = action == DiggingAction.FINISHED_DIGGING

    /** 是否属于"挖掘方块"这一族动作（区别于丢物品 / 松右键 / 换副手）。 */
    val isDigging: Boolean = isStart || isCancel || isFinish

    override fun toString(): String =
        "BlockDigUpdate(action=" + action + ", face=" + faceId +
            ", pos=" + blockX + "," + blockY + "," + blockZ + ")"
}
