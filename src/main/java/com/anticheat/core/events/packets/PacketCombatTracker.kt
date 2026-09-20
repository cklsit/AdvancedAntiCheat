package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.update.AttackUpdate
import com.anticheat.core.util.update.SwingUpdate
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity

/**
 * 战斗类动作包解析：攻击（`INTERACT_ENTITY`）与挥手（`ANIMATION`）。
 *
 * <p>为什么把这两个包放在一起：它们是一对因果动作，绝大多数战斗判据
 * （「攻击了但没挥手」「挥手频率异常」）都需要同时看两边，
 * 放在同一个追踪器里才能保证计数与时间戳的更新顺序一致。</p>
 *
 * <p>攻击计数与挥手计数都是**本 tick 内**的计数，由
 * [com.anticheat.core.manager.init.TickRunner] 在 tick 末尾清零——
 * 因此「同 tick 有攻击但无挥手」这种判据天然不受跨 tick 的包乱序影响。</p>
 */
object PacketCombatTracker {

    /** @return true 表示本包已被本追踪器消费 */
    fun handle(event: PacketReceiveEvent): Boolean {
        return when (event.packetType) {
            PacketType.Play.Client.INTERACT_ENTITY -> {
                handleInteract(event)
                true
            }

            PacketType.Play.Client.ANIMATION -> {
                handleAnimation(event)
                true
            }

            else -> false
        }
    }

    private fun handleInteract(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val wrapper = WrapperPlayClientInteractEntity(event)
            val update = AttackUpdate(
                targetEntityId = wrapper.entityId,
                action = wrapper.action,
                hand = wrapper.hand,
                hitPosition = wrapper.location
            )

            // 只有真攻击才计入攻击统计：右键交互（开箱/骑乘/喂食）不该进战斗判据
            if (update.isAttack) {
                data.attacksThisTick++
                data.lastAttackMillis = System.currentTimeMillis()
            }

            data.checkManager.onAttack(update)
        } catch (t: Throwable) {
            // Netty 线程：异常绝不能外抛，否则会污染连接
            CoreLog.debug("攻击包处理异常: " + t.message)
        }
    }

    private fun handleAnimation(event: PacketReceiveEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val now = System.currentTimeMillis()
            // 间隔必须在更新 lastSwingMillis **之前**算好：
            // 先写时间戳再派发的话，检测侧读到的间隔恒为 0，点击间隔统计会全部失效
            val update = SwingUpdate(
                hand = WrapperPlayClientAnimation(event).hand,
                sinceLastSwingMillis = data.millisSinceLastSwing(now)
            )

            data.swingsThisTick++
            data.lastSwingMillis = now

            data.checkManager.onSwing(update)
        } catch (t: Throwable) {
            CoreLog.debug("挥手包处理异常: " + t.message)
        }
    }
}
