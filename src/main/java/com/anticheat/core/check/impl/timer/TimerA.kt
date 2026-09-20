package com.anticheat.core.check.impl.timer

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.PacketReceiveListener
import com.anticheat.core.player.PlayerData
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying

/**
 * 计时器加速（Timer）——余额法。
 *
 * <h3>为什么这是最有价值的基础检测之一</h3>
 * Timer 类作弊让客户端的本地时钟跑得比服务端快，所有动作（移动、攻击、
 * 使用物品）的冷却都会随之变短。它不是某一项能力的作弊，而是**放大了其余所有作弊**，
 * 因此在任何反作弊里都必须优先覆盖。
 *
 * <h3>判据</h3>
 * 见 [TimerBalance] 的说明：按 50ms/包 的预期累积时间误差，误差超过 100ms
 * （即"比原版快了一个 tick 还多"）就记违规。检测的是**持续**加速，
 * 单次抖动会被余额的还账机制吸收掉。
 *
 * <h3>三处让路</h3>
 * 1. 服务端刚传送过 → 直接 [TimerBalance.reset]。传送后的包序与时间戳全部异常，
 *    而且如果这里只是"扣一点额度"，反而会让余额掉到下界、让之后几十秒都无法触发检测；
 * 2. 载具（船/矿车）与鞘翅飞行不参与——它们的移动包节奏由服务端实体驱动，
 *    与玩家的客户端时钟无关。**本实现暂未识别载具**，因此这类玩家可能被误报，
 *    详见下面 `TODO`；
 * 3. 余额下界给到 1000ms，容纳真实卡顿。
 *
 * `TODO(载具识别)`：需要从服务端读玩家是否骑乘实体，而当前
 * [com.anticheat.core.platform.api.player.ServerSnapshot] 还没有这个字段。
 * 在那之前，**建议骑乘/飞行玩法为主的服务器把本检测设为观察模式**
 * （`core.checks.TimerA.enabled: false`，靠告警日志人工观察）。
 */
@CheckData(
    name = "TimerA",
    decay = 0.05,
    setback = 0.0,
    description = "移动包发送频率持续快于原版（计时器加速）"
)
class TimerA(player: PlayerData) : Check(player), PacketReceiveListener {

    private val balance = TimerBalance()

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.packetType)) return

        // 传送后的包序不可信：整体重置，而不是扣一点额度
        if (AntiCheatCore.tickManager.currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS) {
            balance.reset()
            return
        }

        balance.onMovementPacket(System.nanoTime())

        if (!balance.isOverflowing()) {
            balance.onPass()
            reward()
            return
        }

        flag(
            "moved too frequently：时间余额领先 " + String.format("%.2f", balance.ticksAhead()) +
                " tick（上限 " + String.format("%.1f", TimerBalance.DEFAULT_OVERFLOW_MS) + "ms）",
            VIOLATION_WEIGHT
        )
        balance.forgive(FORGIVE_MILLIS)
    }

    override fun reload() {
        super.reload()
        balance.bufferMillis = AntiCheatCore.configManager
            .optionDouble(configName, "buffer-ms", TimerBalance.DEFAULT_BUFFER_MS)
    }

    companion object {
        /** 传送后的免疫时长（tick）。与 BadPacketsA 保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L

        /**
         * 单次违规权重。
         *
         * <p>刻意取 0.5 而不是 1：余额会持续溢出，1.5 倍速的加速器能在一秒内
         * 触发二十次，权重取 1 会让违规分瞬间打满阈值，失去"持续多久"这个维度。
         * 0.5 配合 [com.anticheat.core.check.impl.timer.TimerBalance] 的 0.05 衰减
         * （约 1.0/s 回收），150% 加速会在 2~3 秒内触发处罚，5% 加速约需 30 秒——
         * 这个梯度正好把"明显作弊"和"边缘情况"分开。</p>
         */
        const val VIOLATION_WEIGHT = 0.5

        /** 每次溢出后削掉的余额（毫秒），避免同一次超发被反复计数。 */
        const val FORGIVE_MILLIS = 15.0
    }
}
