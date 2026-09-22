package com.anticheat.core.check

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.db.DatabaseGlue
import com.anticheat.core.events.AlertEvent
import com.anticheat.core.events.FlagEvent
import com.anticheat.core.player.PlayerData

/**
 * 检测基类。对齐 Grim 的 `Check`：
 *
 * - 元数据来自类上的 [CheckData] 注解，新增检测不需要改注册表；
 * - 违规分账本收敛在 [ViolationData]（可离线单测）；
 * - [flag] 是唯一的违规入口，内部依次做「开关 → 豁免 → 事件否决 → 记账 → 处罚决策」，
 *   避免各检测各写一套导致语义漂移。
 *
 * <p>一个检测**只能通过重写 `onXxx` 回调来产生违规**，不要在回调里直接改
 * `violations` 或自己写处罚逻辑——否则开关、豁免、外部否决三处门控都会被绕过。</p>
 */
abstract class Check(player: PlayerData) : CoreProcessor(player) {

    private val annotation: CheckData? = javaClass.getAnnotation(CheckData::class.java)

    /** 稳定标识，例如 `BadPacketsA`。 */
    val checkName: String = annotation?.name?.takeIf { it.isNotEmpty() } ?: javaClass.simpleName

    /** config.yml 里的键名。 */
    val configName: String =
        annotation?.configName?.takeIf { it.isNotEmpty() && it != "DEFAULT" } ?: checkName

    val description: String = annotation?.description ?: ""

    /** 实验性检测默认关闭。 */
    val experimental: Boolean = annotation?.experimental ?: false

    private val violationData = ViolationData(
        annotation?.decay ?: DEFAULT_DECAY,
        annotation?.setback ?: DEFAULT_SETBACK
    )

    /** 由配置驱动；构造后立即由 [reload] 覆盖。 */
    @Volatile
    var isEnabled: Boolean = true

    /** `anticheat.exempt.<configName>` 权限持有者：检测照常统计但不处罚。 */
    @Volatile
    var exemptPermission: Boolean = false

    /** 当前违规分。 */
    val violations: Double get() = violationData.violations

    /** 生效中的衰减速率。 */
    val decay: Double get() = violationData.decay

    /** setback 阈值；<= 0 表示本检测不参与拉回。 */
    val setbackVl: Double get() = violationData.setbackVl

    // ------------------------------------------------------------------ 违规入口

    fun flag(): Boolean = flag("")

    /**
     * 记一次违规（权重 1.0）。
     *
     * @return true 表示本次违规被记账（调用方通常紧接着做别的状态更新）。
     */
    fun flag(verbose: String): Boolean = flag(verbose, 1.0)

    /**
     * 记一次带权重的违规。
     *
     * @param amount 本次加分。**必须能自圆其说**：协议类判据（原版客户端不可能产生）
     *   可以用较大的权重，统计推断类判据必须用较小的权重并保证合规路径上有 [reward]。
     */
    fun flag(verbose: String, amount: Double): Boolean {
        if (!isEnabled || exemptPermission || player.exempt) return false
        if (experimental && !player.experimentalChecks) return false

        // 外部模块可以否决本次违规（返回 true = 已否决，不记账）
        val event = FlagEvent(player, this, verbose)
        AntiCheatCore.eventBus.fire(event)
        if (event.cancelled) return false

        violationData.flag(amount)
        // **先决定处罚、再落库**：违规行要如实记下"这条违规导致了什么处罚"
        // （violation.punished / punish_action）。处罚判定与这次 flag 在**同一条调用链**上
        // （不是异步），放在前面就能在落库时一次写对；反过来做只能在异步队列
        // 或已落库的行上回头补标记，两条路径都要维护，而队列随时可能已刷出去。
        val punishAction = AntiCheatCore.punishmentManager.handleViolation(player, this)
        // 落库（异步入队，不阻塞收包/主线程）。放在这里而不是各检测里：
        // 这是唯一的违规入口，也就保证了"记账"与"落库"的口径永远一致。
        DatabaseGlue.recordFlag(player, this, amount, verbose, punishAction)
        return true
    }

    /** 记违规并（若越过阈值）拉回玩家。 */
    fun flagWithSetback(verbose: String = "", amount: Double = 1.0): Boolean {
        if (!flag(verbose, amount)) return false
        setbackIfAboveSetbackVl()
        return true
    }

    /**
     * 记一次安全动作，按 decay 扣分。
     *
     * <p>**每个检测都必须在「合规路径」上调用它**，否则违规分只增不减，
     * 长时间在线后任何一次偶然抖动都会顶到阈值——这是这类框架最常见的误封来源。</p>
     */
    fun reward() {
        violationData.reward()
    }

    /** 按指定额度扣分；用于"证据越久远、扣得越多"这类渐进式降温。 */
    fun reward(amount: Double) {
        violationData.reward(amount)
    }

    // ------------------------------------------------------------------ 配置下发

    /**
     * 由配置下发逐检测参数。
     *
     * @param decay 为 null 表示配置里没写，保留注解默认值
     * @param setbackVl 同上
     */
    fun applyTuning(decay: Double?, setbackVl: Double?) {
        violationData.configure(
            decay ?: violationData.decay,
            setbackVl ?: violationData.setbackVl
        )
    }

    // ------------------------------------------------------------------ setback / 告警

    fun setbackIfAboveSetbackVl(): Boolean {
        if (!violationData.shouldSetback()) return false
        return player.setbackUtil.executeViolationSetback()
    }

    /** 由 [com.anticheat.core.manager.AlertManager] 调用，广播告警事件。 */
    fun broadcastAlert(text: String) {
        AntiCheatCore.eventBus.fire(AlertEvent(player, this, text, violations))
    }

    override fun reload() {
        AntiCheatCore.configManager.applyTo(this)
    }

    override fun toString(): String = checkName + "(" + player.name + ")"

    companion object {
        const val DEFAULT_DECAY = 0.02
        const val DEFAULT_SETBACK = 0.0
    }
}
