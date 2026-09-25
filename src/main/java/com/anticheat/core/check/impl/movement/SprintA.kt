package com.anticheat.core.check.impl.movement

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.CoreMath
import kotlin.math.sqrt

/**
 * 疾跑方向矛盾（全向疾跑 / KeepSprint）。
 *
 * <h3>判据</h3>
 * 原版的疾跑**只能由前进输入触发和维持**：客户端在玩家松开前进键的那一刻就发出
 * `STOP_SPRINTING`。因此"正在疾跑"与"水平位移方向偏离朝向超过 45 度"这两件事
 * 不可能同时成立——45 度正是 `W + A` / `W + D` 斜向输入的夹角上限。
 * 默认阈值给到 75 度，也就是在斜向输入的极限之外再留 30 度余量。
 *
 * <p>参考实现的 `Sprint` 模块提供"全向疾跑"（允许朝任意方向保持疾跑状态），
 * 其默认行为就是让位移方向与朝向彻底脱钩：向后跑时夹角接近 180 度，
 * 纯侧移时接近 90 度，两者都远在本判据之外。</p>
 *
 * <h3>为什么在主线程按 tick 采样，而不是在位置回调里当场判</h3>
 * 两个原因，缺一不可：
 *
 * 1. **位移与朝向必须来自同一时刻**。1.9+ 的合并包在包层的派发顺序是
 *    **先位置、后朝向**（见 [com.anticheat.core.events.packets.PacketPlayerUpdate]），
 *    若在位置回调里当场判定，读到的 `player.yaw` 还是上一拍的——
 *    快速转身的那一拍，位移与朝向来自不同的客户端 tick，
 *    夹角会出现一个**纯由延迟造成的尖峰**。在主线程同一瞬间读
 *    `player.position` 与 `player.yaw`，两者天然对齐；
 * 2. **不能在两个线程之间共享可变累加器**。位置回调在 Netty 线程、tick 回调在主线程，
 *    若把位移在 Netty 侧累加、到主线程再读取并清零，就是无同步的跨线程读改写
 *    （可见性与"读到一半被追加"两个问题都在）。
 *    改成"主线程每 tick 采一次样、与上一拍的采样作差"之后，
 *    本检测的全部可变状态都只在主线程出现，竞态从结构上消失。
 *
 * <p>采样式差分的附带好处：客户端一 tick 内补发多个位置包（攒包 / blink）时，
 * 差分得到的自然是这一 tick 的**总位移**，不需要额外合并逻辑。</p>
 *
 * <h3>五道让路</h3>
 * 1. **[PlayerData.movementPhysicsExempt]**：载具 / 鞘翅 / 允许飞行 / 液体 /
 *    梯子蜘蛛网 / 药水效果。骑乘时位移由载具驱动，与疾跑状态无关；
 * 2. **传送免疫窗口**；
 * 3. **外力窗口**（[PlayerData.lastExternalVelocityTick]）：击退会把玩家朝
 *    **远离攻击者**的方向推，而 PvP 里攻击者通常在正前方——于是被击退的玩家
 *    看起来正在"倒着疾跑"。这是本判据最主要的假阳性来源，必须让路；
 * 4. **位移量过大**（[MAX_PLAYER_DRIVEN_SPEED]）：玩家自己走不出这个速度，
 *    出现即说明是外力（爆炸、弹射、载具碰撞、传送）。
 *    冰道上的高速滑行也走这一条——它虽然合法，但那时玩家是朝前跑的，本来也不会命中判据；
 * 5. **不在地面**：击退会把人打上天，而疾跑标志在落地前不会清除。
 *
 * <h3>余额法</h3>
 * 疾跑状态的开始 / 结束与位移之间有**一拍的状态错位**（松开前进键的那一拍，
 * 客户端可能先发位移、后发 `STOP_SPRINTING`），转向时也会短暂出现大夹角。
 * 这些都是单 tick 的孤立事件，用余额累积把它们吸收掉：
 * 连续 [FLAG_BALANCE] tick 的矛盾才告警，任何一个合规 tick 都扣 [DECAY_PER_TICK]。
 */
@CheckData(
    name = "SprintA",
    decay = 0.05,
    setback = 0.0,
    description = "疾跑状态下水平位移方向与朝向严重偏离（全向疾跑 / KeepSprint）"
)
class SprintA(player: PlayerData) : Check(player), ServerTickListener {

    /** 上一拍的采样位置；NaN 表示还没有可比的上一个值。只在主线程读写。 */
    private var lastX = Double.NaN

    private var lastZ = Double.NaN

