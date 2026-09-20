package com.anticheat.core.check.impl.inventory

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.InventoryClickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.InventoryClickUpdate
import com.github.retrooper.packetevents.protocol.player.ClientVersion

/**
 * 自动图腾（AutoTotem）。
 *
 * <h3>判据</h3>
 * 原版里换副手物品是一条**鼠标操作链**：先把图腾从箱子里拿到光标
 * （`PICKUP` 点击），再点击副手槽位（45）放进去。人类完成这两步
 * 至少需要一次「移动鼠标 + 点击」，实测下限约 150~200ms。
 * 作弊客户端的自动图腾模块是一条本地指令，两步之间通常 **< 100ms**。
 *
 * <p>这里衡量的是「同一次拾取与换手之间的时间差」——
 * 它不需要知道物品是什么，因此不需要物品栏快照，是纯时序判据。</p>
 *
 * <h3>只对 1.9+ 生效</h3>
 * 副手槽位（45）是 1.9 才出现的。1.8 上发送 slot=45 的点击本身就已经
 * 属于 [com.anticheat.core.check.impl.badpackets] 那类协议违规，
 * 不该在这里重复计入，避免同一行为被两个检测各记一次分。
 *
 * <p>参考 intave `check/other/inventoryclickanalysis/AutoTotem`
 * （同样以 100ms 为界）。</p>
 */
@CheckData(
    name = "InventoryB",
    decay = 0.05,
    setback = 0.0,
    description = "拾取物品后 100ms 内即换入副手（自动图腾的典型时序）"
)
class InventoryB(player: PlayerData) : Check(player), InventoryClickListener {

    private var lastPickupMillis = 0L

    override fun onInventoryClick(update: InventoryClickUpdate) {
        if (player.clientVersion.isOlderThan(ClientVersion.V_1_9)) {
            // 1.8 没有副手，本判据不适用
            reward()
            return
        }

        val now = System.currentTimeMillis()

        if (update.slot == InventoryClickUpdate.OFFHAND_SLOT) {
            val sincePickup =
                if (lastPickupMillis == 0L) Long.MAX_VALUE else now - lastPickupMillis
            // 无论是否告警都要消费掉这次拾取，避免一次拾取对应多次换手时重复计数
            lastPickupMillis = 0L

            if (sincePickup < AUTO_TOTEM_MILLIS) {
                flag("拾取后仅 " + sincePickup + "ms 就换入副手（阈值 " + AUTO_TOTEM_MILLIS + "ms）", VIOLATION_WEIGHT)
                return
            }
            reward()
            return
        }

        if (update.isPickup) {
            lastPickupMillis = now
            return
        }

        // 其它点击类型不参与本判据，也不构成"合规证据"——保持账本中立
        if (AntiCheatCore.tickManager.currentTick % 2L == 0L) reward()
    }

    companion object {
        /** 拾取与换手之间允许的最短时间（毫秒）。 */
        const val AUTO_TOTEM_MILLIS = 100L

        const val VIOLATION_WEIGHT = 4.0
    }
}
