package com.anticheat.core.manager

import com.anticheat.core.check.Check
import com.anticheat.core.check.CoreProcessor
import com.anticheat.core.check.impl.aim.AimA
import com.anticheat.core.check.impl.autoclicker.AutoClickerA
import com.anticheat.core.check.impl.autoclicker.AutoClickerB
import com.anticheat.core.check.impl.autoclicker.AutoClickerC
import com.anticheat.core.check.impl.badpackets.BadPacketsA
import com.anticheat.core.check.impl.badpackets.BadPacketsB
import com.anticheat.core.check.impl.badpackets.BadPacketsC
import com.anticheat.core.check.impl.badpackets.BadPacketsD
import com.anticheat.core.check.impl.combat.NoSwingA
import com.anticheat.core.check.impl.combat.ToolSwitchA
import com.anticheat.core.check.impl.inventory.InventoryA
import com.anticheat.core.check.impl.inventory.InventoryB
import com.anticheat.core.check.impl.timer.TimerA
import com.anticheat.core.check.impl.timer.TimerB
import com.anticheat.core.check.impl.world.BreakRestartA
import com.anticheat.core.check.impl.world.FastPlaceA
import com.anticheat.core.check.type.AttackListener
import com.anticheat.core.check.type.BlockDigListener
import com.anticheat.core.check.type.BlockPlaceListener
import com.anticheat.core.check.type.HeldItemChangeListener
import com.anticheat.core.check.type.InventoryClickListener
import com.anticheat.core.check.type.PacketReceiveListener
import com.anticheat.core.check.type.PacketSendListener
import com.anticheat.core.check.type.PositionListener
import com.anticheat.core.check.type.PostPredictionListener
import com.anticheat.core.check.type.RotationListener
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.check.type.SwingListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.update.AttackUpdate
import com.anticheat.core.util.update.BlockDigUpdate
import com.anticheat.core.util.update.BlockPlaceUpdate
import com.anticheat.core.util.update.HeldItemUpdate
import com.anticheat.core.util.update.InventoryClickUpdate
import com.anticheat.core.util.update.PositionUpdate
import com.anticheat.core.util.update.PredictionComplete
import com.anticheat.core.util.update.RotationUpdate
import com.anticheat.core.util.update.SwingUpdate
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
 *
 * <p>一个检测可以同时实现多个分派接口（例如「攻击无挥手」同时关心攻击与挥手），
 * 它会被登记进多个数组，但 [byClass] 里只保留一份实例——
 * 因此**多个回调之间共享的计数状态不需要额外同步**，
 * 但要注意它们的调用顺序由各数组的遍历顺序决定。</p>
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

    // ---- 动作包分派（2026-09-20 新增）----
    private val attackListeners: Array<AttackListener>
    private val swingListeners: Array<SwingListener>
    private val inventoryClickListeners: Array<InventoryClickListener>
    private val heldItemChangeListeners: Array<HeldItemChangeListener>
    private val blockDigListeners: Array<BlockDigListener>
    private val blockPlaceListeners: Array<BlockPlaceListener>

    init {
        // ---------------- 检测登记表 ----------------
        // 非法数据包 / 协议违规
        register(BadPacketsA(player))
        register(BadPacketsB(player))
        register(BadPacketsC(player))
        register(BadPacketsD(player))
        // 背包与窗口
        register(InventoryA(player))
        register(InventoryB(player))
        // 收包频率（计时器）
        register(TimerA(player))
        register(TimerB(player))
        // 自动点击
        register(AutoClickerA(player))
        register(AutoClickerB(player))
        register(AutoClickerC(player))
        // 瞄准
        register(AimA(player))
        // 战斗动作
        register(NoSwingA(player))
        register(ToolSwitchA(player))
        // 世界交互
        register(BreakRestartA(player))
        register(FastPlaceA(player))
        // ---------------- 登记表结束 ----------------

        val all: List<CoreProcessor> = byClass.values.toList()
        checks = all.filterIsInstance<Check>()
        packetReceiveListeners = all.filterIsInstance<PacketReceiveListener>().toTypedArray()
        packetSendListeners = all.filterIsInstance<PacketSendListener>().toTypedArray()
        positionListeners = all.filterIsInstance<PositionListener>().toTypedArray()
        rotationListeners = all.filterIsInstance<RotationListener>().toTypedArray()
        postPredictionListeners = all.filterIsInstance<PostPredictionListener>().toTypedArray()
        serverTickListeners = all.filterIsInstance<ServerTickListener>().toTypedArray()
        attackListeners = all.filterIsInstance<AttackListener>().toTypedArray()
        swingListeners = all.filterIsInstance<SwingListener>().toTypedArray()
        inventoryClickListeners = all.filterIsInstance<InventoryClickListener>().toTypedArray()
        heldItemChangeListeners = all.filterIsInstance<HeldItemChangeListener>().toTypedArray()
        blockDigListeners = all.filterIsInstance<BlockDigListener>().toTypedArray()
        blockPlaceListeners = all.filterIsInstance<BlockPlaceListener>().toTypedArray()

        reload()
    }

    fun register(processor: CoreProcessor) {
        byClass[processor.javaClass] = processor
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : CoreProcessor> get(type: Class<T>): T? = byClass[type] as T?

    fun reload() {
        for (processor in byClass.values) {
            try {
                processor.reload()
            } catch (t: Throwable) {
                // 必须留日志：静默吞掉会让「某个检测的配置永远没下发」这种问题
                // 完全不可观测——检测照常跑，管理员改了开关却没有任何效果。
                CoreLog.warn("检测重载失败 " + processor.javaClass.simpleName + ": " + t.message)
            }
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

    // ------------------------------------------------------------------ 动作包分派

    fun onAttack(update: AttackUpdate) {
        for (listener in attackListeners) listener.onAttack(update)
    }

    fun onSwing(update: SwingUpdate) {
        for (listener in swingListeners) listener.onSwing(update)
    }

    fun onInventoryClick(update: InventoryClickUpdate) {
        for (listener in inventoryClickListeners) listener.onInventoryClick(update)
    }

    fun onHeldItemChange(update: HeldItemUpdate) {
        for (listener in heldItemChangeListeners) listener.onHeldItemChange(update)
    }

    fun onBlockDig(update: BlockDigUpdate) {
        for (listener in blockDigListeners) listener.onBlockDig(update)
    }

    fun onBlockPlace(update: BlockPlaceUpdate) {
        for (listener in blockPlaceListeners) listener.onBlockPlace(update)
    }
}
