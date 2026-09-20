package com.anticheat.core.util.math

/**
 * 射线与轴对齐命中盒的求交（slab 法），纯逻辑、可离线单测。
 *
 * <p>它是「准星到底有没有落在目标身上」这个问题**唯一正确的算法**。
 * 拿夹角阈值近似是不行的：同样 3 度的偏差，在 0.5 格处仍在盒内、
 * 在 6 格处早已飞出盒外——夹角与"是否命中"不是同一个量。</p>
 *
 * <p>命中盒的约定与 [PointHistory.distanceToBox] 保持一致：点为**脚底中心**，
 * 水平范围 `[cx ± halfWidth]`、垂直范围 `[cy, cy + height]`。
 * 两处约定必须一致，否则会出现"距离判定说贴脸、命中率判定说没命中"的自相矛盾。</p>
 *
 * <p>slab 法对方向向量的长度不敏感，但调用方应传**单位向量**：
 * 返回值是射线参数 `t`，只有归一化后它才等于"命中距离"。</p>
 */
object RayBox {

    /**
     * 求入射交点参数。
     *
     * @return 命中时返回入射线参数 `t ≥ 0`（原点在盒内时为 0）；未命中返回 `NaN`
     */
    @JvmStatic
    fun intersect(
        originX: Double, originY: Double, originZ: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        centerX: Double, centerY: Double, centerZ: Double,
        halfWidth: Double, height: Double
    ): Double {
        var tMin = 0.0
        var tMax = Double.MAX_VALUE

        val minX = centerX - halfWidth
        val maxX = centerX + halfWidth
        val minY = centerY
        val maxY = centerY + height
        val minZ = centerZ - halfWidth
        val maxZ = centerZ + halfWidth

        // 逐轴收缩区间；任一轴上区间为空即不相交
        val x = slab(originX, dirX, minX, maxX, tMin, tMax)
        if (x == null) return Double.NaN
        tMin = x[0]
        tMax = x[1]

        val y = slab(originY, dirY, minY, maxY, tMin, tMax)
        if (y == null) return Double.NaN
        tMin = y[0]
        tMax = y[1]

        val z = slab(originZ, dirZ, minZ, maxZ, tMin, tMax)
        if (z == null) return Double.NaN
        return z[0]
    }

    /** 是否与命中盒相交（原点在盒内也算命中，对应"贴脸攻击"）。 */
    @JvmStatic
    fun hits(
        originX: Double, originY: Double, originZ: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        centerX: Double, centerY: Double, centerZ: Double,
        halfWidth: Double, height: Double
    ): Boolean = !intersect(
        originX, originY, originZ, dirX, dirY, dirZ,
        centerX, centerY, centerZ, halfWidth, height
    ).isNaN()

    /**
     * 单轴 slab 求交。
     *
     * @return 收缩后的 `[tMin, tMax]`；该轴上区间为空时返回 null
     */
    private fun slab(
        origin: Double, direction: Double, min: Double, max: Double,
        currentMin: Double, currentMax: Double
    ): DoubleArray? {
        if (Math.abs(direction) < PARALLEL_EPSILON) {
            // 与该轴平行：只有原点已经落在该轴的范围内才可能相交
            if (origin < min || origin > max) return null
            return doubleArrayOf(currentMin, currentMax)
        }
        var t1 = (min - origin) / direction
        var t2 = (max - origin) / direction
        if (t1 > t2) {
            val swap = t1
            t1 = t2
            t2 = swap
        }
        val newMin = if (t1 > currentMin) t1 else currentMin
        val newMax = if (t2 < currentMax) t2 else currentMax
        if (newMin > newMax) return null
        return doubleArrayOf(newMin, newMax)
    }

    /**
     * 被视为"与该轴平行"的方向分量阈值。
     *
     * <p>取 1e-7 而不是 0：玩家看向正下方（pitch=90）时水平分量为 0，
     * 浮点误差会让它变成 1e-8 量级的数，除法放大后得到 1e8 的 t 值，
     * 虽不至于判错但会引入无意义的数值噪声。</p>
     */
    private const val PARALLEL_EPSILON = 1e-7
}
