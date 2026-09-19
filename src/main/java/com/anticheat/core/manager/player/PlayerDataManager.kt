package com.anticheat.core.manager.player

import com.anticheat.core.player.PlayerData
import com.github.retrooper.packetevents.protocol.player.User
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线玩家状态表。对齐 Grim 的 `PlayerDataManager`。
 *
 * <p>key 用 UUID 而不是 `User`/`Player` 对象：`User` 在重连后是新实例，
 * 用对象做 key 会在玩家快速重连时留下永远清不掉的僵尸条目。</p>
 */
class PlayerDataManager {

    private val players = ConcurrentHashMap<UUID, PlayerData>()

    fun add(player: PlayerData): PlayerData? = players.put(player.uuid, player)

    fun get(uuid: UUID): PlayerData? = players[uuid]

    fun getByUser(user: User): PlayerData? = user.uuid?.let { players[it] }

    fun remove(uuid: UUID): PlayerData? = players.remove(uuid)

    fun all(): Collection<PlayerData> = players.values

    fun size(): Int = players.size

    fun clear() {
        players.clear()
    }
}
