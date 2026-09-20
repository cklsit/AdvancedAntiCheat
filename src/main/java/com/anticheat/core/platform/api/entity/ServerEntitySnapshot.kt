package com.anticheat.core.platform.api.entity

/**
 * 一个非玩家实体的服务端权威快照。
 *
 * <p>伸手距离（reach）与视线类判据需要"目标在哪"，而目标的位置只能在主线程读。
 * 核心层不认识 Bukkit 的 `Entity`，所以由平台层翻译成这个裸值对象。</p>
 *
 * <p><b>刻意不在这里放包围盒尺寸</b>：命中盒形状是**游戏规则**而不是平台能力，
 * 由核心层的 `EntityBoxes` 统一维护（那里也更容易写测试与解释阈值来源）。
 * 平台层的职责只有"把服务端的真实状态原样搬出来"。</p>
 */
class ServerEntitySnapshot(
    val entityId: Int,
    /** 实体类型名，例如 `PLAYER` / `ZOMBIE`。 */
    val typeName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val isPlayer: Boolean,
    /** 是否仍然存活。已死亡/已移除的实体不应参与判据。 */
    val alive: Boolean,
    /** 目标所骑乘的实体 id（[NO_VEHICLE] 表示没有）。 */
    val vehicleEntityId: Int
) {

    companion object {
        const val NO_VEHICLE = -1
    }
}
