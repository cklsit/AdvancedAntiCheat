package com.anticheat.core.check.impl.autoclicker

import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.SwingListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.CoreMath
import com.anticheat.core.util.math.DoubleRing
import com.anticheat.core.util.update.SwingUpdate
import com.github.retrooper.packetevents.protocol.player.ClientVersion

/**
 * 自动点击器 —— 间隔熵判据。
 *
 * <h3>为什么标准差不够，还要加熵</h3>
 * 标准差只描述"离散程度"，看不出"取值的丰富度"。考虑两种点击：
 *
 * | 情形 | 典型间隔 | 标准差 | 香农熵 |
 * |------|----------|--------|--------|
 * | 真人 | 95~130ms 连续分布 | 约 25ms | 约 4 bit |
 * | 宏（离散取值） | 只在 99/100/101 三个值之间跳 | 约 1ms | 约 1.5 bit |
 * | 宏（完全定时） | 恒定 100ms | 0 | 0 |
 *
 * <p>但参考实现把判定区间取成 **[0.35, 1.0] 而不是"小于 1.0"**，
 * 这是有讲究的：完全恒定的间隔（熵 = 0）在真实客户端上**不可能出现**——
 * 客户端自己的网络/调度抖动会把它打散成 2~3 个相邻取值，
 * 于是熵落在 0.5~1.0。所以真正的宏落在 [0.35, 1.0]，
 * 而熵 < 0.35 的极端情况说明这个窗口里的样本几乎只有一个值，
 * 那更可能是"玩家只点了一两次、窗口没填满"这类采样问题，不该据此判定。</p>
 *
 * <h3>第二重判据</h3>
 * 除了单窗口的熵，还看**连续 4 个窗口的熵是否几乎不变**（标准差 < 0.3）。
 * 真人的点击模式会在"连点"与"停手"之间切换，熵会波动；
 * 宏的熵是一根直线。
 *
 * <h3>为什么 1.13+ 关闭</h3>
 * 同 [AutoClickerA]：1.13+ 客户端的连点时序不同，阈值不成立。生产服为 1.8.8。
 *
 * <p>参考 intave `check/combat/clickpatterns/Entropy`
 * （窗口 100、熵区间 [0.35, 1.0]、熵历史 4 段且标准差阈值 0.3、4 秒窗口门）。</p>
 */
@CheckData(
    name = "AutoClickerB",
    decay = 0.05,
    setback = 0.0,
    description = "点击间隔的取值过于单一（香农熵过低）"
)
class AutoClickerB(player: PlayerData) : Check(player), SwingListener {

    private val intervals = DoubleRing(INTERVAL_SAMPLES)

    private val entropies = DoubleRing(ENTROPY_SAMPLES)

    private var blockStartMillis = 0L

    private var evidence = 0.0

    override fun onSwing(update: SwingUpdate) {
        val now = System.currentTimeMillis()

        if (!isClickSample(update, now)) {
            intervals.clear()
            entropies.clear()
            blockStartMillis = 0L
            return
        }

        if (intervals.size == 0) blockStartMillis = now
        intervals.add(update.sinceLastSwingMillis.toDouble())
        if (!intervals.isFull) return

        val elapsed = now - blockStartMillis
        val entropy = CoreMath.shannonEntropyBits(intervals.toArray())
        intervals.clear()
        entropies.add(entropy)

        var hit = false

        // 频率门 + 熵区间：取值极少，但又不是"只有一个值"
        if (elapsed < MAX_BLOCK_MILLIS && entropy >= MIN_ENTROPY && entropy <= MAX_ENTROPY) {
            evidence += 2.0
            hit = true
        }

        // 熵本身长期不变
        if (entropies.isFull) {
            val entropyDeviation = entropies.populationStandardDeviation()
            entropies.clear()
            if (elapsed < MAX_BLOCK_MILLIS && entropyDeviation < ENTROPY_DEVIATION_THRESHOLD) {
                evidence += 2.0
                hit = true
            }
        }

        if (evidence > EVIDENCE_THRESHOLD) {
            evidence = 0.0
            flag(
                "点击间隔取值过于单一：香农熵=" + String.format("%.3f", entropy) +
                    " bit（判定区间 " + MIN_ENTROPY + "~" + MAX_ENTROPY + "，窗口 " +
                    INTERVAL_SAMPLES + " 次点击耗时 " + elapsed + "ms）",
                VIOLATION_WEIGHT
            )
            return
        }

        if (!hit) {
            evidence = (evidence - 0.2).coerceAtLeast(0.0)
            reward(REWARD_ON_MISS)
        }
    }

    private fun isClickSample(update: SwingUpdate, now: Long): Boolean {
        if (player.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_13)) return false
        if (player.inDiggingNoiseWindow(now)) return false
        if (!update.hasInterval) return false
        return update.sinceLastSwingMillis <= CONTINUOUS_MILLIS
    }

    companion object {
        /** 每段统计的点击间隔样本数。样本越多，熵的估计越准，但成段越慢。 */
        const val INTERVAL_SAMPLES = 100

        /** 参与"熵是否稳定"判断的窗口数。 */
        const val ENTROPY_SAMPLES = 4

        /** 熵的判定下界：低于它更像"窗口没填满"，不判定。 */
        const val MIN_ENTROPY = 0.35

        /** 熵的判定上界：超过它说明取值已经足够丰富，是真人。 */
        const val MAX_ENTROPY = 1.0

        /** 连续窗口之间熵的标准差低于该值即认为模式僵化（bit）。 */
        const val ENTROPY_DEVIATION_THRESHOLD = 0.3

        const val MAX_BLOCK_MILLIS = 4000L

        const val CONTINUOUS_MILLIS = 4000L

        const val EVIDENCE_THRESHOLD = 3.0

        const val VIOLATION_WEIGHT = 1.0

        const val REWARD_ON_MISS = 0.2
    }
}
