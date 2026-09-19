package com.anticheat.core.util.update

/**
 * 一次朝向更新。yaw 无界（客户端可发任意值），pitch 必须落在 [-90, 90]。
 */
class RotationUpdate(
    val fromYaw: Float,
    val toYaw: Float,
    val fromPitch: Float,
    val toPitch: Float
) {

    val deltaYaw: Float = toYaw - fromYaw

    val deltaPitch: Float = toPitch - fromPitch

    val hasInvalidValue: Boolean =
        toYaw.isNaN() || toPitch.isNaN() ||
            toYaw.isInfinite() || toPitch.isInfinite() ||
            toPitch < -90.0f || toPitch > 90.0f
}
