package com.anticheat.core.platform.bukkit

import com.anticheat.core.platform.api.PlatformServer
import com.anticheat.core.platform.api.entity.ServerEntitySnapshot
import com.anticheat.core.platform.api.player.PlatformPlayer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * [PlatformServer] 的 Bukkit 实现。
 *
 * <p>实体查找是这里唯一有性能风险的操作：1.8.8 **没有** `getEntity(int)`，
 * 只能 `world.entities` 全量遍历。因此实体索引独立成 [ChunkEntityIndex]，
 * 按区块增量维护（区块加载时收录、卸载时整块丢弃、未命中时小范围自愈），
 * 稳态下不再有任何全量遍历。</p>
 */
class BukkitPlatformServer(private val plugin: Plugin) : PlatformServer {

    private val entityIndex = ChunkEntityIndex(plugin)

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

    /**
     * 控制台执行命令。调用方保证主线程；失败只降级（返回值
     * 表示命令是否被认识，在这里不关心：我们无法代替服主判断“未知命令”是否应该告警）。
     */
    override fun dispatchConsoleCommand(command: String) {
        runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }
    }

    override fun beginEntityTracking() {
        runCatching { entityIndex.start() }
    }

    override fun endEntityTracking() {
        runCatching { entityIndex.stop() }
    }

    override fun getEntityIndexStats(): String = entityIndex.stats()

    override fun getEntitySnapshot(
        worldName: String?,
        entityId: Int,
        anchorX: Double,
        anchorY: Double,
        anchorZ: Double
    ): ServerEntitySnapshot? {
        if (worldName == null) return null

        val entity = entityIndex.lookup(worldName, entityId, anchorX, anchorY, anchorZ) ?: return null

        // 句柄可能已经失效（死亡/卸载/换世界）；失效即视为"本次无法判定"，
        // 同时把它从索引里剔除，避免实体 id 被服务端复用后一直命中死句柄
        if (!runCatching { entity.isValid }.getOrDefault(false)) {
            entityIndex.forget(worldName, entityId)
            return null
        }

        val location = runCatching { entity.location }.getOrNull() ?: return null
        val typeName = runCatching { entity.type.name }.getOrDefault(UNKNOWN_TYPE)

        // 实体可能已经跨区块了：顺手校正索引（位置刚读过，不额外产生一次分配）
        entityIndex.reindexIfMoved(worldName, entityId, location.blockX shr 4, location.blockZ shr 4)

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

    companion object {
        const val UNKNOWN_TYPE = "UNKNOWN"
    }
}
