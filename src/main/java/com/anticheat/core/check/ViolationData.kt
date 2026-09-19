package com.anticheat.core.check

/**
 * 单个检测的违规分账本。
 *
 * <p>刻意做成**不依赖任何平台类型**的纯类：这是整个核心层里最值得单测的逻辑，
 * 而 Bukkit 的 `Player` 在单元测试里不可构造。`Check` 只负责把事件翻译成
 * [flag] / [reward]，账本本身完全可离线验证。</p>
 */
class ViolationData(
    private val decay: Double,
    private val setbackVl: Double
) {

    /** 当前违规分。 */
    var violations: Double = 0.0
        private set

    /** 最近一次 flag 的时间戳（毫秒）。用于「多久没再违规」类判据。 */
    var lastViolationTime: Long = 0L
        private set

    /** 记一次违规。返回新的违规分，便于调用方直接比对阈值。 */
    fun flag(): Double {
        violations += 1.0
        lastViolationTime = System.currentTimeMillis()
        return violations
    }

    /**
     * 记一次「安全动作」，按 decay 扣分。
     *
     * <p>这是反作弊不误伤的关键：真人总会有合规的 tick，分数必须能回落，
     * 否则长时间游戏后任何一次偶然抖动都会顶到阈值。</p>
     */
    fun reward(): Double {
        violations = (violations - decay).coerceAtLeast(0.0)
        return violations
    }

    /** 是否应触发 setback。setbackVl <= 0 表示该检测不参与拉回。 */
    fun shouldSetback(): Boolean = setbackVl > 0.0 && violations > setbackVl

    fun reset() {
        violations = 0.0
    }

    fun getSetbackVl(): Double = setbackVl

    fun getDecay(): Double = decay
}
