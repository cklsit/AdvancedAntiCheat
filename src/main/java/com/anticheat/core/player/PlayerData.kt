package com.anticheat.core.player

import com.anticheat.core.manager.CheckManager
import com.anticheat.core.manager.SetbackTeleportUtil
import com.anticheat.core.platform.api.player.PlatformPlayer
import com.anticheat.core.platform.api.player.ServerSnapshot
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Vector3d
import java.util.UUID

/**
 * 单个玩家的全部状态。对齐 Grim 的 `GrimPlayer`。
 *
 * <p>两条状态线，务必分清：</p>
 * - **客户端上报值**（[position]/[yaw]/[onGround]）：由收包链路在网络线程写入，**不可信**；
 * - **服务端权威值**（[serverPosition] 等）：由 [com.anticheat.core.manager.TickRunner]
 *   在主线程从 Bukkit 读出，用于 setback 与「客户端到底有没有撒谎」的比对。
 *
 * <p>所有可变字段都用 `@Volatile`：写方是网络线程，读方可能是主线程。
 * 同一连接的包在 Netty 上是串行的，因此不需要更重的同步。</p>
 */
class PlayerData(
    val user: User,
    val platformPlayer: PlatformPlayer
) {

    val uuid: UUID = platformPlayer.uuid

    val name: String = platformPlayer.name

    /**
     * 服务端为本玩家分配的实体 id。
     *
     * <p>构造时取一次就够了：一个玩家在一次连接里的实体 id 不会变。
     * 缓存下来而不是每次去问平台层，是因为读它的是**发包侧的 Netty 线程**
     * （见 [com.anticheat.core.events.packets.PacketVelocityTracker]），
     * 那条路径上每个包都会跑一次。</p>
     */
    val entityId: Int = platformPlayer.entityId

    val clientVersion: ClientVersion get() = user.clientVersion

    /** 每玩家一份的检测实例集合，首次访问时构建。 */
    val checkManager: CheckManager by lazy { CheckManager(this) }

    val setbackUtil: SetbackTeleportUtil by lazy { SetbackTeleportUtil(this) }

    // ------------------------------------------------------------------ 客户端上报状态

    @Volatile
    var position: Vector3d = Vector3d(0.0, 0.0, 0.0)

    @Volatile
    var lastPosition: Vector3d = Vector3d(0.0, 0.0, 0.0)

    @Volatile
    var yaw: Float = 0.0f

    @Volatile
    var lastYaw: Float = 0.0f

    @Volatile
    var pitch: Float = 0.0f

    @Volatile
    var lastPitch: Float = 0.0f

    @Volatile
    var onGround: Boolean = false

    @Volatile
    var lastOnGround: Boolean = false

    /**
     * 客户端上报的疾跑状态（`ENTITY_ACTION` 的 START/STOP_SPRINTING）。
     *
     * <p>为什么不问 Bukkit 的 `isSprinting()`：服务端那个值同样源自客户端上报，
     * 但**经过服务端自己的移动处理**，会与客户端当前认为的状态错开若干 tick。
     * 疾跑方向判据比较的是「客户端此刻声称的疾跑」与「客户端此刻上报的位移方向」，
     * 两者必须来自同一条上报链路，否则延迟就会变成误报。</p>
     */
    @Volatile
    var sprinting: Boolean = false

    // ------------------------------------------------------------------ 服务端权威状态

    /** 主线程刷新。客户端上报值里没有世界名，setback 必须依赖它。 */
    @Volatile
    var serverWorld: String? = null

    @Volatile
    var serverPosition: Vector3d = Vector3d(0.0, 0.0, 0.0)

    @Volatile
    var serverOnGround: Boolean = false

    /**
     * 服务端算出的眼睛位置（主线程刷新）。
     *
     * <p>伸手距离与视线类判据必须从眼睛出发，而眼球高度随姿态变化
     * （站立 1.62 / 潜行 1.54 / 爬行 0.4 / 鞘翅 0.4）。自己去猜高度会直接造成误报，
     * 所以这里存服务端给的 `getEyeLocation()`。</p>
     */
    @Volatile
    var serverEyeX: Double = 0.0

    @Volatile
    var serverEyeY: Double = 0.0

    @Volatile
    var serverEyeZ: Double = 0.0

    /** 骑乘的实体 id；[ServerSnapshot.NO_VEHICLE] 表示没有。 */
    @Volatile
    var serverVehicleEntityId: Int = -1

    /** 是否正在滑翔（鞘翅）。 */
    @Volatile
    var serverGliding: Boolean = false

    /** 是否骑乘中。移动包节奏由载具驱动，计时器类判据必须让路。 */
    val serverInVehicle: Boolean get() = serverVehicleEntityId > -1

    /**
     * 服务端是否**允许**该玩家飞行（创造 / 旁观 / 插件 `/fly` 授权）。
     *
     * <p>飞行类检测的头号误报来源：大厅服与建筑服普遍在生存模式下给玩家开飞行，
     * 只看"悬在空中不下落"会把整服的人判成作弊。这一项由平台层读
     * `getAllowFlight()` 得到，比"比游戏模式"更全（覆盖插件授权）。</p>
     */
    @Volatile
    var serverFlightAllowed: Boolean = false

    /** 脚部或眼睛所在方块是水 / 岩浆。游泳与上浮不遵循重力模型。 */
    @Volatile
    var serverInLiquid: Boolean = false

    /** 脚部或眼睛所在方块会改写垂直运动（梯子 / 藤蔓 / 蜘蛛网 / 脚手架 / 细雪等）。 */
    @Volatile
    var serverMovementAlteredByBlock: Boolean = false

    /** 身上带着会改写移动的药水效果（漂浮 / 缓降 / 跳跃提升 / 迅捷 / 海豚的恩惠）。 */
    @Volatile
    var serverMovementEffectActive: Boolean = false

    /**
     * 移动类检测的**公共让路条件**。
     *
     * <p>命中任何一项都表示「原版的移动物理模型此刻不适用」：载具与鞘翅各有自己的
     * 运动方程，液体与梯子 / 蜘蛛网会改写垂直运动，药水效果直接改系数，
     * 而允许飞行时"悬在空中不下落"本身就是合法状态。</p>
     *
     * <p>刻意做成一个共享属性而不是各检测自己拼条件：这几个标志是**一起**
     * 才有意义的（漏掉任何一个都会在某类合法场景里成批误报），
     * 分散到五个检测里各写一遍，迟早有一处忘了同步。</p>
     *
     * <p>传送窗口不在这里：它需要"当前 tick"才能算，由各检测自己判。</p>
     */
    val movementPhysicsExempt: Boolean
        get() = serverInVehicle || serverGliding || serverFlightAllowed ||
            serverInLiquid || serverMovementAlteredByBlock || serverMovementEffectActive

    /** 最近一次「站在地面且未异常」的位置，setback 的落点。 */
    @Volatile
    var setbackWorld: String? = null

    @Volatile
    var setbackPosition: Vector3d = Vector3d(0.0, 0.0, 0.0)

    /**
     * 本 tick 内**攻击过的目标实体 id**。
     *
     * <p>为什么要在两个线程之间倒一次手：攻击包在 **Netty 线程**到达，
     * 而目标实体的位置只能在**主线程**读（Bukkit 世界/实体 API 不是线程安全的）。
     * 于是收包时先把 id 记进 [pendingAttackTargets]，主线程在 tick 开始时
     * 一次性取走并放进这个字段，检测再读它——这样检测永远不必自己处理跨线程。</p>
     *
     * <p>用"集合"而不是"列表"：同一 tick 内打同一个目标多次，只需要它的位置一次。</p>
     */
    @Volatile
    var attackTargetsThisTick: List<Int> = emptyList()
        private set

    private val pendingAttackTargets = LinkedHashSet<Int>()

    /** Netty 线程调用：记下一个被攻击的目标 id。 */
    fun recordAttackTarget(entityId: Int) {
        synchronized(pendingAttackTargets) {
            // 上限是防御性的：客户端可以在一秒内灌进上千个攻击包，
            // 不设上限会让这个集合无界增长（每个 id 都是一次主线程实体查找）
            if (pendingAttackTargets.size < MAX_PENDING_ATTACK_TARGETS) {
                pendingAttackTargets.add(entityId)
            }
        }
    }

    /** 主线程调用：把待处理目标搬到 [attackTargetsThisTick]。 */
    fun drainAttackTargets() {
        synchronized(pendingAttackTargets) {
            attackTargetsThisTick = pendingAttackTargets.toList()
            pendingAttackTargets.clear()
        }
    }

    // ------------------------------------------------------------------ 运行时标志

    @Volatile
    var ping: Int = 0

    /** 命中白名单/绕过权限：**检测完全不跑**（`Check.flag` 第一行就返回）、不处罚、不告警。 */
    @Volatile
    var exempt: Boolean = false

    /**
     * 是否处于赏金沙箱。
     *
     * <p>与 [exempt] 的关键区别：`exempt` 让 [com.anticheat.core.check.Check.flag]
     * 直接返回、检测**根本不跑**；而沙箱需要"检测照常打分，只是不处罚、不落库、不拉回"。
     * 用 exempt 实现沙箱会得到"没有任何证据的沙箱"，于是每次任务都只能判绕过。
     * 见 [com.anticheat.core.bounty.BountyHooks] 的类注释。</p>
     */
    @Volatile
    var sandbox: Boolean = false

    @Volatile
    var alertsEnabled: Boolean = false

    @Volatile
    var experimentalChecks: Boolean = false

    /**
     * 历史被本插件处罚过的次数（登录时从库里数），
     * 加上本次会话内已处罚过的次数。惩罚阶梯按它升档
     * （第 1 次踢、第 2 次封 1 天…）——VL 是会话内的量，重连即归零，
     * 只看 VL 的话永远升不到重档。
     */
    @Volatile
    var punishmentCount: Int = 0

    @Volatile
    var joinTick: Long = 0L

    /**
     * 最近一次「服务端把玩家传送走」的 tick。
     *
     * <p>这是位移类检测的头号假阳性来源：服务端传送后，客户端下一条位置包会带着
     * 几百格的真实位移。所有位移判据都必须在这个窗口内让路。</p>
     *
     * <p>初值取一个不大的负数而不是 `Long.MIN_VALUE`：免疫窗口的算法是
     * `currentTick - lastTeleportTick`，与 `Long.MIN_VALUE` 相减会溢出成负数，
     * 结果就是「免疫窗口永远成立、检测永远不触发」。</p>
     */
    @Volatile
    var lastTeleportTick: Long = -1000L

    /**
     * 最近一次「服务端对本玩家施加外力」（击退 / 爆炸）的 tick。
     *
     * <p>与 [lastTeleportTick] 是同一类东西：**位移的来源不是玩家自己的输入**。
     * 外力可以把速度推到远超玩家自主移动的上限，方向也完全由攻击者决定，
     * 因此速度类与方向类判据都必须在这个窗口内让路——否则每一次 PvP 对拼
     * 都会被判成速度作弊。写入方见
     * [com.anticheat.core.events.packets.PacketVelocityTracker]。</p>
     *
     * <p>初值同样取一个不大的负数而不是 `Long.MIN_VALUE`：相减会溢出成负数，
     * 结果就是「免疫窗口永远成立」。</p>
     */
    @Volatile
    var lastExternalVelocityTick: Long = -1000L

    @Volatile
    var alive: Boolean = true

    /** 本 tick 内已处理的位置包数量；1.8 客户端会把飞行/位置/朝向拆成多个包。 */
    @Volatile
    var positionPacketsThisTick: Int = 0

    /**
     * 最近一次处理的包类型（形如 `INTERACT_ENTITY`）。
     *
     * <p>写进违规记录：事后查"这个检测为什么在那一刻触发"时，
     * 包类型往往比坐标更能说明问题（例如 reach 告警要看是不是攻击包）。</p>
     */
    @Volatile
    var lastPacketType: String? = null

    /**
     * 本次会话开始时间（墙钟毫秒）。
     *
     * <p>用墙钟而不是 tick：在线时长要写进数据库，而 tick 计数在服务器卡顿时会失真；
     * 而且跨重启的档案需要的正是真实时间。</p>
     */
    @Volatile
    var sessionStartMillis: Long = System.currentTimeMillis()

    // ------------------------------------------------------------------ 动作包状态（战斗 / 背包 / 方块）

    /** 当前手持槽位（0..8）。由 `HELD_ITEM_CHANGE` 更新。 */
    @Volatile
    var heldSlot: Int = 0

    /** 上一次手持槽位；用于识别「连包携带同一 slot」这种协议层异常。 */
    @Volatile
    var lastHeldSlot: Int = 0

    /**
     * 客户端是否**认为**自己打开着容器窗口。
     *
     * <p>刻意由包层自己维护（服务端发 `OPEN_WINDOW` 置 true、任一方 `CLOSE_WINDOW` 置 false），
     * 而不是去问 Bukkit：如果查服务端，得到的永远是"真实状态"，
     * 那就不可能发现"客户端在没开窗的情况下点击容器"这类欺骗。</p>
     */
    @Volatile
    var inventoryOpen: Boolean = false

    /** 最近一次服务端要求打开的窗口 id。 */
    @Volatile
    var openWindowId: Int = 0

    /** 是否正在挖掘方块（`START_DIGGING` 后置位，`FINISHED/CANCELLED` 后清零）。 */
    @Volatile
    var breakingBlock: Boolean = false

    /** 本 tick 的攻击 / 挥手 / 窗口点击计数。用于「攻击了却没挥手」这类跨包判据。 */
    @Volatile
    var attacksThisTick: Int = 0

    @Volatile
    var swingsThisTick: Int = 0

    @Volatile
    var inventoryClicksThisTick: Int = 0

    /**
     * 本 tick 内 `START_DIGGING` 的次数。
     *
     * <p>用于「一 tick 内对多个方块下手」这类判据（nuker / 瞬破）。
     * 计数写在 [breakingBlock] 旁边而不是复用 `lastDigStartMillis`：
     * 墙钟时间戳在同一个 tick 内可以出现多次，数不出"这一 tick 到底开始了几次"。</p>
     */
    @Volatile
    var digStartsThisTick: Int = 0

    /**
     * 最近一次攻击 / 挥手 / 开始挖掘的墙钟时间（毫秒）。
     *
     * <p>为什么这类间隔用墙钟而不是 tick：点击间隔是**亚 tick 级**的物理量
     * （20 CPS = 每 50ms 一次），用 tick 计数会把它量化掉，
     * 而自动点击器的破绽恰恰在毫秒级的间隔分布上。</p>
     */
    @Volatile
    var lastAttackMillis: Long = 0L

    @Volatile
    var lastSwingMillis: Long = 0L

    @Volatile
    var lastDigStartMillis: Long = 0L

    @Volatile
    var lastDigStopMillis: Long = 0L

    /** 开始挖掘时的 tick；用于「挖掘重启间隔」判据（该判据必须用 tick，见对应检测）。 */
    @Volatile
    var digStartTick: Long = 0L

    // ------------------------------------------------------------------ 状态写入

    /** 接受一次位置更新，并把「上一次」滚动保存。 */
    fun acceptPosition(newPosition: Vector3d, ground: Boolean) {
        lastPosition = position
        lastOnGround = onGround
        position = newPosition
        onGround = ground
        positionPacketsThisTick++
    }

    fun acceptRotation(newYaw: Float, newPitch: Float, ground: Boolean) {
        lastYaw = yaw
        lastPitch = pitch
        lastOnGround = onGround
        yaw = newYaw
        pitch = newPitch
        onGround = ground
        positionPacketsThisTick++
    }

    /** 仅飞行包（1.8 / 1.9 都可能有）：只带 onGround 标志。 */
    fun acceptFlying(ground: Boolean) {
        lastOnGround = onGround
        onGround = ground
        positionPacketsThisTick++
    }

    /** 由主线程在每 tick 末尾调用，刷新权威位置并推进 setback 锚点。 */
    fun refreshServerState(snapshot: ServerSnapshot) {
        serverWorld = snapshot.world
        serverPosition = Vector3d(snapshot.x, snapshot.y, snapshot.z)
        serverOnGround = snapshot.onGround
        serverEyeX = snapshot.eyeX
        serverEyeY = snapshot.eyeY
        serverEyeZ = snapshot.eyeZ
        serverVehicleEntityId = snapshot.vehicleEntityId
        serverGliding = snapshot.gliding
        serverFlightAllowed = snapshot.flightAllowed
        serverInLiquid = snapshot.inLiquid
        serverMovementAlteredByBlock = snapshot.movementAlteredByBlock
        serverMovementEffectActive = snapshot.movementEffectActive
        if (snapshot.onGround) {
            setbackWorld = snapshot.world
            setbackPosition = Vector3d(snapshot.x, snapshot.y, snapshot.z)
        }
    }

    /** 每 tick 末尾清零包计数。 */
    fun resetTickCounters() {
        positionPacketsThisTick = 0
        attacksThisTick = 0
        swingsThisTick = 0
        inventoryClicksThisTick = 0
        digStartsThisTick = 0
        attackTargetsThisTick = emptyList()
    }

    /** 距上次挥手的毫秒数；从未挥手时返回一个很大的值而不是 0（避免被当成"刚刚挥过"）。 */
    fun millisSinceLastSwing(now: Long): Long =
        if (lastSwingMillis == 0L) Long.MAX_VALUE else now - lastSwingMillis

    /** 距上次开始挖掘的毫秒数。 */
    fun millisSinceDigStart(now: Long): Long =
        if (lastDigStartMillis == 0L) Long.MAX_VALUE else now - lastDigStartMillis

    /** 距上次结束（完成或取消）挖掘的毫秒数。 */
    fun millisSinceDigStop(now: Long): Long =
        if (lastDigStopMillis == 0L) Long.MAX_VALUE else now - lastDigStopMillis

    /**
     * 是否处于「挖掘噪声窗口」内。
     *
     * <p>挖掘方块会持续挥手。所有基于挥手间隔的检测都必须在这个窗口内让路，
     * 否则挖矿玩家会被自己的挥臂节奏判成自动点击器——这是该类检测的头号假阳性来源。</p>
     */
    fun inDiggingNoiseWindow(now: Long): Boolean =
        breakingBlock || millisSinceDigStop(now) < DIGGING_NOISE_MILLIS

    override fun toString(): String = name + "(" + uuid + ", " + clientVersion + ")"

    companion object {
        /** 结束挖掘后仍需忽略挥手间隔的时长（毫秒）。 */
        const val DIGGING_NOISE_MILLIS = 3000L

        /** 单 tick 内最多记录多少个被攻击目标（防御恶意高频攻击包）。 */
        const val MAX_PENDING_ATTACK_TARGETS = 16
    }
}
