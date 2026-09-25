package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity

/**
 * 服务端**主动施加给玩家的外力**追踪（击退 / 爆炸）。
 *
 * <h3>为什么必须有它</h3>
 * 位移类判据（速度、方向）比较的是"玩家自己走出来的位移"与"原版允许的位移"。
 * 但服务端会因为受击、爆炸、钓鱼钩、被推等原因**直接给玩家一个速度**，
 * 这个速度可以远超玩家自己能达到的上限，而且方向完全由外力决定。
 * 分不清"外力造成的位移"与"作弊造成的位移"，等于把每一次 PvP 对拼都判成速度作弊。
 *
 * <p>所以这里在**发包侧**盯住两个包，把"服务端刚对这个玩家施加了外力"记进
 * [com.anticheat.core.player.PlayerData.lastExternalVelocityTick]，
 * 让位移类检测在随后的窗口内让路：</p>
 * - `ENTITY_VELOCITY`：PE 把 1.8 的 `EntityVelocity` 与 1.20.2+ 的 `SetEntityMotion`
 *   归一到同一个类型，因此**不要**按版本号分支。包里的实体 id 指明速度给谁，
 *   必须与玩家自己的实体 id 比对——否则"给旁边某个实体的击退"会被当成给玩家的；
 * - `EXPLOSION`：爆炸包是**逐个受影响玩家**下发的，里面的 motion 就是该玩家自己的
 *   速度增量，因此收到即算，不需要比 id。
 *
 * <h3>为什么记 tick 而不是把速度值也存下来</h3>
 * 存速度值意味着接下来要做"这个位移里有多少来自外力"的分解，那需要完整的物理积分
 * （本仓库的预测引擎还是骨架）。记"多久之前施加过外力"是一个粗得多、但方向安全的近似：
 * 它只会让检测**多让路一会儿**（漏判），不会因为算错分解而误报。
 *
 * <p>顺带的取舍：[FlyA][com.anticheat.core.check.impl.movement.FlyA] 与
 * [GroundSpoofA][com.anticheat.core.check.impl.movement.GroundSpoofA] **不消费**这个窗口。
 * 它们的判据是"下落得不够快"，而外力冲击只影响一拍、随后立刻回到重力递推上，
 * 余额法本身就能吸收；给它们再加一个让路窗口，等于白送一条"站在 TNT 旁边就能飞"的绕过路径。</p>
 */
object PacketVelocityTracker {

    /** @return true 表示本包已被本追踪器消费 */
    fun handleServerSend(event: PacketSendEvent): Boolean {
        return when (event.packetType) {
            PacketType.Play.Server.ENTITY_VELOCITY -> {
                handleEntityVelocity(event)
                true
            }

            PacketType.Play.Server.EXPLOSION -> {
                markExternal(event)
                true
            }

            else -> false
        }
    }

    private fun handleEntityVelocity(event: PacketSendEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return
            // 只有施加给玩家自己的速度才算外力：给别人的击退与他无关
            if (WrapperPlayServerEntityVelocity(event).entityId != data.entityId) return
            data.lastExternalVelocityTick = AntiCheatCore.tickManager.currentTick
        } catch (t: Throwable) {
            // Netty 线程：异常绝不能外抛
            CoreLog.debug("击退包处理异常: " + t.message)
        }
    }

    private fun markExternal(event: PacketSendEvent) {
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return
            data.lastExternalVelocityTick = AntiCheatCore.tickManager.currentTick
        } catch (t: Throwable) {
            CoreLog.debug("爆炸包处理异常: " + t.message)
        }
    }
}
