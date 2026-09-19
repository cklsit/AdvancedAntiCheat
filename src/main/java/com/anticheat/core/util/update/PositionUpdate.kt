package com.anticheat.core.util.update

import com.github.retrooper.packetevents.util.Vector3d
import kotlin.math.sqrt

/**
 * 一次位置更新（客户端上报的位置变化）。
 *
 * <p>核心层的所有移动类检测都消费这个对象，而不是各自去解析原始包——
 * 这样「包还没收全」的中间态不会泄漏到检测里。</p>
 */
class PositionUpdate(
    val from: Vector3d,
    val to: Vector3d,
    val onGround: Boolean
) {

    val deltaX: Double = to.x - from.x
    val deltaY: Double = to.y - from.y
    val deltaZ: Double = to.z - from.z

    /** 水平位移量（忽略 Y），这是绝大多数速度类检测的判据。 */
    val deltaXZ: Double = sqrt(deltaX * deltaX + deltaZ * deltaZ)

    /** 位移向量长度。 */
    val deltaLength: Double = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

    /** 三个分量里是否有 NaN / Infinity——这是「不可能的值」，没有正常客户端会产生。 */
    val hasInvalidValue: Boolean =
        deltaX.isNaN() || deltaY.isNaN() || deltaZ.isNaN() ||
            deltaX.isInfinite() || deltaY.isInfinite() || deltaZ.isInfinite() ||
            to.x.isNaN() || to.y.isNaN() || to.z.isNaN()
}
