package com.anticheat.core.check.impl.reach

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.ViewAngle
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 视线未对准目标（射线检测）。
 *
 * <h3>判据</h3>
 * 玩家"攻击了一个完全不在视线里的实体"。原版要求准星落在目标命中盒上
 * （容差约 10 度以内），而这里用的阈值是 **75 度**——也就是说只有当目标
 * 明显在侧面甚至背后时才会计入。这样选阈值是刻意的：它抓的是
 * **silent aura / 无视线攻击**这类明确作弊，而不是"瞄得不够准"。
 *
 * <h3>容差从哪来</h3>
 * 服务端记录的朝向是"最近一次收到的朝向"，可能比攻击包早 1~2 tick；
 * 一次快速甩鼠标可以在一 tick 内转 30~50 度。所以这里对**最近 5 次朝向**
 * 与**最近 4 次目标位置**分别求夹角并取**最小**——只要其中任一组合看起来
 * 合理就放过。这一条把"快速转身瞬间命中"从误报里彻底排除。
 *
 * <h3>两道额外让路</h3>
 * - **目标太近不判**（< 1 格）：此时方向向量极短，夹角对位置噪声极其敏感，
 *   一个 0.05 格的偏差就能把夹角推到几十度；
 * - **距离超过 16 格不判**：数据严重分叉，据此判定没有意义。
 *
 * <h3>为什么权重与降温都按"度"来算</h3>
 * 夹角是连续量，用"度"做累积比用次数更贴合信号的强弱：打背后的每次攻击会
 * 累加约 100 度，十几秒的持续作弊就能顶到阈值；而偶发的一次大角度（比如被
 * 击退时鼠标乱甩）只会累加一次、很快被 [DECAY_PER_TICK] 吃掉。
 *
 * <p>参考实现：intave 的 `RotationModuloReset` / `RotationSnap` 系列会做
 * "视线与实体的几何关系"判断；Grim 的 `AimDuplicateLook` / `Aim` 系列也是同类。
 * 本实现只取其中最不易误判的一维（"完全不在视线里"），
 * 更细的瞄准质量判断留给后续增量。</p>
 */
@CheckData(
    name = "ReachB",
    decay = 0.05,
    setback = 0.0,
    description = "攻击了完全不在视线内的实体（无视线攻击 / silent aura）"
)
class ReachB(player: PlayerData) : Check(player), ServerTickListener {

    private val tracker: TargetTracker? by lazy { player.checkManager.get(TargetTracker::class.java) }

    private var balance = 0.0

    @Volatile
    private var maxAngle: Double = DEFAULT_MAX_ANGLE

    // 预分配缓冲，避免每次判定都分配数组（判定在主线程、每 tick 每目标一次）
    private val yawBuffer = FloatArray(TargetTracker.ROTATION_HISTORY)
    private val pitchBuffer = FloatArray(TargetTracker.ROTATION_HISTORY)
    private val targetXBuffer = DoubleArray(MAX_TARGET_SAMPLES)
    private val targetYBuffer = DoubleArray(MAX_TARGET_SAMPLES)
    private val targetZBuffer = DoubleArray(MAX_TARGET_SAMPLES)

    override fun onServerTick() {
        val tracker = this.tracker
        if (tracker == null) return
        tracker.ensureSampled()

        if (!tracker.canJudge()) {
            balance = 0.0
            reward()
            return
        }

        val eyes = tracker.eyes()
        val rotationCount = minOf(tracker.rotationCount(), yawBuffer.size)
        if (eyes.size == 0 || rotationCount == 0) {
            reward()
            return
        }

        // 用**最近一次**眼睛位置：攻击发生在约 1 tick 前，眼睛最多偏移 0.3 格，
        // 在 3 格距离上只等效几度，远小于 75 度的阈值；而朝向会变几十度，
        // 所以容差预算全部留给朝向（见类注释）。
        val eyeIndex = eyes.size - 1
        val eyeX = eyes.x(eyeIndex)
        val eyeY = eyes.y(eyeIndex)
        val eyeZ = eyes.z(eyeIndex)

        for (r in 0 until rotationCount) {
            yawBuffer[r] = tracker.rotationYaw(r)
            pitchBuffer[r] = tracker.rotationPitch(r)
        }

        var flagged = false

        for ((_, track) in tracker.tracks()) {
            if (!track.attackedThisTick) continue
            if (!track.history.isFull) continue

            val targetCount = minOf(track.history.size, MAX_TARGET_SAMPLES)
            // 取最近的 targetCount 个样本
            val from = track.history.size - targetCount
            for (i in 0 until targetCount) {
                targetXBuffer[i] = track.history.x(from + i)
                targetYBuffer[i] = track.history.y(from + i)
                targetZBuffer[i] = track.history.z(from + i)
            }

            // 太近或太远都不判（原因见类注释）
            val rawDistance = sqrt(
                sq(targetXBuffer[targetCount - 1] - eyeX) +
                    sq(targetYBuffer[targetCount - 1] - eyeY) +
                    sq(targetZBuffer[targetCount - 1] - eyeZ)
            )
            if (rawDistance < MIN_JUDGE_DISTANCE || rawDistance > MAX_SANE_DISTANCE) continue

            val angle = ViewAngle.minAngleOffBox(
                eyeX, eyeY, eyeZ,
                yawBuffer, pitchBuffer, rotationCount,
                targetXBuffer, targetYBuffer, targetZBuffer, targetCount,
                track.box.halfWidth, track.box.height
            )
            if (angle < 0.0) continue
            if (angle <= maxAngle) continue

            balance += (angle - maxAngle).coerceAtMost(MAX_EXCESS_PER_HIT)

            if (balance > FLAG_BALANCE) {
                balance -= FLAG_BALANCE * 0.5
                flagged = true
                flag(
                    "攻击时视线与目标偏离 " + format(angle) + " 度（上限 " + format(maxAngle) +
                        "）目标=" + track.typeName + " 距离=" + format(rawDistance) +
                        " ping=" + player.ping + "ms",
                    VIOLATION_WEIGHT
                )
            }
        }

        if (!flagged) {
            balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)
            reward()
        }
    }

    override fun reload() {
        super.reload()
        maxAngle = AntiCheatCore.configManager.optionDouble(configName, "max-angle", DEFAULT_MAX_ANGLE)
    }

    private fun sq(value: Double): Double = value * value

    private fun format(value: Double): String = String.format("%.1f", value)

    companion object {
        /**
         * 允许的最大视线偏角（度）。
         *
         * <p>原版要求准星落在命中盒上（约 10 度内）。这里给到 75 度是**刻意的**：
         * 阈值越靠近原版值，越容易被"快速转身 + 服务端朝向滞后"打成误报，
         * 而 75 度仍然能抓住"打背后"这类明确的 silent aura。
         * 想更严可以在观察期之后逐步下调，但请一次只降 5~10 度。</p>
         */
        const val DEFAULT_MAX_ANGLE = 75.0

        /** 目标比该距离更近时不判（方向噪声过大）。 */
        const val MIN_JUDGE_DISTANCE = 1.0

        const val MAX_SANE_DISTANCE = 16.0

        /** 每个目标最多参与计算的位置样本数。 */
        const val MAX_TARGET_SAMPLES = 4

        /** 单次最多计入的证据量（度）。 */
        const val MAX_EXCESS_PER_HIT = 20.0

        /** 累积到该值才告警（度）。 */
        const val FLAG_BALANCE = 120.0

        /** 每 tick 的降温量（度）。约 40 度/秒。 */
        const val DECAY_PER_TICK = 2.0

        const val VIOLATION_WEIGHT = 1.0
    }
}
