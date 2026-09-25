package com.anticheat.core.check.impl.autoclicker

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.AttackListener
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.RateTracker
import com.anticheat.core.util.update.AttackUpdate

/**
 * 每秒攻击次数超限（ClickSpeedLimiter）。
 *
 * <h3>判据</h3>
 * 用 20 tick 的滑动窗口统计攻击次数，**达到** `max-cps`（默认 20）即记违规。
 * 这里刻意用"达到"而不是"超过"：上限被当成允许值会让"把频率精确压在阈值上"
 * 成为一条现成的绕过通路（见 [onServerTick] 里的注释）。
 * 另外单独看**单 tick 内的攻击数**：原版攻击有 10 tick 的攻击冷却，
 * 一个 tick 内出现多次攻击包在协议层就是不可能的，
 * 这属于"强的判据"，权重单独加大。
 *
 * <h3>为什么用攻击包而不是挥手包</h3>
 * 挥手包在挖掘/放置时也会发，用它算 CPS 会把建筑玩家算成超高 CPS；
 * 攻击包只在真的攻击实体时发出，语义干净。
 * 代价是**空挥不计入**——但这恰好是我们想要的：空挥不该算进"每秒攻击次数"。
 *
 * <h3>为什么默认阈值是 20 而不是更高</h3>
 * 参考实现的可调范围是 8~40、默认 20。20 CPS 已经显著高于人类极限
 * （职业玩家爆发约 12~15 CPS，且无法持续），把默认压在 20
 * 可以让"长时间稳定超过 20"成为强信号，而不必依赖更高的阈值。
 * 服务器若有特殊玩法（如自动攻击类武器），可在
 * `core.checks.AutoClickerC.max-cps` 上调。
 *
 * <p>与 [AutoClickerA]/[AutoClickerB] 不同，本检测对**所有客户端版本生效**：
 * 它只看攻击包的频率，不依赖任何版本相关的时序假设。</p>
 *
 * <p>参考 intave `check/combat/ClickSpeedLimiter`
 * （20 格环形窗口、默认上限 20、同 tick 多次攻击加重）。</p>
 */
@CheckData(
    name = "AutoClickerC",
    decay = 0.1,
    setback = 0.0,
    description = "每秒攻击次数超出人类与游戏机制上限"
)
class AutoClickerC(player: PlayerData) : Check(player), AttackListener, ServerTickListener {

    private val tracker = RateTracker()

    @Volatile
    private var maxCps: Int = DEFAULT_MAX_CPS

    override fun onAttack(update: AttackUpdate) {
        // 只有真攻击计数；右键交互（开箱/骑乘/喂食）不是攻击
        if (update.isAttack) {
            tracker.record()
        }
    }

    override fun onServerTick() {
        tracker.tick()

        if (maxCps <= 0) {
            // 配置成 0 或负数 = 关闭本检测的判定（保留统计，不产生违规）
            reward()
            return
        }

        val current = tracker.count()
        // 注意比较方向：`<` 而不是 `<=`。
        // 原实现写的是 `<=`，于是「恰好等于 max-cps」被放行——而 20 CPS 对
        // 原版客户端本来就是不可能的（1.8 左键冷却 10 tick，20 tick 窗口内
        // 最多出手 2~3 次）。把上限当成"允许值"等于给作弊留了一条
        // 「把频率精确压在阈值上」的现成通路：实测证据里就出现过
        // 攻击包贴着同一数值连发的形态。现在语义是「达到上限即违规」，
        // 想放宽请上调 max-cps 本身，而不是指望恰好等于它不会触发。
        if (current < maxCps) {
            reward()
            return
        }

        val peak = tracker.peakPerBucket()
        val weight = if (peak > MAX_ATTACKS_PER_TICK) IMPOSSIBLE_WEIGHT else LIMIT_WEIGHT
        flag(
            "CPS=" + current + " 超过上限 " + maxCps +
                "（单 tick 峰值 " + peak + " 次）",
            weight
        )
    }

    override fun reload() {
        super.reload()
        maxCps = AntiCheatCore.configManager.optionInt(configName, "max-cps", DEFAULT_MAX_CPS)
    }

    companion object {
        /** 一秒内允许的攻击次数上限。 */
        const val DEFAULT_MAX_CPS = 20

        /**
         * 单 tick 内的攻击数上限。
         *
         * <p>原版攻击冷却 10 tick，一个 tick 内理论上只可能 1 次。
         * 给到 2 是为了容忍 1.8 客户端在高速移动时可能出现的
         * "同一 tick 拆成两个攻击包"的边缘情况。</p>
         */
        const val MAX_ATTACKS_PER_TICK = 2

        /** 仅超过每秒上限时的权重。 */
        const val LIMIT_WEIGHT = 1.0

        /** 单 tick 内多次攻击——协议层不可能的强判据。 */
        const val IMPOSSIBLE_WEIGHT = 3.0
    }
}
