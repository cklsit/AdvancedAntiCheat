package com.anticheat.core.platform.api

import com.anticheat.core.platform.api.player.PlatformPlayer
import java.util.UUID

/**
 * 服务端抽象。只暴露核心层真正用到的最小面：
 * 版本串（写进告警/verbose，便于排障）、在线玩家、玩家查找。
 */
interface PlatformServer {

    /** 例如 `Paper 1.21.11-R0.1-SNAPSHOT (Java 21.0.10)`；用于告警脚注与 verbose 上下文。 */
    fun getPlatformImplementationString(): String

    fun getOnlinePlayerIds(): Collection<UUID>

    fun getPlayer(uuid: UUID): PlatformPlayer?

    fun getPlayerByName(name: String): PlatformPlayer?

    fun sendConsoleMessage(message: String)
}
