package com.anticheat.core.manager

import com.anticheat.core.check.Check
import com.anticheat.core.check.CoreProcessor
import com.anticheat.core.check.impl.badpackets.BadPacketsA
import com.anticheat.core.check.impl.badpackets.BadPacketsB
import com.anticheat.core.check.type.PacketReceiveListener
import com.anticheat.core.check.type.PacketSendListener
import com.anticheat.core.check.type.PositionListener
import com.anticheat.core.check.type.PostPredictionListener
import com.anticheat.core.check.type.RotationListener
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.update.PositionUpdate
import com.anticheat.core.util.update.PredictionComplete
import com.anticheat.core.util.update.RotationUpdate
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent

/**
 * 每玩家一份的检测集合。对齐 Grim 的 `CheckManager`。
 *
 * <p>关键设计：构造时把全部检测实例**按接口分类成扁平数组**，
 * 事件到达时只遍历对应数组——不要用「遍历所有检测 + instanceof 判断」，
 * 因为位置/朝向回调的调用频率是每包一次，反射式的分派会成为热点。</p>
 *
 * <p>检测清单是**硬编码**的（对齐 Grim）：每玩家实例化路径上做注解扫描太慢，
 * 新增检测请显式 [register]，编译器会保证类型正确。</p>
 */
class CheckManager(val player: PlayerData) {

    private val byClass = LinkedHashMap<Class<out CoreProcessor>, CoreProcessor>()

    val checks: List<Check>

    private val packetReceiveListeners: Array<PacketReceiveListener>
    private val packetSendListeners: Array<PacketSendListener>
    private val positionListeners: Array<PositionListener>
    private val rotationListeners: Array<RotationListener>
    private val postPredictionListeners: Array<PostPredictionListener>
    private val serverTickListeners: Array<ServerTickListener>

    init {
        // ---------------- 检测登记表 ----------------
        register(BadPacketsA(player))
        register(BadPacketsB(player))
        // ---------------- 登记表结束 ----------------

        val all: List<CoreProcessor> = byClass.values.toList()
        checks = all.filterIsInstance<Check>()
        packetReceiveListeners = all.filterIsInstance<PacketReceiveListener>().toTypedArray()
        packetSendListeners = all.filterIsInstance<PacketSendListener>().toTypedArray()
        positionListeners = all.filterIsInstance<PositionListener>().toTypedArray()
        rotationListeners = all.filterIsInstance<RotationListener>().toTypedArray()
        postPredictionListeners = all.filterIsInstance<PostPredictionListener>().toTypedArray()
        serverTickListeners = all.filterIsInstance<ServerTickListener>().toTypedArray()

        reload()
    }

    fun register(processor: CoreProcessor) {
        byClass[processor.javaClass] = processor
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : CoreProcessor> get(type: Class<T>): T? = byClass[type] as T?

    fun reload() {
        for (processor in byClass.values) {
            runCatching { processor.reload() }
        }
    }

    // ------------------------------------------------------------------ 分派

    fun onPacketReceive(event: PacketReceiveEvent) {
        for (listener in packetReceiveListeners) listener.onPacketReceive(event)
    }

    fun onPacketSend(event: PacketSendEvent) {
        for (listener in packetSendListeners) listener.onPacketSend(event)
    }

    fun onPositionUpdate(update: PositionUpdate) {
        for (listener in positionListeners) listener.onPositionUpdate(update)
    }

    fun onRotationUpdate(update: RotationUpdate) {
        for (listener in rotationListeners) listener.onRotationUpdate(update)
    }

    fun onPredictionComplete(complete: PredictionComplete) {
        for (listener in postPredictionListeners) listener.onPredictionComplete(complete)
    }

    fun onServerTick() {
        for (listener in serverTickListeners) listener.onServerTick()
    }
}
