package com.anticheat.core.check.impl.autoclicker

/**
 * 连击段分析 —— 纯逻辑、可离线单测。
 *
 * <h3>它在找什么</h3>
 * 把每个 tick 归成"有动作 / 没动作"之后，一段**不间断**的动作序列就是一个 *streak*。
 * 真人做不到长时间不间断：原版 1.8 客户端的左键有 10 tick 冷却
 * （`leftClickCounter`，在一次攻击后置位），所以正常玩家按住左键的出手节奏
 * 接近每 10 拍一次，不会出现"连着十几拍每拍都出手"。
 * 而自动攻击类外挂（KillAura / TriggerBot）是按 tick 无脑发包的，
 * 于是形成很长的 streak。
 *
 * <h3>为什么"同 tick 多次"要减分而不是加分</h3>
 * 同 tick 出现多次动作（双击）恰好是**手动连点**的特征——人的手指会抖，
 * 偶尔一拍点两下是常事；而按 tick 驱动的外挂是"每拍恰好一次"。
 * 所以参考实现给带双击的段减分（本类沿用），让判据偏向"机械的整齐连击"。
 *
 * <h3>与参考实现的刻意偏离</h3>
 * 参考 intave `check/combat/clickpatterns/Bursts` 把「点击（挥臂）」与「攻击」都算作
 * 动作，并在出现**放置方块**时整体作废。本项目的调用方
 * [AutoClickerD] 只用**攻击包**作为动作来源，因此：
 *
 * <ul>
 *   <li>不需要"放置作废"——放置方块、挖掘都不会发出攻击包，建筑/挖矿玩家
 *       天然不会进入判定，这比事后作废更干净；</li>
 *   <li>双击标记的作用域被收紧到**当前这一段**。参考实现里该标记只在结算一个
 *       段时才重置，于是"某处出现过一次双击"会污染之后所有段；
 *       按直觉与判据语义，它应当只描述它所在的那一段。</li>
 * </ul>
 *
 * <p>注意本类**不结算窗口末尾那个未闭合的段**（参考实现同样如此）：
 * 窗口边界会把段截断，长度天然偏短；拿截断值去判定只会低估，
 * 属于安全方向——下一个窗口会完整覆盖它。</p>
 */
object ClickStreaks {

    /**
     * 段的长度超过该值才计入违规（即需要连续 **6 tick** 以上不间断）。
     *
     * <p>参考实现取 5。原版 1.8 客户端的出手间隔约 10 tick，
     * 6 拍已经是不可能出现的密度。</p>
     */
    const val MIN_STREAK = 5

    /** 达到该级别即判定为作弊（参考实现同为 20）。 */
    const val FLAG_VL = 20.0

    /** 段内出现过"同 tick 多次"时的修正（减分）。 */
    const val ADJUST_WITH_MULTI = -1.0

    /** 段内每次都是"一拍一次"时的修正（加分）。 */
    const val ADJUST_WITHOUT_MULTI = 2.0

    /**
     * 计算窗口内的违规级别。
     *
     * @param acted 每个 tick 是否发生了被统计的动作（攻击包）
     * @param multiPerTick 每个 tick 是否发生了同 tick 多次动作（长度不足的位置按 false 处理）
     * @return 违规级别；调用方按 `>=` [FLAG_VL] 判定。0 表示窗口内没有足够长的段。
     */
    @JvmStatic
    fun violationLevel(acted: BooleanArray, multiPerTick: BooleanArray): Double {
        var streak = 0
        var multiSeen = false
        var vl = 0.0

        for (i in acted.indices) {
            if (!acted[i]) {
                // 段在此结束：只有足够长才计入
                if (streak > MIN_STREAK) {
                    vl += streak + if (multiSeen) ADJUST_WITH_MULTI else ADJUST_WITHOUT_MULTI
                }
                streak = 0
                multiSeen = false
                continue
            }

            // 新的一段从这一拍开始：双击标记只描述它所在的那一段
            if (streak == 0) multiSeen = false
            streak++
            if (i < multiPerTick.size && multiPerTick[i]) multiSeen = true
        }

        // 末尾未闭合的段不结算（见类注释）
        return vl
    }

    /**
     * 窗口内最长的不间断段长度（tick）。
     *
     * <p>单独提供它是为了标定与告警文案：违规级别是"多个段的加权和"，
     * 光看它无法回答"这个玩家到底连续出手了几拍"，而那个数字才是
     * 判断阈值该定在哪里的直接依据。</p>
     */
    @JvmStatic
    fun longestStreak(acted: BooleanArray): Int {
        var longest = 0
        var current = 0
        for (i in acted.indices) {
            if (acted[i]) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }
}
