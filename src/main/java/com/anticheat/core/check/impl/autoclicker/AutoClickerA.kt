package com.anticheat.core.check.impl.autoclicker

import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.SwingListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.DoubleRing
import com.anticheat.core.util.update.SwingUpdate
import com.github.retrooper.packetevents.protocol.player.ClientVersion

/**
 * 自动点击器 —— 间隔标准差判据。
 *
 * <h3>判据的核心思路</h3>
 * 人的手指不可能精确到毫秒。即使刻意保持节奏，连续 50 次点击的间隔标准差
 * 也在 15~40ms 量级。而自动点击器的间隔由定时器驱动，标准差通常 < 5ms。
 *
 * <p>但**只看一次标准差会误判**：一个正在被追击的玩家会因为紧张而快速乱点，
 * 某一段的标准差也可能很低。所以参考实现的做法是看**标准差本身稳不稳定**：
 * 把连续 3 段（每段 50 次点击）的标准差再求一次标准差。
 * 真人的"点击节奏"会随战况起伏，这三段标准差之间差异明显；
 * 自动点击器的三段标准差几乎一模一样，其标准差趋近 0。</p>
 *
 * <h3>`elapsed < 4000ms` 这一道门是关键</h3>
 * 50 次点击要在 4 秒内完成，意味着 **≥12.5 CPS**。这一步把所有低频率点击
 * （挖矿、慢速 PVP、建筑）整段排除掉，只在高频区间里做统计判断。
 * 少了这道门，一段"稳定但慢"的点击会直接命中判据。
 *
 * <h3>为什么 1.13+ 直接关闭</h3>
 * 1.13 起客户端对"按住左键"的处理发生变化（连续点击的时序由客户端统一调度），
 * 间隔分布与 1.8~1.12 完全不同，原判据的阈值不再成立。
 * 本项目生产服是 1.8.8，因此**按参考实现直接对 1.13+ 关闭**。
 * 若将来要覆盖现代客户端，需要重新标定阈值而不是简单打开。
 *
 * <p>参考 intave `check/combat/clickpatterns/Deviation`
 * （窗口 50、SD-of-SD 阈值 25ms、强判据 10ms、4 秒窗口门）。</p>
 */
@CheckData(
    name = "AutoClickerA",
    decay = 0.05,
    setback = 0.0,
    description = "点击间隔的标准差长期过低且稳定（自动点击器 / 宏）"
)
class AutoClickerA(player: PlayerData) : Check(player), SwingListener {

    private val intervals = DoubleRing(INTERVAL_SAMPLES)

    private val deviations = DoubleRing(DEVIATION_SAMPLES)

    private var blockStartMillis = 0L

    /** 证据累积器。连续多段命中才升级为一次违规，避免单段噪声直接定性。 */
    private var evidence = 0.0

    override fun onSwing(update: SwingUpdate) {
        val now = System.currentTimeMillis()

        if (!isClickSample(update, now)) {
            discard()
            return
        }

        if (intervals.size == 0) blockStartMillis = now
        intervals.add(update.sinceLastSwingMillis.toDouble())
        if (!intervals.isFull) return

        val elapsed = now - blockStartMillis
        val deviation = intervals.populationStandardDeviation()
        intervals.clear()

        deviations.add(deviation)
        if (!deviations.isFull) return

        val deviationOfDeviations = deviations.populationStandardDeviation()
        deviations.clear()

        if (deviationOfDeviations < SD_OF_SD_THRESHOLD && elapsed < MAX_BLOCK_MILLIS) {
            evidence += if (deviationOfDeviations < STRONG_SD_OF_SD_THRESHOLD) 2.0 else 1.0
            if (evidence > EVIDENCE_THRESHOLD) {
                evidence = 0.0
                flag(
                    "点击间隔标准差过于稳定：3 段标准差的标准差=" +
                        String.format("%.2f", deviationOfDeviations) +
                        "ms（阈值 " + SD_OF_SD_THRESHOLD + "ms，50 次点击耗时 " + elapsed + "ms）",
                    VIOLATION_WEIGHT
                )
                return
            }
        } else {
            evidence = (evidence - 0.1).coerceAtLeast(0.0)
            reward(REWARD_ON_MISS)
        }
    }

    /**
     * 这次挥手能不能当作"点击样本"。
     *
     * <p>四道排除：现代客户端（时序不同，见类注释）、挖掘噪声窗口内、
     * 没有可用间隔、以及间隔过大（间隔 >1s 说明点击流已经断了，
     * 再把它算进去会把标准差拉得虚高）。</p>
     */
    private fun isClickSample(update: SwingUpdate, now: Long): Boolean {
        if (player.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_13)) return false
        if (player.inDiggingNoiseWindow(now)) return false
        if (!update.hasInterval) return false
        return update.sinceLastSwingMillis <= CONTINUOUS_MILLIS
    }

    private fun discard() {
        intervals.clear()
        deviations.clear()
        blockStartMillis = 0L
    }

    companion object {
        /** 每段统计的点击间隔样本数。 */
        const val INTERVAL_SAMPLES = 50

        /** 参与"稳定性"判断的标准差段数。 */
        const val DEVIATION_SAMPLES = 3

        /** 标准差之间的标准差低于该值即认为节奏机械（毫秒）。 */
        const val SD_OF_SD_THRESHOLD = 25.0

        /** 低于该值直接给满分证据（毫秒）。 */
        const val STRONG_SD_OF_SD_THRESHOLD = 10.0

        /** 单段填满所需的最大耗时；超过说明点击频率不够高，不参与判定。 */
        const val MAX_BLOCK_MILLIS = 4000L

        /** 点击流中断阈值：两次挥手间隔超过它就不算连续点击。 */
        const val CONTINUOUS_MILLIS = 4000L

        /** 证据累积到该值触发一次违规。 */
        const val EVIDENCE_THRESHOLD = 2.0

        const val VIOLATION_WEIGHT = 1.0

        /** 未命中时的降温额度。 */
        const val REWARD_ON_MISS = 0.2
    }
}
