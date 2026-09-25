package com.anticheat.core.platform.api.player

import java.util.UUID

/**
 * 玩家抽象。
 *
 * <p>刻意只暴露 1.8.8 与 1.21 都存在的语义，且**不暴露 Location/World 类型**——
 * 否则核心层会被 Bukkit 类型绑死，平台层也就失去意义了。</p>
 */
interface PlatformPlayer {

    val uuid: UUID

    val name: String

    /**
     * 服务端为该玩家分配的实体 id。
     *
     * <p>为什么核心层需要它：服务端的击退包（`ENTITY_VELOCITY`）是按**实体 id**
     * 指明"这个速度施加给谁"的。分不清"给这个玩家的击退"与
     * "给他旁边某个实体的击退"，就没法在击退窗口里让位移类检测让路——
     * 而击退正是速度 / 方向类判据的头号假阳性来源。</p>
     */
    val entityId: Int

    fun sendMessage(message: String)

    fun hasPermission(permission: String): Boolean

    /** 按世界名 + 裸坐标传送；世界不存在返回 false。 */
    fun teleportTo(world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean

    fun kick(reason: String)

    fun isOnline(): Boolean

    /** 服务端权威位置快照；玩家不在线返回 null。**只能在主线程调用**。 */
    fun getServerSnapshot(): ServerSnapshot?

    /**
     * 玩家当前所在世界的脚下 [depth] 格范围内，是否存在**可站立的固体方块**。
     *
     * <p>为什么需要它：判断「客户端声称站在地面上」是真是假，最可靠的办法是看
     * 它脚下到底有没有东西可以踩。只看位移量分不开两种情况——
     * 从高处坠落时谎称落地（NoFall 类作弊）与站在下降的活塞 / 飞行机器上（完全合法）。
     * 前者脚下是空的，后者脚下就是机器本身。</p>
     *
     * <p>**只能在主线程调用**（要读世界方块）。玩家不在线或世界取不到时返回 true——
     * 取不到信息时一律往"合法"的方向让路，宁可漏判。</p>
     *
     * @param depth 向下探测的格数。要大于玩家一 tick 内可能的最大合法下沉量。
     */
    fun hasGroundSupport(depth: Double): Boolean
}
