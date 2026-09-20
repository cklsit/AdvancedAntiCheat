package com.anticheat.core.platform.api.player

/**
 * 服务端权威状态快照。
 *
 * <p>刻意用裸值而不是平台的 Location/World 类型：核心层不该认识 Bukkit 的类。
 * 之所以需要它，是因为客户端上报的位置包在 1.8 里**不含世界名**，
 * 而 setback 与「客户端到底有没有撒谎」都必须基于服务端权威值。</p>
 *
 * <p>后面几个字段是为了消除假阳性才加的，都很关键：</p>
 * - [eyeX]/[eyeY]/[eyeZ]：**服务端算出的眼睛位置**。伸手距离（reach）与视线类判据
 *   必须从眼睛出发，而眼球高度随姿态变化（站立 1.62 / 潜行 1.54 / 爬行 0.4 / 鞘翅 0.4），
 *   自己去猜高度会直接造成误报。这里直接取服务端的 `getEyeLocation()`，一次算准。
 * - [vehicleEntityId]：骑乘中的实体 id（[NO_VEHICLE] 表示没有）。移动包节奏由**载具**
 *   驱动而非玩家客户端时钟，计时器类判据必须对骑乘玩家让路。
 * - [gliding]：是否在滑翔。鞘翅状态下的移动包节奏与眼球高度都与常规不同。
 */
class ServerSnapshot(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
    val eyeX: Double,
    val eyeY: Double,
    val eyeZ: Double,
    val vehicleEntityId: Int,
    val gliding: Boolean
) {

    /** 是否骑乘在某个实体上。 */
    val inVehicle: Boolean get() = vehicleEntityId > NO_VEHICLE

    companion object {
        /** 无载具时的 [vehicleEntityId]。 */
        const val NO_VEHICLE = -1
    }
}
