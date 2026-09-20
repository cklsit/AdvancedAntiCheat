package com.anticheat.core.check.impl.world

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.BlockPlaceListener
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.RateTracker
import com.anticheat.core.util.update.BlockPlaceUpdate

/**
 * 放置方块频率异常（fastplace / 无冷却放置）。
 *
 * <h3>判据一：同一 tick 内多次放置（强判据）</h3>
 * 一次右键 = 一个放置包。原版客户端在成功放置后会重置右键冷却计时器，
 * 因此**一个 tick 内不可能完成两次放置**。这与 `BadPackets*` 同级：
 * 原版客户端产生不了，所以可以给较高权重。
 *
 * <h3>判据二：每秒放置次数超限（弱判据）</h3>
 * 原版的右键冷却让连续放置的上限远低于每秒 20 次。这里用 20 tick 的窗口统计，
 * 超过 `max-per-second`（默认 15）才判定。**默认特意留了约 3 倍余量**：
 * 冷却的具体长度随版本与手持物品变化（有快速装填等附魔差异），
 * 留足余量可以把"版本差异"排除在误报之外，真正抓到的是
 * 完全不遵守冷却的 fastplace 类客户端。
 *
 * <h3>为什么两个判据的权重差 3 倍</h3>
 * 同 tick 多次放置是**不可能的**（权重 3.0）；
 * 每秒频率超限是**统计推断**（权重 1.0），它可能被尚未发现的版本差异影响，
 * 所以必须给小权重并保证合规路径上持续降温。
 *
 * <p>TODO(放置质量)：参考实现的放置分析家族（`world/placementanalysis` 下的
 * AngleSnap / RotationFlick / Snap / SneakAndPlace 等 10 余项）
 * 依赖方块几何、视角与放置面的联合判断，属于下一个增量。
 * 本检测只覆盖其中"频率"这一维度。</p>
 */
@CheckData(
    name = "FastPlaceA",
    decay = 0.1,
    setback = 0.0,
    description = "放置方块频率异常（同 tick 多次放置 / 持续超高频率）"
)
class FastPlaceA(player: PlayerData) : Check(player), BlockPlaceListener, ServerTickListener {

    private val rates = RateTracker()

    private var placementsThisTick = 0

    @Volatile
    private var maxPerSecond: Int = DEFAULT_MAX_PER_SECOND

    override fun onBlockPlace(update: BlockPlaceUpdate) {
        placementsThisTick++
        rates.record()

        if (placementsThisTick > MAX_PLACEMENTS_PER_TICK) {
            flag(
                "同一 tick 内放置了 " + placementsThisTick +
                    " 个方块（原版一次右键只能放置 1 个）",
                IMPOSSIBLE_WEIGHT
            )
        }
    }

    override fun onServerTick() {
        rates.tick()
        placementsThisTick = 0

        if (maxPerSecond <= 0) {
            reward()
            return
        }

        val perSecond = rates.count()
        if (perSecond > maxPerSecond) {
            flag(
                "每秒放置 " + perSecond + " 个方块，超过上限 " + maxPerSecond +
                    "（单 tick 峰值 " + rates.peakPerBucket() + "）",
                LIMIT_WEIGHT
            )
        } else {
            reward()
        }
    }

    override fun reload() {
        super.reload()
        maxPerSecond = AntiCheatCore.configManager
            .optionInt(configName, "max-per-second", DEFAULT_MAX_PER_SECOND)
    }

    companion object {
        /**
         * 单 tick 内允许的放置次数。
         *
         * <p>理论上就是 1；给到 2 是为了容忍 tick 边界处
         * "包到达时间刚好跨过服务端 tick"的极端情况。</p>
         */
        const val MAX_PLACEMENTS_PER_TICK = 2

        /** 一秒内允许的放置次数。留了约 3 倍于原版冷却的余量。 */
        const val DEFAULT_MAX_PER_SECOND = 15

        /** 仅频率超限时的权重。 */
        const val LIMIT_WEIGHT = 1.0

        /** 同 tick 多次放置——原版不可能的强判据。 */
        const val IMPOSSIBLE_WEIGHT = 3.0
    }
}
