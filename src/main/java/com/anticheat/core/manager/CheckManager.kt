package com.anticheat.core.manager

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CoreProcessor
import com.anticheat.core.check.impl.aim.AimA
import com.anticheat.core.check.impl.aim.AimB
import com.anticheat.core.check.impl.aim.AimC
import com.anticheat.core.check.impl.autoclicker.AutoClickerA
import com.anticheat.core.check.impl.autoclicker.AutoClickerB
import com.anticheat.core.check.impl.autoclicker.AutoClickerC
import com.anticheat.core.check.impl.autoclicker.AutoClickerD
import com.anticheat.core.check.impl.badpackets.BadPacketsA
import com.anticheat.core.check.impl.badpackets.BadPacketsB
import com.anticheat.core.check.impl.badpackets.BadPacketsC
import com.anticheat.core.check.impl.badpackets.BadPacketsD
import com.anticheat.core.check.impl.combat.NoSwingA
import com.anticheat.core.check.impl.combat.ToolSwitchA
import com.anticheat.core.check.impl.honeypot.HoneypotA
import com.anticheat.core.check.impl.inventory.InventoryA
import com.anticheat.core.check.impl.inventory.InventoryB
import com.anticheat.core.check.impl.movement.FlyA
import com.anticheat.core.check.impl.movement.GroundSpoofA
import com.anticheat.core.check.impl.movement.InventoryMoveA
import com.anticheat.core.check.impl.movement.SpeedA
import com.anticheat.core.check.impl.movement.SpeedB
import com.anticheat.core.check.impl.movement.SprintA
import com.anticheat.core.check.impl.reach.ReachA
import com.anticheat.core.check.impl.reach.ReachB
import com.anticheat.core.check.impl.reach.TargetTracker
import com.anticheat.core.check.impl.timer.TimerA
import com.anticheat.core.check.impl.timer.TimerB
import com.anticheat.core.check.impl.world.BreakRestartA
import com.anticheat.core.check.impl.world.FastPlaceA
import com.anticheat.core.check.impl.world.NukerA
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

    /**
     * `checkName` → 检测实例。
     *
     * <p>给**外部证据入口**用（目前只有蜜罐：它的命中来自方块/实体事件，
     * 不是包驱动，没有对应的回调接口）。外部来源必须能拿到具体检测实例
     * 才能走 `Check.flag` 这条唯一违规入口；否则就得自己再写一套处罚逻辑，
     * 而那正是本项目"两个引擎各有一套封禁阈值"的老问题。</p>
     */
    private val byName = LinkedHashMap<String, Check>()

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
        // 共享状态容器：不是检测，但必须最先登记——
        // ReachA / ReachB 在构造后会通过 get(TargetTracker::class.java) 取它。
        // （它们用的是 lazy 解析，所以顺序并不是正确性前提；先登记只是让语义更清楚）
        register(TargetTracker(player))
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
        register(AutoClickerD(player))
        // 瞄准
        register(AimA(player))
        register(AimB(player))
        register(AimC(player))
        // 伸手与视线（射线类）
        register(ReachA(player))
        register(ReachB(player))
        // 战斗动作
        register(NoSwingA(player))
        register(ToolSwitchA(player))
        // 移动（2026-09-23 新增：参照 LiquidBounce 的移动 / 免摔家族补齐）
        register(FlyA(player))
        register(GroundSpoofA(player))
        register(SprintA(player))
        register(SpeedA(player))
        register(SpeedB(player))
        register(InventoryMoveA(player))
        // 蜜罐（外部上报：幻象矿石 / 假掉落 / 不可能破坏进度 / 假逃脱）
        // 它不实现任何监听接口——命中来自蜜罐自己的方块/实体事件，
        // 由 com.anticheat.core.honeypot.HoneypotHooks 按名字取实例后调 flag。
        register(HoneypotA(player))
        // 世界交互
        register(BreakRestartA(player))
        register(FastPlaceA(player))
        register(NukerA(player))
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
        if (processor is Check) {
            byName[processor.checkName] = processor
        }
    }

    /** 按 [Check.checkName] 取检测实例；不存在返回 null。 */
    fun check(name: String): Check? = byName[name]

    @Suppress("UNCHECKED_CAST")
    fun <T : CoreProcessor> get(type: Class<T>): T? = byClass[type] as T?

    /**
     * 全部**检测**（不含 [TargetTracker] 这类共享状态容器）。
     *
     * <p>登记规则、算命中率分母都要用"检测"这一层视图：容器没有 checkName，
     * 混进来会在库里出现一行名字为 `TargetTracker` 的规则。</p>
     */
    fun checks(): List<Check> = byClass.values.filterIsInstance<Check>()

    companion object {

        /**
         * 检测类目录——**不依赖玩家**的那份名单。
         *
         * <p>为什么需要它：检测实例是"每个玩家一份"的（`register(Xxx(player))`），
         * 于是"把检测登记进数据库的规则表"这件事在**没有玩家在线时做不了**——
         * 而管理员恰恰可能在开服前就想改阈值。这里用类对象列一份名单，
         * 元数据从类上的 `@CheckData` 反射读取，不需要构造实例。</p>
         *
         * <p>两份名单（实例登记 + 这里）必须一致：`CoreCheckCatalogTest` 会拿它和
         * `config.yml` 的 `core.checks` 键做双向比对，漏一个就红。</p>
         */
        val CHECK_CLASSES: List<Class<out Check>> = listOf(
            BadPacketsA::class.java,
            BadPacketsB::class.java,
            BadPacketsC::class.java,
            BadPacketsD::class.java,
            InventoryA::class.java,
            InventoryB::class.java,
            TimerA::class.java,
            TimerB::class.java,
            AutoClickerA::class.java,
            AutoClickerB::class.java,
            AutoClickerC::class.java,
            AutoClickerD::class.java,
            AimA::class.java,
            AimB::class.java,
            AimC::class.java,
            ReachA::class.java,
            ReachB::class.java,
            NoSwingA::class.java,
            ToolSwitchA::class.java,
            FlyA::class.java,
            GroundSpoofA::class.java,
            SprintA::class.java,
            SpeedA::class.java,
            SpeedB::class.java,
            InventoryMoveA::class.java,
            BreakRestartA::class.java,
            FastPlaceA::class.java,
            NukerA::class.java,
            HoneypotA::class.java
        )
    }

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
        for (listener in packetReceiveListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onPacketReceive(event)
        }
    }

    fun onPacketSend(event: PacketSendEvent) {
        for (listener in packetSendListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onPacketSend(event)
        }
    }

    fun onPositionUpdate(update: PositionUpdate) {
        for (listener in positionListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onPositionUpdate(update)
        }
    }

    fun onRotationUpdate(update: RotationUpdate) {
        for (listener in rotationListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onRotationUpdate(update)
        }
    }

    fun onPredictionComplete(complete: PredictionComplete) {
        for (listener in postPredictionListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onPredictionComplete(complete)
        }
    }

    fun onServerTick() {
        for (listener in serverTickListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onServerTick()
        }
    }

    // ------------------------------------------------------------------ 动作包分派

    fun onAttack(update: AttackUpdate) {
        for (listener in attackListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onAttack(update)
        }
    }

    fun onSwing(update: SwingUpdate) {
        for (listener in swingListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onSwing(update)
        }
    }

    fun onInventoryClick(update: InventoryClickUpdate) {
        for (listener in inventoryClickListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onInventoryClick(update)
        }
    }

    fun onHeldItemChange(update: HeldItemUpdate) {
        for (listener in heldItemChangeListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onHeldItemChange(update)
        }
    }

    fun onBlockDig(update: BlockDigUpdate) {
        for (listener in blockDigListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onBlockDig(update)
        }
    }

    fun onBlockPlace(update: BlockPlaceUpdate) {
        for (listener in blockPlaceListeners) {
            // 命中率的分母：本次调用即"评估了一次"
            if (listener is Check) AntiCheatCore.database?.noteCheckEvaluation(listener.checkName)
            listener.onBlockPlace(update)
        }
    }
}
