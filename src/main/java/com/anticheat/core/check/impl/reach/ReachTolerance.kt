package com.anticheat.core.check.impl.reach

/**
 * 伸手判定的**有效容差**：随延迟变化，而不是一个固定常数。
 *
 * <h3>为什么固定容差是错的</h3>
 * 延迟补偿（[com.anticheat.core.util.math.PointHistory.minDistanceToBoxes] 取历史最小）
 * 的能力是有边界的，边界来自"历史窗口长度"：窗口 8 tick = 400ms，
 * 而需要被覆盖的是**单程延迟**。RTT 100ms 与 RTT 400ms 的玩家，
 * 其位置历史里"能对齐客户端视角"的那一对样本的可信度完全不同。
 * 用一个 0.85 格的大容差去覆盖 400ms 的玩家，等于让低延迟玩家白拿 0.85 格的作弊空间
 * ——3.85 格才触发，意味着**所有 3.5 格左右的 reach 作弊全部漏判**。
 *
 * <h3>曲线怎么来的</h3>
 * 三段，全部单调：
 * ```
 * ping ≤ PING_FREE_MS(200)   → base                （单程 ≤ 100ms，窗口 400ms，对齐余量 4 倍）
 * 200 < ping < 400           → base → base + maxSlack（线性；丢包重传会让"客户端当时的位置"
 *                                                    落到窗口边缘，按延迟线性放宽）
 * ping ≥ 400                 → base + maxSlack      （与旧版 0.85 的固定容差持平，不产生回归）
 * ```
 * `ping ≥ MAX_JUDGEABLE_PING` 时 [TargetTracker.canJudge] 已经直接放弃判定，
 * 所以最后一段只是把"能判的边缘范围"填满，不是给高延迟玩家无限放宽。
 *
 * <p>参考实现的对照：Grim 的 `Reach` 同样使用「动态容差 + 延迟修正」，
 * 而不是单一常数；intave 的 `AttackRaytrace` 则直接按延迟配对位置。
 * 本实现选择"取历史最小 + 按延迟放宽"的组合，原因是它不依赖事务号反推，
 * 算错的后果是"更宽容"而不是"误报"。</p>
 */
object ReachTolerance {

    /**
     * 基础容差（格）。对 `ping ≤ [PING_FREE_MS]` 的玩家生效。
     *
     * <p>0.40 不是估的，是标定仿真的产物：`ReachCalibrationTest` 用**真实算法**复算了
     * 静止 / 冲刺逃逸 / 冲刺逼近 / 横移 / 跳跃 / 双方移动 / 交错 七类场景 × 0~400ms 延迟，
     * 其中"客户端在自认为正好 3.0 格时出手"的极限攻击，服务端实测上界最高到
     * **3.46 格**（跳跃目标 + 400ms），最低 3.00（静止贴上限）。0.40 让最紧的一档
     * （300ms 以内）阈值落在 3.40，距实测上界仍有余量，同时比旧的固定 0.85 收紧了 0.45 格
     * ——这意味着 3.5 格左右的 reach 作弊从"完全看不见"变成"能被累积捕捉"。</p>
     *
     * <p>注意实测可以通过 3.1（原版允许值）是**正常**的：客户端的出手判断基于它自己
     * 滞后 d tick 的视角，而服务端算的是当前位置，两者的差就是目标在这 d tick 里的位移。
     * 这也是必须保留一部分容差、而不能把阈值压到 3.1 的根本原因。</p>
     */
    const val BASE = 0.40

    /** 该延迟以下不额外放宽（单程延迟远小于历史窗口）。 */
    const val PING_FREE_MS = 200.0

    /** 该延迟及以上按满额放宽；再高本来就不判定了（见 [TargetTracker.MAX_JUDGEABLE_PING]）。 */
    const val PING_FULL_MS = 400.0

    /**
     * 满额放宽量（格）。
     *
     * <p>取 0.45 使 `400ms` 玩家的有效容差正好回到旧版的 0.8，**不产生回归**：
     * 老实的移动网络玩家不会因为这次收紧而开始被误报，而低延迟玩家的判定被收紧。</p>
     */
    const val MAX_PING_SLACK = 0.45

    /**
     * 计算有效容差。
     *
     * @param pingMs 服务端测得的延迟（RTT，毫秒）。非正数按 0 处理。
     * @param base 基础容差，来自配置 `core.checks.ReachA.tolerance`
     * @param maxSlack 满额放宽量，来自配置 `core.checks.ReachA.ping-slack`；0 = 退化为固定容差
     */
    @JvmStatic
    fun effective(pingMs: Int, base: Double, maxSlack: Double): Double {
        val ping = if (pingMs < 0) 0.0 else pingMs.toDouble()
        val clampedBase = if (base < 0.0) 0.0 else base
        val slack = when {
            maxSlack <= 0.0 -> 0.0
            ping <= PING_FREE_MS -> 0.0
            ping >= PING_FULL_MS -> maxSlack
            else -> maxSlack * (ping - PING_FREE_MS) / (PING_FULL_MS - PING_FREE_MS)
        }
        return clampedBase + slack
    }

    /** 默认参数下的有效容差（便于排障文案与测试引用）。 */
    @JvmStatic
    fun effective(pingMs: Int): Double = effective(pingMs, BASE, MAX_PING_SLACK)

    /**
     * 允许的**样本年龄**上限（tick）：只用最近这么多个 tick 的位置参与取最小。
     *
     * <h3>公式怎么来的</h3>
     * 客户端出手时能看到的目标位置，最多滞后它自己的单程延迟 `d`（tick），
     * 其中 `d = RTT / 100ms`（1 tick = 50ms，单程 = RTT/2）。
     * 再加 [SAMPLE_AGE_JITTER_TICKS] 覆盖非对称路由与丢包重传。
     *
     * <p>上限同时管住两件事，方向相反，缺一不可：</p>
     * - **太小**会排除掉客户端真正用过的那一对样本 -> 实测距离偏大 -> **误报**；
     * - **太大**会让"目标在窗口内靠近过"被算进来 -> 实测距离偏小 -> **漏判**
     *   （这是本检测最隐蔽的失效方式：作弊者的实测值被削到阈值以下，
     *   从数据上看完全正常）。
     *
     * <p>因此这个常数不是"随手留的余量"，而是标定仿真的直接产物：
     * `ReachCalibrationTest` 里"每个延迟下能抓到的最小 reach"这张表就是它的验收标准。</p>
     */
    @JvmStatic
    fun maxSampleAgeTicks(pingMs: Int): Int {
        val ping = if (pingMs < 0) 0.0 else pingMs.toDouble()
        val oneWayTicks = Math.ceil(ping / ONE_WAY_MILLIS_PER_TICK).toInt()
        return oneWayTicks + SAMPLE_AGE_JITTER_TICKS
    }

    /** 单程延迟换算：RTT / 2 / 50ms = RTT / 100。 */
    const val ONE_WAY_MILLIS_PER_TICK = 100.0

    /** 样本年龄上限的抖动余量（tick），覆盖非对称路由与重传。 */
    const val SAMPLE_AGE_JITTER_TICKS = 1

    /** 有效容差的展示文本（2 位小数），用于告警/标定日志。 */
    @JvmStatic
    fun describe(pingMs: Int, base: Double, maxSlack: Double): String =
        String.format("%.2f", effective(pingMs, base, maxSlack))
}
