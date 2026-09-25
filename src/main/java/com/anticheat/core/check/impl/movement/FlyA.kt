package com.anticheat.core.check.impl.movement

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData

/**
 * 飞行（Fly / 悬停 / 滑翔式缓降）。
 *
 * <h3>判据</h3>
 * 见 [VerticalMotion]：原版空中玩家的垂直增量满足确定的递推
 * `expected = (previous - 0.08) * 0.98`。本检测逐 tick 比对实测增量与递推值，
 * **只在"下落得不够快"时累积证据**——悬停（增量恒为 0）与匀速上升（飞行模式常见的
 * 0.44 格/tick）都会持续命中，而跳跃、自由下落、被炸飞后回落都不会。
 *
 * <h3>为什么在主线程按 tick 判，而不是在收包时按位置包判</h3>
 * 悬停式飞行最省事的写法是**站着不动**：客户端此时只发「不带位置分量」的飞行包，
 * 位置回调根本不会被触发，按包判据会完全看不见它。
 * 服务端每 tick 读到的权威位置则不管客户端发的是哪种包都在那里，
 * 因此这里用 [ServerTickListener] + `serverPosition`。
 * 代价是必须自己处理"这一 tick 客户端没上报"的情况（见下）。
 *
 * <h3>四道让路，缺一个就是成批误报</h3>
 * 1. **[PlayerData.movementPhysicsExempt]**：载具 / 鞘翅 / **允许飞行** / 液体 /
 *    梯子蜘蛛网等改写运动的方块 / 缓降漂浮等改写移动的药水效果。
 *    其中"允许飞行"最关键——大厅服与建筑服普遍在生存模式下给玩家开 `/fly`，
 *    只看"悬在空中不下落"会把整服的人判成作弊；
 * 2. **传送免疫窗口**：传送后的位置差没有物理意义；
 * 3. **本 tick 客户端没上报任何移动包**（`positionPacketsThisTick <= 0`）：
 *    此时服务端位置不变是"没有新信息"，不是"玩家悬停"。
 *    漏了这条，任何一次丢包或掉帧都会被判成悬停；
 * 4. **腾空宽限期** [GRACE_TICKS]：离开地面的**第一拍**没有可比的上一个空中增量
 *    （上一拍是地面，增量为 0），拿它去套递推必然得出"该下落却没下落"。
 *    原版起跳的上升段是 6 tick（见 `VerticalMotion.apexTick(0.42)`），
 *    上升段本身是满足递推的，所以宽限期只需要盖住第一拍；取 4 是留余量。
 *
 * <h3>只累积、不单次判定</h3>
 * 沿用 [com.anticheat.core.check.impl.reach.ReachA] 的做法：每 tick 只计入
 * "比应有下落高出多少"，且单项封顶 [MAX_SHORTFALL_PER_TICK]。
 * 因为封顶值小于 [FLAG_BALANCE]，**任何一次孤立异常（爆炸、史莱姆弹射、激流冲刺、
 * 一次攒包补发）都不可能单独触发告警**，必须持续不满足重力才行。
 *
 * <h3>与 [GroundSpoofA] 的分工</h3>
 * 本检测的"是否在空中"取自服务端权威值，而 1.8 的服务端是直接采信客户端上报的
 * `onGround` 标志的。于是「NoFall 类作弊在坠落中谎称自己落地」会让本检测以为玩家
 * 站在地面上、整体让路——那一部分由 [GroundSpoofA] 用"声称落地却在加速下坠 +
 * 脚下没有支撑"接住。两个检测合起来才覆盖完整的飞行 / 免摔家族。
 */
@CheckData(
    name = "FlyA",
    decay = 0.05,
    setback = 0.0,
    description = "空中垂直运动不满足重力递推（悬停 / 匀速上升式飞行）"
)
class FlyA(player: PlayerData) : Check(player), ServerTickListener {

    /** 上一个 tick 的服务端 Y；[NO_POSITION] 表示还没有可比的上一个值。 */
    private var lastY = NO_POSITION

    /** 上一拍的**空中**垂直增量，递推的输入。 */
    private var lastAirDeltaY = 0.0

    /** 连续处于空中的 tick 数。落地即归零。 */
    private var airTicks = 0

    /** 证据累积器（单位：格/tick）。 */
    private var balance = 0.0

    @Volatile
    private var tolerance: Double = VerticalMotion.DEFAULT_TOLERANCE

