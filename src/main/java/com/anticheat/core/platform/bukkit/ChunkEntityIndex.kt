package com.anticheat.core.platform.bukkit

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.math.ChunkGrid
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.Plugin

/**
 * 按**区块**维护的实体索引：`实体 id ↔ Entity 句柄`，增量更新，不做全量遍历。
 *
 * <h3>为什么需要它</h3>
 * 1.8.8 **没有** `World#getEntity(int)`，只能 `world.entities` 全量遍历。而伸手/
 * 视线类判据要在**每 tick、每目标**查一次实体位置。原先的实现是"全量遍历 + 5 tick TTL"，
 * 在世界实体数很大时（刷怪塔、动物农场、竞技场混战）成本随实体数线性上升，
 * 而这份成本完全可以用事件驱动消掉：区块加载时把它里面的实体收进来，
 * 区块卸载时整块丢掉。
 *
 * <h3>旧实现被替换掉的那个"不确定窗口"</h3>
 * TTL 方案有一个不能靠调参躲开的缺陷：**新建实体最多晚 5 tick 才可见**，
 * 期间查不到目标 → 跳过判定。对刚上线的竞技场、刚刷出的实体，这是**静默漏判**。
 * 本实现把"未命中"变成一次**有限邻域扫描**（见 [FALLBACK_CHUNK_RADIUS]），
 * 既自愈又不会退化成全量遍历。
 *
 * <h3>三条硬约束</h3>
 * 1. **只存句柄，不缓存位置**。`Entity` 是活对象，`entity.location` 任何时候读都是当前值；
 *    一旦把 `Location` 缓存进索引，读到的就是过期位置——目标其实已经走开了，
 *    算出的距离虚大，直接误报。这是本类唯一真正危险的写法，务必不要"优化"它。
 * 2. **失效即剔除**。句柄失效（死亡/移除/换世界）时返回 null 并从索引里删掉，
 *    避免死句柄长期占用。
 * 3. **任何异常都降级成"查不到"**。索引只是省算力的手段，不是正确性前提：
 *    查不到 → 调用方跳过判定（安全方向）。**绝不能因为索引取不到就判违规。**
 */
class ChunkEntityIndex(private val plugin: Plugin) : Listener {

    /** 单个世界的索引。 */
    private class WorldIndex {
        /** 实体 id → 句柄。 */
        val byId = HashMap<Int, Entity>(256)

        /** 实体 id → 所在区块键（反向索引：卸载区块时 O(1) 淘汰，不必遍历全表）。 */
        val chunkOfId = HashMap<Int, Long>(256)

        /** 区块键 → 该区块内的实体 id 集合。 */
        val byChunk = HashMap<Long, MutableSet<Int>>(256)
    }

    /** 所有索引状态的唯一锁。见 [MAX_ENTITIES_PER_WORLD] 处的线程模型说明。 */
    private val lock = Any()

    private val worlds = HashMap<String, WorldIndex>(4)

    private var registered = false

    private var truncationLogged = false

    // ------------------------------------------------------------------ 生命周期

    /**
     * 注册区块监听并**种一次**当前已加载的区块。
     *
     * <p>种这一次是必须的：`ChunkLoadEvent` 只对"之后加载"的区块触发，
     * 而核心层启动时服务器往往已经加载了出生点与在线玩家周围的区块。
     * 漏了这步的表现是"重启后的前几分钟检测对老区块里的实体全部失灵"。</p>
     */
    fun start() {
        if (registered) return
        registered = true
        runCatching { Bukkit.getPluginManager().registerEvents(this, plugin) }
            .onFailure {
                CoreLog.warn("实体索引监听注册失败，退化为按需查询: " + it.message)
                return
            }
        seed()
        CoreLog.info("实体索引已就绪（按区块增量维护）：" + stats())
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { HandlerList.unregisterAll(this as Listener) }
        synchronized(lock) {
            worlds.clear()
            truncationLogged = false
        }
    }

    private fun seed() {
        val loaded = runCatching { Bukkit.getWorlds() }.getOrNull() ?: return
        for (world in loaded) {
            val chunks = runCatching { world.loadedChunks }.getOrNull() ?: continue
            for (chunk in chunks) {
                indexChunk(chunk)
            }
        }
    }

