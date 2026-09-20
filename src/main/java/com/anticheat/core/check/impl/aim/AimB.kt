package com.anticheat.core.check.impl.aim

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.impl.reach.TargetTracker
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.CoreMath
import com.anticheat.core.util.math.PointHistory
import com.anticheat.core.util.math.RayBox

/**
 * 瞄准质量：**对上移动目标长期百发百中**。
 *
 * <h3>与 [AimA] 的区别</h3>
 * `AimA` 看的是"朝向增量过于均匀"（机械感），属于过程特征；本检测看的是
 * **结果**——准星是否真的落在命中盒上。结果特征更难伪造：aimbot 可以故意加噪声
 * 打乱增量分布，但它仍然要求"每一刀都命中"。
 *
 * <h3>判据（三个条件必须同时成立）</h3>
 * 1. 窗口内攻击次数 ≥ `min-attacks`（默认 60）；
 * 2. **目标确实在移动**：窗口内目标累计位移 ≥ `min-target-travel`（默认 25 格）。
 *    这一条是必需的——打挂机/卡住的对手天然 100% 命中，那是完全正常的行为，
 *    没有这条门槛就是在测"对手是否站着不动"；
 * 3. 命中率 ≥ `min-hit-rate`（默认 1.0）。
 *
 * <h3>为什么"命中"用射线求交而不是夹角阈值</h3>
 * 夹角与"是否命中"不是同一个量：同样 3 度的偏差，在 0.5 格处仍在盒内、
 * 6 格处早已飞出盒外。用 [RayBox] 直接算"准星射线是否与命中盒相交"才是正确判据。
 *
 * <h3>三处刻意保守（宁可漏判）</h3>
 * - 判定只用**当前**朝向与**最新**目标位置（不取历史里的最有利组合）。
 *   服务端记录的朝向可能比攻击包晚或早一 tick，快速甩枪时会因此被判成"没命中"
 *   → 命中率被**低估**。低估只会漏判，不会误报；
 * - 贴脸（距离 < [MIN_SAMPLE_DISTANCE]）与超远（> [MAX_SAMPLE_DISTANCE]）的
 *   攻击不进窗口：前者方向向量太短、后者数据可能已分叉；
 * - 换目标的那一步不计入位移（避免把两个实体之间的空间距离当成"目标走了多远"，
 *   那会让位移虚高、门槛虚过）。
 *
 * <h3>为什么标成 experimental</h3>
 * 真实玩家的命中率分布取决于手感、延迟与对手水平，**这个分布无法离线建模**——
 * 仿真能证明的只有"这道题的数学是对的"，证明不了"门槛定在 1.0 不会打到高手"。
 * 因此本检测默认不参与判定（需 `core.experimental-checks: true`）。
 * 开启后请先只看告警、看一到两周的实测命中率分布，再决定是否收紧或放松。
 */
@CheckData(
    name = "AimB",
    decay = 0.05,
    setback = 0.0,
    description = "对上移动目标长期百发百中（瞄准质量异常，实验性）",
    experimental = true
)
class AimB(player: PlayerData) : Check(player), ServerTickListener {

    private val tracker: TargetTracker? by lazy { player.checkManager.get(TargetTracker::class.java) }

    private val window = HitRateWindow(HitRateWindow.DEFAULT_CAPACITY)

    @Volatile
    private var minAttacks: Int = DEFAULT_MIN_ATTACKS

    @Volatile
    private var minHitRate: Double = DEFAULT_MIN_HIT_RATE

    @Volatile
    private var minTargetTravel: Double = DEFAULT_MIN_TARGET_TRAVEL

    override fun onServerTick() {
        val tracker = this.tracker
        if (tracker == null) return
        tracker.ensureSampled()

        if (!tracker.canJudge()) {
            // 让路期间的数据不该填进窗口：否则会用一堆无意义的样本去凑门槛
            window.reset()
            reward()
            return
        }

        val eyes = tracker.eyes()
        if (eyes.size == 0) {
            reward()
            return
        }

        for ((id, track) in tracker.tracks()) {
            if (!track.attackedThisTick) continue
            if (!track.history.isFull) continue

            val index = track.history.size - 1
            val targetX = track.history.x(index)
            val targetY = track.history.y(index)
            val targetZ = track.history.z(index)

            val distance = eyes.minDistanceToBox(targetX, targetY, targetZ, track.box.halfWidth, track.box.height)
            if (distance < MIN_SAMPLE_DISTANCE || distance > MAX_SAMPLE_DISTANCE) continue

            val hit = crosshairOnBox(eyes, track.box.halfWidth, track.box.height, targetX, targetY, targetZ)
            window.onAttack(hit, id, targetX, targetY, targetZ)
        }

        evaluate()
    }

