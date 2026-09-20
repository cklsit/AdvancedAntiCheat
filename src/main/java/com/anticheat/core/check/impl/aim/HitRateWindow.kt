package com.anticheat.core.check.impl.aim

/**
 * 命中率滑窗：最近 [capacity] 次攻击里有多少次"准星真的落在命中盒上"，
 * 以及这段时间里**目标累计移动了多远**。纯逻辑、可离线单测。
 *
 * <h3>为什么必须同时统计目标位移</h3>
 * 命中率本身不能作为判据：打一个不动的人（挂机、卡住、被堵住的对手）
 * 天然就是 100% 命中，这是完全合法的行为。把"目标确实在移动"当成
 * 与命中率并列的必需条件，才让这条判据有判别力——**移动目标的连续百发百中**
 * 才是瞄准类作弊的特征（人类对移动目标必然有脱靶）。
 *
 * <h3>为什么换目标要把位移清零</h3>
 * 位移是"相邻两次攻击之间目标的移动距离"。如果攻击者切换了目标，
 * 两次攻击的坐标属于不同实体，直接相减得到的是两个实体之间的**空间距离**，
 * 不是目标走了多远——这会把位移凭空放大，反而**更容易误报**。
 * 所以目标 id 变化时该步位移按 0 计（宁可统计不到，也不要虚高）。
 */
class HitRateWindow(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须为正" }
    }

    private val hitFlags = BooleanArray(capacity)

    /** 每一步的位移（相对上一次攻击的目标位置）。 */
    private val stepDistances = DoubleArray(capacity)

    private var cursor = 0

    private var size = 0

    private var hitCount = 0

    private var travelled = 0.0

    private var lastEntityId = NO_TARGET

    private var lastX = 0.0

    private var lastY = 0.0

    private var lastZ = 0.0

    /** 当前窗口内的攻击次数。 */
    fun attacks(): Int = size

    fun hits(): Int = hitCount

    fun isFull(): Boolean = size == capacity

    fun capacity(): Int = capacity

    /**
     * 命中率（0.0 ~ 1.0）。窗口为空时返回 0.0——
     * **不是 1.0**：空窗口没有任何证据，返回 1.0 会让"刚上线"被当成"百发百中"。
     */
    fun hitRate(): Double = if (size == 0) 0.0 else hitCount.toDouble() / size

    /** 窗口内目标累计位移（格）。跨目标的那一步不计入。 */
    fun travelled(): Double = travelled

    /** 每次攻击平均的目标位移（格）。用于判断"目标是不是真的在动"。 */
    fun travelPerAttack(): Double = if (size == 0) 0.0 else travelled / size

    /**
     * 记录一次攻击。
     *
     * @param hit 准星是否落在目标命中盒上（由调用方用射线求交算出）
     * @param entityId 目标实体 id；与上次不同则本次位移不计入
     */
    fun onAttack(hit: Boolean, entityId: Int, x: Double, y: Double, z: Double) {
        val step = if (lastEntityId == entityId) distance(lastX, lastY, lastZ, x, y, z) else 0.0

        if (size == capacity) {
            // cursor 指向最旧的槽位：淘汰它并把它那一步的位移扣掉
            if (hitFlags[cursor]) hitCount--
            travelled -= stepDistances[cursor]
            if (travelled < 0.0) travelled = 0.0
        }

        hitFlags[cursor] = hit
        stepDistances[cursor] = step
        cursor = (cursor + 1) % capacity
        if (size < capacity) size++
        if (hit) hitCount++
        travelled += step

        lastEntityId = entityId
        lastX = x
        lastY = y
        lastZ = z
    }

    /**
     * 清空窗口。
     *
     * <p>换世界、传送、骑乘、高延迟等"不该判定的时刻"都要调用它：
     * 让让路期间的数据把窗口填满，等于用一堆无意义的样本去凑门槛。</p>
     */
    fun reset() {
        size = 0
        cursor = 0
        hitCount = 0
        travelled = 0.0
        lastEntityId = NO_TARGET
    }

    private fun distance(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun toString(): String =
        "hits=" + hitCount + "/" + size + " rate=" + String.format("%.3f", hitRate()) +
            " travel=" + String.format("%.1f", travelled)

    companion object {
        /** 默认窗口：128 次攻击。PvP 一次交手通常十几到几十刀，128 足以跨越整场战斗。 */
        const val DEFAULT_CAPACITY = 128

        const val NO_TARGET = -1
    }
}
