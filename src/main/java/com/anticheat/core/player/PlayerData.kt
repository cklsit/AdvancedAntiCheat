package com.anticheat.core.player

import com.anticheat.core.manager.CheckManager
import com.anticheat.core.manager.SetbackTeleportUtil
import com.anticheat.core.platform.api.player.PlatformPlayer
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

    /** 最近一次「站在地面且未异常」的位置，setback 的落点。 */
    @Volatile
    var setbackWorld: String? = null

    @Volatile
    var setbackPosition: Vector3d = Vector3d(0.0, 0.0, 0.0)

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
    fun refreshServerState(world: String?, x: Double, y: Double, z: Double, ground: Boolean) {
        serverWorld = world
        serverPosition = Vector3d(x, y, z)
        serverOnGround = ground
        if (world != null && ground) {
            setbackWorld = world
            setbackPosition = Vector3d(x, y, z)
        }
    }

    /** 每 tick 末尾清零包计数。 */
    fun resetTickCounters() {
        positionPacketsThisTick = 0
        attacksThisTick = 0
        swingsThisTick = 0
        inventoryClicksThisTick = 0
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
    }
}