    // ------------------------------------------------------------------ 区块事件

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (event.isAsynchronous) {
            // Paper 的异步区块加载路径：不在别的线程碰 Bukkit 世界数据，录到主线程再做
            runCatching {
                AntiCheatCore.scheduler.runOnMainThread(Runnable { indexChunk(event.chunk) })
            }
            return
        }
        indexChunk(event.chunk)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val world = runCatching { event.chunk.world?.name }.getOrNull() ?: return
        dropChunk(world, ChunkGrid.chunkKey(event.chunk.x, event.chunk.z))
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 按实体 id 取句柄。**只能在主线程调用。**
     *
     * @param anchorX/Y/Z 查询锚点（通常是发起查询的玩家所在位置）。仅用于
     *   "索引未命中"时的有限邻域扫描：反作弊查的都是**近处**的实体
     *   （伸手判定上限本身就在 3~6 格），锚点周边几个区块足以覆盖。
     *   传 `NaN` 表示没有锚点，此时未命中直接返回 null（安全方向）。
     */
    fun lookup(worldName: String, entityId: Int, anchorX: Double, anchorY: Double, anchorZ: Double): Entity? {
        // 先在锁内做一次纯查表（不碰 Bukkit API）：持锁期间只允许操作内部 map，
        // 否则异步区块加载线程会让主线程等在锁上，白白拖慢 tick
        val hit = synchronized(lock) { worlds[worldName]?.byId?.get(entityId) }
        if (hit != null) return hit
        return fallbackScan(worldName, entityId, anchorX, anchorY, anchorZ)
    }

    /**
     * 句柄失效（已死亡/已移除）时把它从索引里剔除。
     *
     * <p>不做这一步的后果是"死句柄"长期占位：后续查同一个 id（实体 id 会被服务端
     * 复用）会一直命中一个失效句柄，表现为"该目标永远查不到位置"。</p>
     */
    fun forget(worldName: String, entityId: Int) {
        synchronized(lock) {
            val index = worlds[worldName] ?: return
            removeEntity(index, entityId)
        }
    }

    /**
     * 实体可能已经走到别的区块了：把它的索引条目搬过去。
     *
     * <p>为什么不注册"实体移动事件"：那类事件每次位移都触发，开销远大于收益。
     * 这里只在**已经要读它位置**的时候顺带校正（调用方手上正好有刚读出的坐标，
     * 不额外产生一次 `Location` 分配）。不校正的后果是"实体换区块后查不到"，
     * 而被换出的那个区块里会残留一个指向别处的条目。</p>
     */
    fun reindexIfMoved(worldName: String, entityId: Int, chunkX: Int, chunkZ: Int) {
        val newKey = ChunkGrid.chunkKey(chunkX, chunkZ)
        synchronized(lock) {
            val index = worlds[worldName] ?: return
            val entity = index.byId[entityId] ?: return
            if (index.chunkOfId[entityId] == newKey) return
            removeEntity(index, entityId)
            addEntity(index, newKey, entity)
        }
    }

