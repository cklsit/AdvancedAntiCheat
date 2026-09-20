package com.anticheat.core.check.impl.badpackets

import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.HeldItemChangeListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.HeldItemUpdate

/**
 * 手持槽位重复上报。
 *
 * <h3>判据</h3>
 * `HELD_ITEM_CHANGE` 的原版语义是「**槽位真的变了**才通知服务端」。
 * 因此连续收到多个携带同一个 slot 的包，说明客户端在做多余上报——
 * 典型来源是把滚轮/数字键的输入处理写坏的自制客户端，或试图用高频
 * 切手包干扰服务端状态机的作弊客户端。
 *
 * <h3>与参考实现的差异（有意为之）</h3>
 * intave 的 `SentSlotTwice` 在**第二次**重复时就给出 `VL=100`。
 * 它的 VL 量纲与阈值映射是自有体系；本项目的违规分是统一的 0~20 制
 * （`core.punishment.threshold` 默认 20），照搬 100 会让一次误报直接触发处罚。
 * 所以这里改成：**要求连续 2 次重复**（即连收 3 个同 slot 包）才记 1 分，
 * 并且把前 4 个包当作热身期——玩家刚进服时客户端会补发一次当前槽位，
 * 那是完全正常的。
 *
 * <p>另外，原版客户端在某些操作（例如从容器里 Shift+点击整理后）会补发一次
 * 相同槽位，所以**单次重复绝不足以判定**。这条判据刻意保持低权重，
 * 它的作用是给其它检测提供旁证。</p>
 */
@CheckData(
    name = "BadPacketsD",
    decay = 0.1,
    setback = 0.0,
    description = "连续收到相同手持槽位的切换包（原版只在槽位变化时才发送）"
)
class BadPacketsD(player: PlayerData) : Check(player), HeldItemChangeListener {

    private var packetsSeen = 0

    private var duplicateStreak = 0

    override fun onHeldItemChange(update: HeldItemUpdate) {
        packetsSeen++

        if (update.previousSlot == update.slot) {
            duplicateStreak++
        } else {
            duplicateStreak = 0
            reward()
        }

        if (packetsSeen <= WARMUP_PACKETS) return

        if (duplicateStreak >= REQUIRED_DUPLICATE_STREAK) {
            flag("手持槽位 " + update.slot + " 连续重复上报 " + (duplicateStreak + 1) + " 次", VIOLATION_WEIGHT)
            duplicateStreak = 0
        }
    }

    companion object {
        /** 前若干个包只用于热身（登录补发 / 容器整理后的补发）。 */
        const val WARMUP_PACKETS = 4

        /** 需要"连续几次重复"。2 表示连收 3 个同 slot 包才判定。 */
        const val REQUIRED_DUPLICATE_STREAK = 2

        const val VIOLATION_WEIGHT = 1.0
    }
}
