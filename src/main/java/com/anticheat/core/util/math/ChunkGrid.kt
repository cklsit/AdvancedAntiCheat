package com.anticheat.core.util.math

/**
 * 区块（chunk）坐标运算，纯逻辑、可离线单测。
 *
 * <p>把"区块键怎么算、邻域按什么顺序枚举"独立出来的理由：这部分是最容易出
 * **静默错误**的地方——区块键碰撞会让两个区块的实体互相覆盖（表现为"偶尔查不到
 * 目标"），而负数坐标的除法方向写错会让整个负象限的实体全部查不到。
 * 这类错误不会抛异常，只会在线上表现为"某些玩家/某些位置检测失灵"，
 * 放在纯类里配单测是唯一可靠的防线。</p>
 */
object ChunkGrid {

    /**
     * 把区块坐标打包成 64 位键。
     *
     * <p>高 32 位放 x、低 32 位放 z。**z 必须做位掩码**：直接
     * `(z.toLong() and 0xFFFFFFFFL)` 才能让负数的补码正确落进低 32 位；
     * 若写成 `(x.toLong() shl 32) or z.toLong()`，负 z 会把符号位往高位污染，
     * 导致不同区块算出同一个键。</p>
     */
    @JvmStatic
    fun chunkKey(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    /**
     * 方块坐标 → 区块坐标。
     *
     * <p>用**算术右移**而不是 `/ 16`：`shr` 对负数是向下取整，与 MC 的坐标系一致
     * （`-1` 属于区块 `-1`，不是 `0`）。用 `/ 16` 会把 `-1` 归到 `0`，
     * 于是 x=-1 与 x=0 的方块被算成同一区块——负象限的实体查找会连带出错。</p>
     */
    @JvmStatic
    fun chunkOfBlock(blockCoord: Int): Int = blockCoord shr 4

    /** 世界坐标（double）→ 所在区块。先 floor 再取整，避免截断朝零取整。 */
    @JvmStatic
    fun chunkOfWorldCoord(coord: Double): Int = Math.floor(coord).toInt() shr 4

    /**
     * 以 [centerChunkX], [centerChunkZ] 为中心的方形邻域区块键，**由近及远**排列。
     *
     * <p>按距离排序的用处：调用方（实体索引的回退扫描）在命中后可以提前结束，
     * 顺序越靠前越可能命中，平均扫描量更小。</p>
     *
     * @param radius 半径（区块）。0 = 只有中心区块；1 = 3×3；2 = 5×5
     * @param maxChunks 返回的键数量上限（防止 radius 写大导致一次扫描上千区块）
     */
    @JvmStatic
    @JvmOverloads
    fun neighborhood(centerChunkX: Int, centerChunkZ: Int, radius: Int, maxChunks: Int = Int.MAX_VALUE): LongArray {
        require(radius >= 0) { "半径不能为负" }
        require(maxChunks > 0) { "上限必须为正" }

        val side = radius * 2 + 1
        val all = ArrayList<Long>(side * side)
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                all.add(chunkKey(centerChunkX + dx, centerChunkZ + dz))
            }
        }
        // 切比雪夫距离优先（方形），同环内按曼哈顿距离与坐标稳定排序，
        // 保证结果与输入无关地可复现（测试与排障都依赖这一点）
        all.sortWith(
            compareBy(
                { chebyshevOf(it, centerChunkX, centerChunkZ) },
                { manhattanOf(it, centerChunkX, centerChunkZ) },
                { it }
            )
        )
        if (all.size <= maxChunks) return all.toLongArray()
        return all.subList(0, maxChunks).toLongArray()
    }

    private fun chebyshevOf(key: Long, centerChunkX: Int, centerChunkZ: Int): Int {
        val dx = Math.abs(chunkXOf(key) - centerChunkX)
        val dz = Math.abs(chunkZOf(key) - centerChunkZ)
        return if (dx > dz) dx else dz
    }

    private fun manhattanOf(key: Long, centerChunkX: Int, centerChunkZ: Int): Int =
        Math.abs(chunkXOf(key) - centerChunkX) + Math.abs(chunkZOf(key) - centerChunkZ)

    @JvmStatic
    fun chunkXOf(key: Long): Int = (key shr 32).toInt()

    @JvmStatic
    fun chunkZOf(key: Long): Int = key.toInt()
}
