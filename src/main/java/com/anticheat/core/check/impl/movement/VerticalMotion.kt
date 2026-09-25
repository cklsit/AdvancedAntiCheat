package com.anticheat.core.check.impl.movement

/**
 * 原版**空中垂直运动**模型（纯逻辑、不依赖任何平台类型，可离线单测）。
 *
 * <h3>模型</h3>
 * 原版空中玩家的**连续两拍 Y 增量**之间存在确定的递推关系：
 * <pre>
 *   next = (previous - GRAVITY) * DRAG      // 0.08 是每 tick 重力，0.98 是空气阻力
 * </pre>
 * 实测序列印证了它：起跳后逐拍的 Y 增量是
 * `0.42 → 0.3332 → 0.2481 → 0.1648 → 0.0831 → 0.0030 → -0.0754 …`，
 * 累计上升 1.25 格，正是原版的跳跃高度；持续下落则收敛到终端速度
 * [TERMINAL_VELOCITY]（它是这个递推的不动点）。
 *
 * <p>这个关系与玩家的初速度无关——起跳、爆炸击退、激流冲刺、史莱姆弹射给的都是
 * **初速度**，而初速度只改变递推的起点，不改变递推本身。</p>
 *
 * <h3>为什么用递推而不是用"下落了多远"</h3>
 * 拿"空中 N tick 后应该掉了多少格"去比，需要假设玩家离开地面时的初速度是 0，
 * 于是每一次跳跃、每一次被炸飞都成了例外，例外一多判据就没了。
 * 递推关系没有这个假设：任何被原版物理驱动的运动都满足它，
 * 不满足的只有"由作弊代码直接设定坐标"的运动。
 *
 * <h3>判据的方向是单侧的</h3>
 * 只判「**下落得不够快**」（[isFallingTooSlowly]），不判"下落得太快"。
 * 这是刻意的：
 * - 下落太快有无数合法来源（爆炸、被推、掉进虚空前的加速、以及**攒包**——
 *   客户端一 tick 内补发三个位置包时，服务端这一 tick 的位移是三个 tick 之和）；
 * - 下落不够快的合法来源极少，且都能用上下文排除（液体、梯子 / 蜘蛛网、
 *   缓降 / 漂浮效果、鞘翅、允许飞行）。
 *
 * <p>顺带的好处是它对网络问题免疫：延迟与卡顿只会让实测增量**更负**，
 * 永远不会把合法玩家推进违规的一侧。</p>
 *
 * <h3>容差</h3>
 * [DEFAULT_TOLERANCE] 取 0.03，约是单 tick 重力的 40%。它要覆盖的不是物理误差
 * （递推是精确的），而是**观测误差**：服务端每 tick 读一次位置，
 * 而客户端发包的时刻与 tick 边界不对齐，一 tick 的实测增量可能混进上/下一 tick 的一部分。
 * 0.03 足以吸收这种错位，同时远小于悬停（差 0.078）与上升（差 0.088+）的特征值。
 */
object VerticalMotion {

    /** 每 tick 施加的重力（格/tick²）。 */
    const val GRAVITY = 0.08

    /** 每 tick 的空气阻力系数。 */
    const val DRAG = 0.98

    /** 起跳初速度（无跳跃提升效果）。 */
    const val JUMP_VELOCITY = 0.42

    /** 自由下落的终端速度：`GRAVITY * DRAG / (1 - DRAG)`。 */
    const val TERMINAL_VELOCITY = -3.92

    /** 判定「下落不够快」的默认容差（格/tick）。 */
    const val DEFAULT_TOLERANCE = 0.03

    /**
     * 已知上一 tick 的垂直增量，推出本 tick **应当**是多少。
     *
     * @param previousDelta 上一 tick 的 Y 增量（上升为正）
     */
    @JvmStatic
    fun expectedDelta(previousDelta: Double): Double = (previousDelta - GRAVITY) * DRAG

    /**
     * 本 tick 的垂直增量是否**下落得不够快**（即不满足重力递推）。
     *
     * <p>悬停（增量恒为 0）、匀速上升、以及所有"缓降式"飞行都会命中；
     * 跳跃、自由下落、被炸飞后回落都不会。</p>
     *
     * @param previousDelta 上一 tick 的 Y 增量
     * @param actualDelta 本 tick 实测的 Y 增量
     * @param tolerance 容差，见 [DEFAULT_TOLERANCE]
     */
    @JvmStatic
    fun isFallingTooSlowly(previousDelta: Double, actualDelta: Double, tolerance: Double): Boolean =
        actualDelta > expectedDelta(previousDelta) + tolerance

    /** 本 tick 的垂直增量比"应有的下落"高出多少（格/tick）；不为正表示没有异常。 */
    @JvmStatic
    fun shortfall(previousDelta: Double, actualDelta: Double): Double =
        actualDelta - expectedDelta(previousDelta)

    /**
     * 以 [initialVelocity] 作为**第一拍**的 Y 增量，连续 [ticks] 拍后的累计垂直位移。
     *
     * <p>顺序是"先计入本拍、再递推出下一拍"，与实测序列一致：
     * `displacementAfter(0.42, 1) == 0.42`（起跳第一拍就上升 0.42 格），
     * 而 `displacementAfter(0.42, 7)` 约等于 1.25 格，即原版的完整跳跃高度。</p>
     *
     * <p>只用于单测里复算原版运动，检测本身走 [expectedDelta] 的逐 tick 递推，不依赖它。</p>
     */
    @JvmStatic
    fun displacementAfter(initialVelocity: Double, ticks: Int): Double {
        var velocity = initialVelocity
        var total = 0.0
        repeat(ticks) {
            total += velocity
            velocity = (velocity - GRAVITY) * DRAG
        }
        return total
    }

    /**
     * 从 [initialVelocity] 起，上升段一共持续多少拍（即有多少拍的 Y 增量为正）。
     *
     * <p>用来定 [com.anticheat.core.check.impl.movement.FlyA] 的宽限期：
     * 宽限期必须**覆盖离开地面的第一拍**（那一拍没有可比的"上一拍空中增量"），
     * 而原版起跳（0.42）的上升段是 6 拍。</p>
     */
    @JvmStatic
    fun apexTick(initialVelocity: Double): Int {
        var velocity = initialVelocity
        var tick = 0
        while (velocity > 0.0 && tick < MAX_APEX_SEARCH_TICKS) {
            velocity = (velocity - GRAVITY) * DRAG
            tick++
        }
        return tick
    }

    /** [apexTick] 的搜索上限，防御"初速度极大"这种非法输入把循环跑成无限。 */
    private const val MAX_APEX_SEARCH_TICKS = 200
}