    /** 证据累积器（tick 计）。只在主线程读写。 */
    private var balance = 0.0

    @Volatile
    private var maxAngle: Double = DEFAULT_MAX_ANGLE

    override fun onServerTick() {
        // 客户端上报的位置与朝向：同一瞬间读出来，两者天然对齐
        val x = player.position.x
        val z = player.position.z
        val previousX = lastX
        val previousZ = lastZ
        lastX = x
        lastZ = z

        // NaN 不能用 == 比较（IEEE 754 里 NaN != NaN），必须走 isNaN()
        if (previousX.isNaN()) return

        judge(x - previousX, z - previousZ, player.yaw)
    }

    private fun judge(deltaX: Double, deltaZ: Double, yaw: Float) {
        val currentTick = AntiCheatCore.tickManager.currentTick
        if (player.movementPhysicsExempt ||
            currentTick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS ||
            currentTick - player.lastExternalVelocityTick < VELOCITY_IMMUNITY_TICKS
        ) {
            balance = 0.0
            reward()
            return
        }

        if (!player.sprinting) {
            coolDown()
            return
        }

        val speed = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        // 位移太小时方向由浮点噪声主导，夹角没有意义；
        // 过大则说明是外力（玩家自己走不出这个速度）
        if (speed < MIN_JUDGED_SPEED || speed > MAX_PLAYER_DRIVEN_SPEED) {
            coolDown()
            return
        }

        // 空中让路：击退会把人打上天，而疾跑标志在落地前不会清除
        if (!player.serverOnGround) {
            coolDown()
            return
        }

        val angle = CoreMath.angleOffViewDegrees(yaw, 0.0f, deltaX, 0.0, deltaZ)
        if (angle <= maxAngle) {
            coolDown()
            return
        }

        balance += 1.0
        if (balance > FLAG_BALANCE) {
            balance -= FLAG_BALANCE * 0.5
            flag(
                "疾跑中位移方向偏离朝向 " + String.format("%.1f", angle) + " 度（上限 " +
                    String.format("%.1f", maxAngle) + "，斜向输入的极限是 45 度）" +
                    " 速度=" + String.format("%.3f", speed) + " 格/tick",
                VIOLATION_WEIGHT
            )
        }
    }

    private fun coolDown() {
        if (balance > 0.0) balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)
        reward()
    }

    override fun reload() {
        super.reload()
        maxAngle = AntiCheatCore.configManager.optionDouble(configName, "max-angle", DEFAULT_MAX_ANGLE)
    }

    companion object {
        /**
         * 允许的"位移方向 vs 朝向"夹角（度）。
         *
         * <p>原版斜向输入（W+A / W+D）的极限是 45 度，默认给到 75 度即再留 30 度余量，
         * 用来吸收"转向与位移之间的一拍错位"。全向疾跑向后跑时夹角接近 180 度，
         * 余量再大也盖不住它。</p>
         *
         * <p>想更严可以下调，但**每次只降 5~10 度**：这个判据的假阳性全部来自
         * 转身瞬间，降得太狠会让 PvP 玩家频繁告警。</p>
         */
        const val DEFAULT_MAX_ANGLE = 75.0

        /** 低于该速度不判：位移太小时方向由浮点噪声主导，夹角没有意义。 */
        const val MIN_JUDGED_SPEED = 0.05

        /**
         * 高于该速度视为外力，不判。
         *
         * <p>取 0.9：远超疾跑跳跃的约 0.42，也高于蓝冰高速滑行的常见区间。
         * 这一条是**故意宽松**的——它的失败模式是漏判，而卡紧了会把爆炸击退判成作弊。</p>
         */
        const val MAX_PLAYER_DRIVEN_SPEED = 0.9

        /** 连续多少 tick 的矛盾才告警。 */
        const val FLAG_BALANCE = 10.0

        /** 每个合规 tick 的降温量。约 10/秒，一次击退的影响最多残留半秒。 */
        const val DECAY_PER_TICK = 0.5

        const val VIOLATION_WEIGHT = 1.0

        /** 传送后的免疫时长（tick）。与其它位移类检测保持一致。 */
        const val TELEPORT_IMMUNITY_TICKS = 40L

        /**
         * 击退 / 爆炸后的免疫时长（tick）。
         *
         * <p>取 15（0.75 秒）：原版的击退速度按每 tick 约 0.91 衰减，
         * 15 tick 后残余已低于本判据的最小速度门槛，不会再影响方向。</p>
         */
        const val VELOCITY_IMMUNITY_TICKS = 15L
    }
}
