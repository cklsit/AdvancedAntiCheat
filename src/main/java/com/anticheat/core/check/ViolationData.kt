package com.anticheat.core.check

/**
 * 单个检测的违规分账本。
 *
 * <p>刻意做成**不依赖任何平台类型**的纯类：这是整个核心层里最值得单测的逻辑，
 * 而 Bukkit 的 `Player` 在单元测试里不可构造。`Check` 只负责把事件翻译成
 * [flag] / [reward]，账本本身完全可离线验证。</p>
 *
 * <p>关于「加多少分」的量纲：所有检测共用一套 0~20 的违规分
 * （`core.punishment.threshold` 的默认值就是 20），因此 [flag] 支持传入本次权重。
 * **权重必须能自圆其说**：越接近"原版客户端不可能产生"的判据权重越高（协议类），
 * 越依赖统计推断的判据权重越低，且必须能被 [reward] 衰减掉。</p>
 */
class ViolationData(
    decay: Double,
    setbackVl: Double
) {

    /** 每次安全动作扣减的违规分。运行期可由配置覆盖（见 [configure]）。 */
    var decay: Double = decay
        private set

    /** 触发 setback 的违规分阈值；<= 0 表示本检测不参与拉回。 */
    var setbackVl: Double = setbackVl
        private set

    /** 当前违规分。 */
    var violations: Double = 0.0
        private set

    /** 最近一次 flag 的时间戳（毫秒）。用于「多久没再违规」类判据。 */
    var lastViolationTime: Long = 0L
        private set

    /**
     * 把配置里的逐检测覆盖项下发到账本。
     *
     * <p>缺了这一步，`core.checks.<名字>.decay/setback` 就会「配置写了但不生效」——
     * 正是本项目历史上出现过的那类静默失效缺陷。</p>
     */
    fun configure(decay: Double, setbackVl: Double) {
        this.decay = decay
        this.setbackVl = setbackVl
    }

    /** 记一次违规（权重 1.0）。返回新的违规分，便于调用方直接比对阈值。 */
    fun flag(): Double = flag(1.0)

    /**
     * 记一次带权重的违规。
     *
     * @param amount 本次加分；非正数直接忽略，避免出现"减分式 flag"这种反直觉用法
     */
    fun flag(amount: Double): Double {
        if (amount <= 0.0) return violations
        violations += amount
        lastViolationTime = System.currentTimeMillis()
        return violations
    }

    /**
     * 记一次「安全动作」，按 [decay] 扣分。
     *
     * <p>这是反作弊不误伤的关键：真人总会有合规的 tick，分数必须能回落，
     * 否则长时间游戏后任何一次偶然抖动都会顶到阈值。</p>
     */
    fun reward(): Double = reward(decay)

    /** 按指定额度扣分；结果不会低于 0。 */
    fun reward(amount: Double): Double {
        violations = (violations - amount).coerceAtLeast(0.0)
        return violations
    }

    /** 是否应触发 setback。setbackVl <= 0 表示该检测不参与拉回。 */
    fun shouldSetback(): Boolean = setbackVl > 0.0 && violations > setbackVl

    fun reset() {
        violations = 0.0
    }
}
