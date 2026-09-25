package com.anticheat.core.check.impl.movement

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData

/**
 * 谎称站在地面上（NoFall 免摔 / 飞行落地伪装）。
 *
 * <h3>判据：三件事同时成立</h3>
 * 1. 客户端**声称在地面**（`onGround = true`，服务端权威值直接采信它）；
 * 2. 而这一 tick 的 Y **下降超过 [MAX_GROUNDED_DESCENT]**。原版能"踩着地面下降"的
 *    唯一途径是走下台阶 / 半砖，单 tick 最多约 0.6 格（等于原版的自动上台阶高度）；
 *    再高就必须腾空，而腾空时客户端会老实地把 `onGround` 置为 false；
 * 3. 且**脚下确实没有可以踩的东西**（[com.anticheat.core.platform.api.player.PlatformPlayer.hasGroundSupport]）。
 *
 * <h3>为什么第 3 条不能省</h3>
 * 只靠前两条会把两种完全合法的场景判成作弊：
 * - **下降的活塞 / 飞行机器**：玩家真的站在方块上，`onGround = true` 是对的，
 *   而机器可以带着他以任意速度下降；
 * - **服务端把玩家往下"挤"**：例如方块被放置进玩家身体时原版会向下推挤。
 *
 * 这两种情况的共同点是**脚下有固体方块**。反过来，NoFall 类作弊是在自由落体中
 * 谎称落地（参考实现的默认模式 `SpoofGround` 就是在坠落距离超过安全值后
 * 把移动包里的 `onGround` 改写成 true），它脚下是空的。一条方块探测把两者彻底分开，
 * 而且只在"已经满足前两条"时才执行——绝大多数 tick 根本不会走到这一步。
 *
 * <h3>为什么需要它（[FlyA] 覆盖不到这一段）</h3>
 * [FlyA] 的"是否在空中"取自服务端权威值，而 1.8 的服务端直接采信客户端上报的
 * `onGround`。于是谎称落地的作弊者会让 FlyA 以为他站在地面上而整体让路。
 * 本检测正好接住这一块：它不要求玩家真的在空中，只要求"声称落地"与"正在下坠"矛盾。
 *
 * <h3>让路</h3>
 * [PlayerData.movementPhysicsExempt]（载具 / 鞘翅 / 允许飞行 / 液体 / 梯子蜘蛛网 /
 * 药水效果）与传送免疫窗口。液体那一项尤其重要：在水里下沉是合法的，
 * 而客户端在水中确实可能上报 `onGround = true`（水的垂直碰撞标志）。
 */
@CheckData(
    name = "GroundSpoofA",
    decay = 0.05,
    setback = 0.0,
    description = "声称站在地面上却在快速下坠，且脚下没有支撑（NoFall 免摔）"
)
class GroundSpoofA(player: PlayerData) : Check(player), ServerTickListener {

    /** 上一个 tick 的服务端 Y；NaN 表示还没有可比的上一个值。 */
    private var lastY = Double.NaN

    /** 连续命中的 tick 数，只用于告警文案。 */
    private var streak = 0

    /** 证据累积器（tick 数计）。 */
    private var balance = 0.0

    @Volatile
    private var maxGroundedDescent: Double = MAX_GROUNDED_DESCENT

    @Volatile
    private var supportProbeDepth: Double = SUPPORT_PROBE_DEPTH

    override fun onServerTick() {
        val teleportImmunity =
            AntiCheatCore.tickManager.currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS

        if (player.movementPhysicsExempt || teleportImmunity || !player.serverOnGround) {
            reset()
            reward()
            return
        }

        // 客户端这一 tick 什么都没上报：位置不变是"没有新信息"，不是"没有下坠"
        if (player.positionPacketsThisTick <= 0) return

        val y = player.serverPosition.y
        val previous = lastY
        lastY = y
        // NaN 不能用 == 比较，必须走 isNaN()
        if (previous.isNaN()) return
        val deltaY = y - previous

        // 走下台阶 / 半砖是合法的"踩地下降"，原版自动上台阶高度是 0.6，这里给到 0.7
        if (deltaY >= -maxGroundedDescent) {
            coolDown()
            return
        }

        // 下降得快、又声称在地面：只有脚下真有东西可踩才算合法（活塞 / 飞行机器 / 挤压）
        if (player.platformPlayer.hasGroundSupport(supportProbeDepth)) {
            coolDown()
            return
        }

        streak++
        balance += 1.0
        if (balance > FLAG_BALANCE) {
            balance -= FLAG_BALANCE * 0.5
            flag(
                "声称在地面却在 " + streak + " tick 内持续下坠：deltaY=" + format(deltaY) +
                    "（踩地下降上限 -" + format(maxGroundedDescent) + "），脚下 " +
                    format(supportProbeDepth) + " 格内无固体方块",
                VIOLATION_WEIGHT
            )
        }
    }

    private fun coolDown() {
        streak = 0
        if (balance > 0.0) balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)
        reward()
    }

    private fun reset() {
        lastY = Double.NaN
        streak = 0
        balance = 0.0
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        maxGroundedDescent = manager.optionDouble(configName, "max-grounded-descent", MAX_GROUNDED_DESCENT)
        supportProbeDepth = manager.optionDouble(configName, "support-probe-depth", SUPPORT_PROBE_DEPTH)
    }

    private fun format(value: Double): String = String.format("%.3f", value)

    companion object {
        /**
         * 单 tick 内"踩在地面上还能下降"的最大幅度（格）。
         *
         * <p>原版的自动上台阶高度是 0.6，因此走下台阶 / 半砖的单 tick 下降也在这个量级。
         * 取 0.7 留一点余量：这个值只需要**明显小于**自由落体第二拍的 0.15→0.23→0.31…
         * 就够了，卡得太紧没有任何额外收益，只会把台阶几何的版本差异变成误报。</p>
         */
        const val MAX_GROUNDED_DESCENT = 0.7

        /**
         * 支撑探测的向下深度（格）。
         *
         * <p>必须覆盖"玩家站在方块顶面上"的全部合法情形（最多差 1 格），
         * 又不能大到"正在下坠的人离地面还有两三格也算有支撑"——
         * 那会让免摔作弊在落地前的一整段都被放过。1.5 是这两者的折中。</p>
         */
        const val SUPPORT_PROBE_DEPTH = 1.5

        /** 累积到该值才告警：需要连续 5 tick 的矛盾状态，孤立的一次测量抖动攒不够。 */
        const val FLAG_BALANCE = 5.0

        /** 每个合规 tick 的降温量。 */
        const val DECAY_PER_TICK = 0.5

        const val VIOLATION_WEIGHT = 1.0

        /** 传送后的免疫时长（tick）。与 FlyA / BadPacketsA 保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L
    }
}
