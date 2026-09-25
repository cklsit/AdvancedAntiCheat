package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction

/**
 * 玩家自身状态动作包（`ENTITY_ACTION`）解析：疾跑的开始 / 结束。
 *
 * <p>为什么必须自己追踪、不能问 Bukkit 的 `isSprinting()`：服务端那个值同样源自客户端
 * 上报，但**经过服务端自己的移动处理**，会与"客户端此刻认为的状态"错开若干 tick。
 * 疾跑方向判据（[com.anticheat.core.check.impl.movement.SprintA]）比较的是
 * 「客户端此刻声称在疾跑」与「客户端此刻上报的位移方向」，
 * 两者必须来自同一条上报链路——混用服务端权威值等于把网络延迟做成了误报。</p>
 *
 * <p>同一个包里的潜行与其它动作（下马、开马背包、开始鞘翅滑翔、起床）刻意**不记录**：
 * 目前没有任何检测消费它们，而滑翔状态本来就该由服务端权威值给出、不从客户端上报里取。
 * 等真有检测需要时再加一行 `when` 分支即可。</p>
 *
 * <p>状态卡住的后果是有限的：客户端掉线重连或重生后会重新上报，
 * 而消费方用的是余额累积，一个卡在 true 的疾跑标志只会让"没有位移"的 tick
 * 走降温分支，不会凭空判违规。</p>
 */
object PacketPlayerStateTracker {

    /** @return true 表示本包已被本追踪器消费 */
    fun handle(event: PacketReceiveEvent): Boolean {
        if (event.packetType != PacketType.Play.Client.ENTITY_ACTION) return false
        try {
            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return true
            if (!data.alive) return true

            when (WrapperPlayClientEntityAction(event).action) {
                WrapperPlayClientEntityAction.Action.START_SPRINTING -> data.sprinting = true
                WrapperPlayClientEntityAction.Action.STOP_SPRINTING -> data.sprinting = false
                else -> Unit
            }
        } catch (t: Throwable) {
            // Netty 线程：异常绝不能外抛，否则会污染连接
            CoreLog.debug("玩家状态包处理异常: " + t.message)
        }
        return true
    }
}
