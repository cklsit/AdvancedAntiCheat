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
     * 按实体 id 取服务端权威快照。
     *
     * <p>**只能在主线程调用**（内部会读世界与实体）。找不到、已移除、
     * 或所在世界与 [worldName] 不一致时返回 null——调用方必须把 null 当作
     * "本次无法判定" 而不是"违规"。</p>
     *
     * @param worldName 目标所在世界名。客户端上报里没有实体所在世界，必须由调用方给出。
     */
    fun getEntitySnapshot(worldName: String?, entityId: Int): ServerEntitySnapshot?
}
