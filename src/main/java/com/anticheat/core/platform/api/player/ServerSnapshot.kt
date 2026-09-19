package com.anticheat.core.platform.api.player

/**
 * 服务端权威状态快照。
 *
 * <p>刻意用裸值而不是平台的 Location/World 类型：核心层不该认识 Bukkit 的类。
 * 之所以需要它，是因为客户端上报的位置包在 1.8 里**不含世界名**，
 * 而 setback 必须知道往哪个世界传送。</p>
 */
class ServerSnapshot(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean
)
