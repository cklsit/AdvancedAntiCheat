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

    /** 命中白名单/绕过权限：检测照常跑，但不处罚、不告警。 */
    @Volatile
    var exempt: Boolean = false

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
