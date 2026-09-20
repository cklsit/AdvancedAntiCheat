package com.anticheat.core.check.impl.timer

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.PacketReceiveListener
import com.anticheat.core.player.PlayerData
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying

/**
 * 移动包长间隔（blink / 攒包）。
 *
 * <h3>与 [TimerA] 的关系</h3>
 * 两者是同一枚硬币的两面：TimerA 抓"包发得**太快**"（时钟加速），
 * 本检测抓"包发得**太慢**"（客户端把包攒起来不发）。
 * 攒包是很多作弊的基础设施——先憋住几拍让服务端收不到数据，
 * 再一次性放出来，就能在服务端视角里制造"这一瞬间位移一大截"的空隙，
 * 用于绕过位移与命中判定（俗称 blink / lag switch）。
 *
 * <h3>判据</h3>
 * 原版移动包的间隔固定是 50ms。这里把**连续 9 个间隔都超过 62.5ms**
 * （即 1.25 倍）判定为攒包：
 * - 先要求**连续 3 个**长间隔成"一波"，再要求**连续 3 波**中间不出现任何
 *   正常间隔。真实网络抖动制造的单个长间隔很常见，但连着 9 个且中间没有
 *   任何一个正常间隔，在物理上几乎不可能；
 * - 超过 1000ms 的间隔视为连接停滞（重连/服务端挂起），整体重置而不是计入——
 *   那种间隔说明不上话，不是"攒包"。
 *
 * <p>阈值 62.5ms 与"连续波次"的门槛来自 intave 的 `movement/timer/PacketLoss`；
 * 那边只要求 3 个长间隔就计数，这里收紧到 9 个，原因是**我们没有它的
 * 延迟补偿链路**（intave 会用事务号与 ping 反推客户端的真实发包时刻），
 * 在不具备补偿能力时只能靠更高的连续性门槛换取同样的假阳性水平。</p>
 *
 * <p>权重 0.5 与 [TimerA] 一致：攒包同样会持续反复出现，单次不足以定性。</p>
 */
@CheckData(
    name = "TimerB",
    decay = 0.1,
    setback = 0.0,
    description = "移动包出现连续长间隔（客户端攒包 / blink）"
)
class TimerB(player: PlayerData) : Check(player), PacketReceiveListener {

    private var lastPacketNanos = 0L

    private var primed = false

    private var longGapStreak = 0

    private var waves = 0

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.packetType)) return

        val now = System.nanoTime()
        if (!primed) {
            primed = true
            lastPacketNanos = now
            return
        }
        val deltaNanos = now - lastPacketNanos
        lastPacketNanos = now
        if (deltaNanos <= 0L) return

        val deltaMillis = deltaNanos / 1_000_000.0

        // 传送后 / 连接停滞：间隔本身没有信息量，整体重置
        if (deltaMillis > STALL_GAP_MILLIS ||
            AntiCheatCore.tickManager.currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS
        ) {
            resetState()
            return
        }

        if (deltaMillis > LONG_GAP_MILLIS) {
            longGapStreak++
            if (longGapStreak >= LONG_GAPS_IN_A_ROW) {
                longGapStreak = 0
                waves++
                if (waves >= WAVES_TO_FLAG) {
                    waves = 0
                    flag(
                        "连续 " + (LONG_GAPS_IN_A_ROW * WAVES_TO_FLAG) +
                            " 个移动包间隔超过 " + String.format("%.1f", LONG_GAP_MILLIS) +
                            "ms（本次 " + String.format("%.1f", deltaMillis) + "ms）",
                        VIOLATION_WEIGHT
                    )
                }
            }
            return
        }

        // 一个正常间隔就打断整个计数链
        longGapStreak = 0
        waves = 0
        reward()
    }

    private fun resetState() {
        longGapStreak = 0
        waves = 0
        primed = false
        lastPacketNanos = 0L
    }

    companion object {
        /** 超过预期（50ms）多少算"长间隔"。 */
        const val LONG_GAP_MILLIS = 62.5

        /** 连续多少个长间隔算"一波"。 */
        const val LONG_GAPS_IN_A_ROW = 3

        /** 连续多少波算违规。 */
        const val WAVES_TO_FLAG = 3

        /** 超过这个间隔视为连接停滞，不做任何判定。 */
        const val STALL_GAP_MILLIS = 1000.0

        const val TELEPORT_IMMUNITY_TICKS = 40L

        const val VIOLATION_WEIGHT = 0.5
    }
}
