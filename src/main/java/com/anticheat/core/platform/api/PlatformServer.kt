package com.anticheat.core.platform.api

import com.anticheat.core.platform.api.entity.ServerEntitySnapshot
import com.anticheat.core.platform.api.player.PlatformPlayer
import java.util.UUID

/**
 * 服务端抽象。只暴露核心层真正用到的最小面：
 * 版本串（写进告警/verbose，便于排障）、在线玩家、玩家查找、实体查找。
 */
interface PlatformServer {

    /** 例如 `Paper 1.21.11-R0.1-SNAPSHOT (Java 21.0.10)`；用于告警脚注与 verbose 上下文。 */
    fun getPlatformImplementationString(): String

    fun getOnlinePlayerIds(): Collection<UUID>

    fun getPlayer(uuid: UUID): PlatformPlayer?

    fun getPlayerByName(name: String): PlatformPlayer?

    fun sendConsoleMessage(message: String)

    /**
     * 让平台层开始维护「实体位置」的索引。
     *
     * <p>为什么由核心层显式驱动而不是平台自己"第一次用时顺手开始"：
     * 索引需要在服务端就绪后（能注册事件时）建立，并要在卸载时明确停掉。
     * 把它挂进生命周期链路，才不会出现"某个检测先跑了一次、于是索引在别的时机被建起来"
     * 这种顺序依赖。</p>
     *
     * <p>实现必须容忍重复调用；失败只允许降级（[getEntitySnapshot] 变慢或返回 null），
     * **不允许抛出**——核心层的其它能力不依赖它。</p>
     */
    fun beginEntityTracking()

    /** 停止并释放实体索引。允许在未启动时调用。 */
    fun endEntityTracking()

    /**
     * 按实体 id 取服务端权威快照。
     *
     * <p>**只能在主线程调用**（内部会读世界与实体）。找不到、已移除、
     * 或所在世界与 [worldName] 不一致时返回 null——调用方必须把 null 当作
     * "本次无法判定" 而不是"违规"。</p>
     *
     * @param worldName 目标所在世界名。客户端上报里没有实体所在世界，必须由调用方给出。
     * @param anchorX 查询锚点（通常是发起查询的玩家坐标），仅在索引未命中时用于小范围定位。
     *   传 `NaN` 表示没有锚点，此时未命中直接返回 null。
     */
    fun getEntitySnapshot(
        worldName: String?,
        entityId: Int,
        anchorX: Double,
        anchorY: Double,
        anchorZ: Double
    ): ServerEntitySnapshot?

    /** 索引的运行时统计串（写进启动/排障日志）。未启用时返回一个说明串。 */
    fun getEntityIndexStats(): String
}