    /**
     * 准星是否落在命中盒上：从**当前**眼睛位置沿**当前**朝向发射线。
     *
     * <p>刻意不做"最近几次朝向里任一命中就算命中"的宽容处理：那会让"擦边快甩"
     * 也算命中，把命中率虚高——正是本检测要抓的东西。低估命中率是安全方向。</p>
     */
    private fun crosshairOnBox(
        eyes: PointHistory,
        halfWidth: Double,
        height: Double,
        targetX: Double,
        targetY: Double,
        targetZ: Double
    ): Boolean {
        val index = eyes.size - 1
        val eyeX = eyes.x(index)
        val eyeY = eyes.y(index)
        val eyeZ = eyes.z(index)
        val yaw = player.yaw
        val pitch = player.pitch
        return RayBox.hits(
            eyeX, eyeY, eyeZ,
            CoreMath.lookX(yaw, pitch), CoreMath.lookY(yaw, pitch), CoreMath.lookZ(yaw, pitch),
            targetX, targetY, targetZ,
            halfWidth, height
        )
    }

    private fun evaluate() {
        if (window.attacks() < minAttacks) {
            reward()
            return
        }
        if (window.travelled() < minTargetTravel) {
            // 目标没怎么动，百发百中不构成证据
            reward()
            return
        }
        if (window.hitRate() < minHitRate) {
            reward()
            return
        }

        val flagged = flag(
            "最近 " + window.attacks() + " 次攻击命中 " + window.hits() + " 次（命中率 " +
                format(window.hitRate() * 100.0) + "% ≥ 门槛 " + format(minHitRate * 100.0) +
                "%），期间目标累计移动 " + format(window.travelled()) + " 格" +
                "（人均 " + format(window.travelPerAttack()) + " 格/刀） ping=" + player.ping + "ms",
            VIOLATION_WEIGHT
        )
        if (flagged) {
            // 清空窗口：下一次告警必须重新攒满一整窗证据，
            // 避免同一批样本被反复用来告警
            window.reset()
        }
    }

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        minAttacks = manager.optionInt(configName, "min-attacks", DEFAULT_MIN_ATTACKS)
        minHitRate = manager.optionDouble(configName, "min-hit-rate", DEFAULT_MIN_HIT_RATE)
        minTargetTravel = manager.optionDouble(configName, "min-target-travel", DEFAULT_MIN_TARGET_TRAVEL)
        window.reset()
    }

    private fun format(value: Double): String = String.format("%.2f", value)

    companion object {
        /**
         * 触发判定所需的最小攻击次数。
         *
         * <p>60 是一个权衡：太少（比如 20）会把"一次交手的顺风局"当成证据；
         * 太多则要等很久。60 刀约等于一次完整 PvP 交手的时长。</p>
         */
        const val DEFAULT_MIN_ATTACKS = 60

        /** 命中率门槛：默认 100%。低于它就变成"测高手手感"，不是测作弊。 */
        const val DEFAULT_MIN_HIT_RATE = 1.0

        /**
         * 窗口内目标累计位移门槛（格）。
         *
         * <p>25 格 ≈ 冲刺 4 秒的位移。低于它说明对手基本是站桩/被卡住，
         * 此时 100% 命中毫无判别力。</p>
         */
        const val DEFAULT_MIN_TARGET_TRAVEL = 25.0

        /** 比该距离更近的攻击不进窗口：方向向量太短，命中与否几乎没有信息量。 */
        const val MIN_SAMPLE_DISTANCE = 1.0

        /**
         * 比该距离更远的攻击不进窗口。
         *
         * <p>6 格仍在合法 reach 的讨论范围之外（原版 3.45 之内），
         * 出现这种数说明数据已分叉——那是 ReachA 的事，不是本检测的事。</p>
         */
        const val MAX_SAMPLE_DISTANCE = 6.0

        /** 单次违规的权重：一整窗（几十刀）的完美命中，证据强度高于单次协议异常。 */
        const val VIOLATION_WEIGHT = 2.0
    }
}
