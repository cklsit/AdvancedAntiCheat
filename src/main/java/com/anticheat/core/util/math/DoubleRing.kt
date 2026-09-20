package com.anticheat.core.util.math

/**
 * 定长环形双精度缓冲。反作弊里的所有"最近 N 个样本"统计（点击间隔、朝向增量）
 * 都用它承载。
 *
 * <p>为什么不用 `ArrayDeque` + `removeFirst()`：那些容器每次增删都要搬指针/装箱，
 * 而这里的样本由**客户端可控的频率**驱动（作弊客户端一秒能灌进上千个包），
 * 必须保证每次写入都是 O(1) 且零分配。用一个 `DoubleArray` + 游标即可。</p>
 *
 * <p>注意 [size] 在未填满时小于 [capacity]，此时 [toArray] 返回的是**前 size 个**
 * 有效样本（按写入顺序），统计函数必须按 size 而不是 capacity 计算——
 * 拿未填满的缓冲区算标准差会把 0 当成有效样本，结果毫无意义。</p>
 */
class DoubleRing(val capacity: Int) {

    init {
        require(capacity > 0) { "容量必须为正" }
    }

    private val values = DoubleArray(capacity)

    private var cursor = 0

    var size: Int = 0
        private set

    /** 写入顺序的累计和，增量维护。 */
    var total: Double = 0.0
        private set

    val isFull: Boolean get() = size == capacity

    fun add(value: Double) {
        if (size == capacity) {
            total -= values[cursor]
        } else {
            size++
        }
        values[cursor] = value
        total += value
        cursor = (cursor + 1) % capacity
    }

    fun mean(): Double = if (size == 0) 0.0 else total / size

    /**
     * 总体标准差（除以 N，不是 N-1）。
     *
     * <p>刻意用总体而不是样本标准差：这里的样本**就是**我们关心的全部总体
     * （最近 N 个包），不存在"从总体抽样"的语义。样本标准差在 N 很小时
     * （点击间隔窗口只有 7~10 个）会把方差放大成原来的 N/(N-1) 倍，
     * 让阈值变得难解释。</p>
     */
    fun populationStandardDeviation(): Double {
        if (size < 2) return 0.0
        val mean = mean()
        var sum = 0.0
        for (i in 0 until size) {
            val d = values[i] - mean
            sum += d * d
        }
        return kotlin.math.sqrt(sum / size)
    }

    /**
     * 按**写入顺序**返回有效样本的副本。
     *
     * <p>注意不能直接 `values.copyOf(size)`：缓冲区绕回之后，
     * `values` 的下标 0 不再是"最旧的样本"，而是"最旧样本的下一个位置"。
     * 求均值/标准差不受影响（与顺序无关），但任何**按顺序**消费样本的
     * 逻辑（例如逐点比较、拼成序列做匹配）都会拿到错位的序列。</p>
     */
    fun toArray(): DoubleArray {
        val out = DoubleArray(size)
        if (size < capacity) {
            System.arraycopy(values, 0, out, 0, size)
        } else {
            // 已绕回：cursor 指向最旧的元素
            val tail = capacity - cursor
            System.arraycopy(values, cursor, out, 0, tail)
            System.arraycopy(values, 0, out, tail, cursor)
        }
        return out
    }

    fun clear() {
        cursor = 0
        size = 0
        total = 0.0
    }
}