    @Volatile
    private var graceTicks: Int = GRACE_TICKS

    override fun onServerTick() {
        val teleportImmunity =
            AntiCheatCore.tickManager.currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS

        if (player.movementPhysicsExempt || teleportImmunity) {
            reset()
            reward()
            return
        }

        // 客户端这一 tick 什么都没上报：服务端位置不变只是"没有新信息"，不是"悬停"。
        // 刻意放在读 lastY 之前 return —— 不推进 lastY，等下一次真实上报再比。
        if (player.positionPacketsThisTick <= 0) return

        val y = player.serverPosition.y
        val previous = lastY
        lastY = y
        // NaN 不能用 == 比较（IEEE 754 里 NaN != NaN），必须走 isNaN()
        if (previous.isNaN()) return
        val deltaY = y - previous

        if (player.serverOnGround) {
            airTicks = 0
            lastAirDeltaY = 0.0
            balance = 0.0
            reward()
            return
        }

        airTicks++
        if (airTicks <= graceTicks) {
            // 宽限期内只记录增量、不判定：第一拍没有可比的空中增量
            lastAirDeltaY = deltaY
            return
        }

        if (VerticalMotion.isFallingTooSlowly(lastAirDeltaY, deltaY, tolerance)) {
            val shortfall = VerticalMotion.shortfall(lastAirDeltaY, deltaY)
                .coerceAtMost(MAX_SHORTFALL_PER_TICK)
            balance += shortfall

            if (balance > FLAG_BALANCE) {
                // 只扣一半而不是清零：让"持续悬停"能连续告警，
                // 同时避免同一个累积量被反复用来触发多次
                balance -= FLAG_BALANCE * 0.5
                flag(
                    "空中 " + airTicks + " tick 不满足重力：实测 deltaY=" + format(deltaY) +
                        " 应为 " + format(VerticalMotion.expectedDelta(lastAirDeltaY)) +
                        "（上一拍 " + format(lastAirDeltaY) + "，累积 " + format(balance) + "）",
                    VIOLATION_WEIGHT
                )
            }
        } else {
            balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)
            reward()
        }

        lastAirDeltaY = deltaY
    }

    private fun reset() {
        lastY = NO_POSITION
        lastAirDeltaY = 0.0
        airTicks = 0
        balance = 0.0
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        tolerance = manager.optionDouble(configName, "tolerance", VerticalMotion.DEFAULT_TOLERANCE)
        graceTicks = manager.optionInt(configName, "grace-ticks", GRACE_TICKS)
    }

    private fun format(value: Double): String = String.format("%.4f", value)

    companion object {
        /**
         * "还没有上一个 Y" 的哨兵值。
         *
         * <p>用 NaN 而不是 0.0：y=0 是合法坐标（主世界基岩层附近），
         * 用它当哨兵会让"刚传送过来"与"正好在 y=0"两种情况混为一谈。
         * 代价是读取时必须用 `isNaN()` 判空——`NaN == NaN` 在 IEEE 754 里是 false。</p>
         */
        const val NO_POSITION = Double.NaN

        /**
         * 腾空宽限期（tick）。
         *
         * <p>理论上 1 就够（只有离开地面的第一拍缺可比增量），取 4 是为了吸收
         * "发包时刻与 tick 边界不对齐"导致的第一拍增量被切分。
         * 代价只是飞行作弊晚 150ms 被抓，方向是安全的。</p>
         */
        const val GRACE_TICKS = 4

        /**
         * 单 tick 最多计入的证据量（格/tick）。
         *
         * <p>必须**小于** [FLAG_BALANCE]，这是"单次异常不可能告警"这条性质的前提。
         * 悬停的特征值是 0.078、匀速上升约 0.087，都在封顶值以下不会被削减；
         * 爆炸 / 弹射 / 攒包造成的巨大偏离会被削平，于是一次性事件攒不够证据。</p>
         */
        const val MAX_SHORTFALL_PER_TICK = 0.3

        /** 累积到该值才告警（格/tick）。悬停需要连续约 13 tick（0.65 秒）。 */
        const val FLAG_BALANCE = 1.0

        /** 每个合规 tick 的降温量。约 1.0/秒，与 ReachA 一致。 */
        const val DECAY_PER_TICK = 0.05

        const val VIOLATION_WEIGHT = 1.0

        /** 传送后的免疫时长（tick）。与 BadPacketsA / TimerA 保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L
    }
}
