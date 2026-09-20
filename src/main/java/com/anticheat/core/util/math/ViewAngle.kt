package com.anticheat.core.util.math

import kotlin.math.max

/**
 * 视线夹角计算，纯逻辑、可离线单测。
 *
 * <p>它是"射线类"检测的核心：玩家的朝向是否**真的**指向被攻击的目标。
 * 与伸手距离不同，这个判据要对抗的不是距离误差而是**朝向的量化与时序误差**
 * ——服务端记录的 yaw/pitch 是最近一次收到的朝向，可能比攻击包早 1~2 tick，
 * 而一次快速甩鼠标可以在一个 tick 里转 30~50 度。因此这里同样采用
 * "取最近若干次朝向与最近若干次目标位置、再取**最小**夹角"的做法，
 * 把容差做到最大。</p>
 *
 * <p><b>为什么朝盒子的两个候选点算：</b>瞄准点不唯一。</p>
 * - **盒心**：玩家通常瞄胸口/头部附近；
 * - **盒子上离眼睛最近的点**：贴着目标时的实际朝向；
 *
 * <p>只算盒心会让"打脚下"的合法攻击被算成大角度（一个 1.8 格高的目标在
 * 3 格外，盒心与脚底的夹角就有约 17 度）。两个候选都算、取更小的那个，
 * 才不会有这种系统性偏差。</p>
 */
object ViewAngle {

    /**
     * 在 [rotationCount] 次朝向与 [targetCount] 次目标位置之间取最小夹角。
     *
     * @param yaws 朝向 yaw 序列（`FloatArray`，前 [rotationCount] 个有效）
     * @param pitches 朝向 pitch 序列，与 [yaws] 一一对应
     * @param targetX 目标脚底中心 X 序列（前 [targetCount] 个有效）
     * @return 最小夹角（度，0~180）；任一侧样本数为 0 时返回 [UNDECIDABLE_ANGLE]
     *   （一个**负数哨兵**，调用方应据此跳过，**不要**把它当成违规）
     */
    @JvmStatic
    fun minAngleOffBox(
        eyeX: Double, eyeY: Double, eyeZ: Double,
        yaws: FloatArray, pitches: FloatArray, rotationCount: Int,
        targetX: DoubleArray, targetY: DoubleArray, targetZ: DoubleArray, targetCount: Int,
        halfWidth: Double, height: Double
    ): Double {
        if (rotationCount <= 0 || targetCount <= 0) return UNDECIDABLE_ANGLE

        var best = MAX_ANGLE
        for (r in 0 until rotationCount) {
            val yaw = yaws[r]
            val pitch = pitches[r]
            for (t in 0 until targetCount) {
                val cx = targetX[t]
                val cy = targetY[t]
                val cz = targetZ[t]

                // 候选一：盒心
                val centerAngle = CoreMath.angleOffViewDegrees(
                    yaw, pitch, cx - eyeX, (cy + height * 0.5) - eyeY, cz - eyeZ
                )
                if (centerAngle < best) best = centerAngle

                // 候选二：盒子上离眼睛最近的点
                val qx = CoreMath.clamp(eyeX, cx - halfWidth, cx + halfWidth)
                val qy = CoreMath.clamp(eyeY, cy, cy + height)
                val qz = CoreMath.clamp(eyeZ, cz - halfWidth, cz + halfWidth)
                val closestAngle = CoreMath.angleOffViewDegrees(yaw, pitch, qx - eyeX, qy - eyeY, qz - eyeZ)
                if (closestAngle < best) best = closestAngle

                if (best <= 0.0) return 0.0
            }
        }
        return max(best, 0.0)
    }

    /** 夹角的上界（正后方）。 */
    const val MAX_ANGLE = 180.0

    /**
     * 样本不足时的返回值。
     *
     * <p>用一个**负数**而不是 180：180 度是"目标在正后方"的真实取值，
     * 拿它当哨兵会让"打背后"这种最该被抓的行为被当成"无法判定"跳过。</p>
     */
    const val UNDECIDABLE_ANGLE = -1.0
}
