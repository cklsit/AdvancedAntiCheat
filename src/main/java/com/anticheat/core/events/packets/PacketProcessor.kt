package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent

/**
 * PacketEvents 监听入口。
 *
 * <p>只做路由，不放任何判定逻辑：这个类的每个回调都在 Netty 线程上执行，
 * 一旦抛异常会直接污染连接，所以所有下游实现都必须自带 try/catch。</p>
 *
 * <p>路由按**领域**分派（战斗 / 背包 / 方块 / 移动），每个追踪器自己判断
 * "这个包是不是我的"，不认识就交回下一个。这样做的好处是新增领域时
 * 只需要加一个追踪器 + 一行分派，不必在主入口里堆 `when` 分支——
 * 而这个方法在每个玩家的每个包上都会跑，是最不该膨胀的地方。</p>
 */
class PacketProcessor : PacketListener {

    override fun onUserLogin(event: UserLoginEvent) {
        PacketPlayerTracker.onLogin(event)
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        PacketPlayerTracker.onDisconnect(event)
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        try {
            // 原始包级监听（PacketReceiveListener）：每包都派发一次。
            // TimerA / TimerB 这类检测只看包类型与到达时间，不需要语义化解析，
            // 但它们必须能看到**每一个**移动包，包括「只带 onGround 的飞行包」——
            // 那种包不会触发 Position/Rotation 回调，放在语义化链路里会漏掉。
            dispatchRawReceive(event)

            if (PacketCombatTracker.handle(event)) return
            if (PacketInventoryTracker.handle(event)) return
            if (PacketBlockTracker.handle(event)) return
            // 位置 / 朝向（内部按 WrapperPlayClientPlayerFlying 判定，非飞行包直接返回）
            PacketPlayerUpdate.handle(event)
        } catch (t: Throwable) {
            // 兜底：单个追踪器漏了 try/catch 也不能把异常漏到 Netty 管道
            CoreLog.debug("收包路由异常: " + t.message)
        }
    }

    /**
     * 原始包级派发。
     *
     * <p>这里会多做一次玩家状态表查找（下游追踪器各自会再查一次）。
     * 这是**刻意接受的重复**：把「解析出 PlayerData」下传到每个追踪器
     * 需要改掉全部追踪器的签名，而多出来的开销只是一次 ConcurrentHashMap 查找
     * （约几十纳秒），与包处理的其它成本相比可以忽略。</p>
     */
    private fun dispatchRawReceive(event: PacketReceiveEvent) {
        val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
        if (!data.alive) return
        data.checkManager.onPacketReceive(event)
    }

    override fun onPacketSend(event: PacketSendEvent) {
        try {
            // 发包侧目前只用于维护「窗口是否打开」的状态机（见 PacketInventoryTracker）
            PacketInventoryTracker.handleServerSend(event)
        } catch (t: Throwable) {
            CoreLog.debug("发包路由异常: " + t.message)
        }
    }
}
