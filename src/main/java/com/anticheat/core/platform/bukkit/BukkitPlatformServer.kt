package com.anticheat.core.platform.bukkit

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.platform.api.PlatformServer
import com.anticheat.core.platform.api.entity.ServerEntitySnapshot
import com.anticheat.core.platform.api.player.PlatformPlayer
import com.anticheat.core.util.CoreLog
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * [PlatformServer] 的 Bukkit 实现。
 *
 * <p>实体查找是这里唯一有性能风险的操作：1.8.8 **没有** `getEntity(int)`，
 * 只能 `world.entities` 全量遍历。因此维护一份 `实体 id -> Entity 句柄` 的索引。</p>
 *
 * <p><b>索引里只存句柄，位置每次都现读。</b>这一点是刻意的，也是本项目里最容易
 * 被写错的一处：如果把 `Location` 一起缓存下来，索引存活期内读到的就是**过期位置**，
 * 伸手距离会因为"目标其实已经走开了"而算大，直接造成误报。
 * 句柄指向的是活对象，`entity.location` 任何时候读都是当前值。</p>
 *
 * <p>索引重建按 TTL（[INDEX_TTL_TICKS]）触发，与世界里的实体数无关地限频；
 * 不做"未命中就立刻重建"——那会让查一个不存在的 id 变成每次全量遍历。
 * 代价是新建实体最多晚 [INDEX_TTL_TICKS] tick 才可见，此时返回 null（不判定），
 * 方向是安全的。</p>
 */
class BukkitPlatformServer : PlatformServer {

    private class WorldIndex(val builtAtTick: Long, val byId: MutableMap<Int, Entity>)

    private val indexes = HashMap<String, WorldIndex>()

    override fun getPlatformImplementationString(): String =
        Bukkit.getName() + " " + Bukkit.getBukkitVersion() +
            " (Java " + System.getProperty("java.version", "unknown") + ")"

    override fun getOnlinePlayerIds(): Collection<UUID> =
        Bukkit.getOnlinePlayers().map { it.uniqueId }

    override fun getPlayer(uuid: UUID): PlatformPlayer? =
        Bukkit.getPlayer(uuid)?.let { BukkitPlayer(it) }

    override fun getPlayerByName(name: String): PlatformPlayer? =
        Bukkit.getPlayer(name)?.let { BukkitPlayer(it) }

    override fun sendConsoleMessage(message: String) {
        runCatching { Bukkit.getConsoleSender().sendMessage(message) }
    }

    override fun getEntitySnapshot(worldName: String?, entityId: Int): ServerEntitySnapshot? {
        if (worldName == null) return null
        val world = runCatching { Bukkit.getWorld(worldName) }.getOrNull() ?: return null

        val now = AntiCheatCore.tickManager.currentTick
        val cached = indexes[worldName]
        val index = if (cached != null && now - cached.builtAtTick < INDEX_TTL_TICKS) {
            cached
        } else {
            rebuild(worldName, world, now)
        } ?: return null

        val entity = index.byId[entityId] ?: return null
        // 句柄可能已经失效（死亡/卸载/换世界）；失效即视为"本次无法判定"
        if (!runCatching { entity.isValid }.getOrDefault(false)) return null

        val location = runCatching { entity.location }.getOrNull() ?: return null
        val typeName = runCatching { entity.type.name }.getOrDefault(UNKNOWN_TYPE)

        return ServerEntitySnapshot(
            entityId = entityId,
            typeName = typeName,
            x = location.x,
            y = location.y,
            z = location.z,
            isPlayer = runCatching { entity is Player }.getOrDefault(false),
            alive = runCatching { !entity.isDead }.getOrDefault(false),
            vehicleEntityId = runCatching { entity.vehicle?.entityId }
                .getOrNull() ?: ServerEntitySnapshot.NO_VEHICLE
        )
    }

    /**
     * 重建某个世界的实体索引。
     *
     * <p>失败一律降级成"没有索引"：索引是优化手段而不是正确性前提，
     * 取不到就跳过判定，**绝不能因为取不到目标位置就判违规**。</p>
     */
    private fun rebuild(worldName: String, world: org.bukkit.World, now: Long): WorldIndex? {
        val map = HashMap<Int, Entity>(64)
        val ok = runCatching {
            for (entity in world.entities) {
                map[entity.entityId] = entity
            }
        }.onFailure {
            CoreLog.debug("实体索引重建失败(" + worldName + "): " + it.message)
        }.isSuccess
        if (!ok) return null

        val index = WorldIndex(now, map)
        indexes[worldName] = index
        return index
    }

    companion object {
        /**
         * 实体索引的存活时长（tick）。
         *
         * <p>权衡：太长会让新生成的实体迟迟不可见（漏判，方向安全），
         * 太短会让全量遍历频繁发生。5 tick 下，200 实体/世界的服务器
         * 每 5 tick 付一次 200 次读，即每秒约 800 次，可忽略。</p>
         */
        const val INDEX_TTL_TICKS = 5L

        const val UNKNOWN_TYPE = "UNKNOWN"
    }
}