    /** 排障用的统计串；也用于启动日志（便于确认索引真的在维护而不是空转）。 */
    fun stats(): String {
        synchronized(lock) {
            if (worlds.isEmpty()) return "0 个世界 / 0 个区块 / 0 个实体"
            var chunks = 0
            var entities = 0
            for (index in worlds.values) {
                chunks += index.byChunk.size
                entities += index.byId.size
            }
            return worlds.size.toString() + " 个世界 / " + chunks + " 个区块 / " + entities + " 个实体"
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun indexChunk(chunk: org.bukkit.Chunk?) {
        if (chunk == null) return
        val world = runCatching { chunk.world?.name }.getOrNull() ?: return
        val key = runCatching { ChunkGrid.chunkKey(chunk.x, chunk.z) }.getOrNull() ?: return
        val entities = runCatching { chunk.entities }.getOrNull() ?: return
        replaceChunk(world, key, entities)
    }

    /**
     * 用一次**新读到的**实体数组整体替换某个区块的索引条目。
     *
     * <p>整体替换而不是逐个 add：区块重载（例如被卸载后又被加载）时，
     * 旧条目必须清掉，否则会留下指向已失效句柄的条目。</p>
     */
    private fun replaceChunk(worldName: String, key: Long, entities: Array<Entity>) {
        synchronized(lock) {
            val index = worlds.getOrPut(worldName) { WorldIndex() }
            val previous = index.byChunk.remove(key)
            if (previous != null) {
                for (id in previous) {
                    index.byId.remove(id)
                    index.chunkOfId.remove(id)
                }
            }
            for (entity in entities) {
                addEntity(index, key, entity)
            }
        }
    }

    private fun dropChunk(worldName: String, key: Long) {
        synchronized(lock) {
            val index = worlds[worldName] ?: return
            val ids = index.byChunk.remove(key) ?: return
            for (id in ids) {
                index.byId.remove(id)
                index.chunkOfId.remove(id)
            }
        }
    }

    private fun addEntity(index: WorldIndex, key: Long, entity: Entity) {
        val id = runCatching { entity.entityId }.getOrDefault(-1)
        if (id < 0) return
        val previousKey = index.chunkOfId[id]
        if (previousKey == key) return
        if (previousKey != null) {
            index.byChunk[previousKey]?.remove(id)
        } else if (index.byId.size >= MAX_ENTITIES_PER_WORLD) {
            // 上限只在**新增**时生效（已索引的实体照常更新），
            // 保证上限一旦触达也只是"新实体看不见"，而不是整体停止维护
            if (!truncationLogged) {
                truncationLogged = true
                CoreLog.warn(
                    "世界的实体数达到索引上限 " + MAX_ENTITIES_PER_WORLD +
                        "，超出部分不再索引（伸手/视线类判据对这些实体跳过，不会误判）"
                )
            }
            return
        }
        index.byId[id] = entity
        index.chunkOfId[id] = key
        index.byChunk.getOrPut(key) { HashSet(8) }.add(id)
    }

    private fun removeEntity(index: WorldIndex, id: Int) {
        index.byId.remove(id)
        val key = index.chunkOfId.remove(id) ?: return
        index.byChunk[key]?.remove(id)
        if (index.byChunk[key]?.isEmpty() == true) {
            index.byChunk.remove(key)
        }
    }

    /**
     * 索引未命中时的有限邻域扫描（自愈路径）。
     *
     * <p>命中场景：实体刚生成/刚加入、区块是异步加载的、或索引被上限截断。
     * 它会顺手把扫过的区块**重新索引**（用新读到的数据整体替换），
     * 因此下一次查询就走索引了。</p>
     *
     * <p>扫描范围刻意很小（默认 5×5 区块 = 以锚点为中心 80×80 格）：
     * 超出这个范围的"目标"要么不属于本次判定的合理场景，要么说明我们的数据
     * 已经严重分叉——两种情况都应该跳过而不是据此判定。</p>
     */
    private fun fallbackScan(
        worldName: String,
        entityId: Int,
        anchorX: Double,
        anchorY: Double,
        anchorZ: Double
    ): Entity? {
        if (anchorX.isNaN() || anchorY.isNaN() || anchorZ.isNaN()) return null
        val world = runCatching { Bukkit.getWorld(worldName) }.getOrNull() ?: return null

        val centerX = ChunkGrid.chunkOfWorldCoord(anchorX)
        val centerZ = ChunkGrid.chunkOfWorldCoord(anchorZ)
        val keys = ChunkGrid.neighborhood(centerX, centerZ, FALLBACK_CHUNK_RADIUS, MAX_FALLBACK_CHUNKS)

        var found: Entity? = null
        for (key in keys) {
            val cx = ChunkGrid.chunkXOf(key)
            val cz = ChunkGrid.chunkZOf(key)
            if (!runCatching { world.isChunkLoaded(cx, cz) }.getOrDefault(false)) continue
            val chunk = runCatching { world.getChunkAt(cx, cz) }.getOrNull() ?: continue
            val entities = runCatching { chunk.entities }.getOrNull() ?: continue

            replaceChunk(worldName, key, entities)

            if (found == null) {
                for (entity in entities) {
                    if (runCatching { entity.entityId }.getOrDefault(-1) == entityId) {
                        found = entity
                        break
                    }
                }
            }
        }
        return found
    }

    companion object {
        /**
         * 未命中时扫描的邻域半径（区块）。1 = 3×3，2 = 5×5。
         *
         * <p>2 对应 80×80 格：任何真实攻击场景（≤ 6 格）都远在覆盖范围内，
         * 留出余量是为了容纳"锚点与目标不在同一区块"这种常规情况。</p>
         */
        const val FALLBACK_CHUNK_RADIUS = 2

        /** 单次回退扫描最多看多少个区块（防御半径被误改大）。 */
        const val MAX_FALLBACK_CHUNKS = 25

        /**
         * 单世界索引的实体数上限。
         *
         * <p>一个"恶意/失控"的世界（例如数千个掉落物）不应该让反作弊的常驻内存
         * 无界增长。触达上限后只停止**新增**索引，已索引的照常更新——
         * 于是最坏情况退化成"部分实体查不到"（跳过判定），而不是 OOM。</p>
         */
        const val MAX_ENTITIES_PER_WORLD = 30000
    }
}
