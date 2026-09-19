package com.anticheat.core.util.update

/**
 * 预测完成事件。
 *
 * <p>对应 Grim 的 `PredictionComplete`：物理预测引擎算出「客户端本应移动多少」之后，
 * 把与「客户端实际上报值」的偏差交给 [com.anticheat.core.check.type.PostPredictionListener]。
 * 本仓库的预测引擎目前只做骨架实现，偏差恒为 0，但接口先立住——
 * 后续补物理模拟时不需要改任何检测的签名。</p>
 */
class PredictionComplete(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double
) {

    /** 三维偏差长度。 */
    val offset: Double = kotlin.math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ)

    /** 水平偏差。垂直方向受台阶/落地修正影响大，判据通常只用水平分量。 */
    val offsetXZ: Double = kotlin.math.sqrt(offsetX * offsetX + offsetZ * offsetZ)
}
