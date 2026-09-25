package com.anticheat.core.check.impl.aim

/**
 * 瞬转（rotation snap）指纹判定 —— 纯逻辑、可离线单测。
 *
 * <h3>它抓的是什么</h3>
 * 外挂把准星"瞬移"到目标上时，服务端看到的是一个**三拍**的模式：
 *
 * ```
 * 第 N-1 拍：玩家没动鼠标        （yawMotion ≈ 0）
 * 第 N   拍：视角突然跳了几十度  （yawMotion 很大）
 * 第 N+1 拍：玩家又没动鼠标      （yawMotion ≈ 0）
 * ```
 *
 * 关键是**前后两拍都静止**。真人快速甩鼠标（flick）虽然也能一拍转 90 度以上，
 * 但手腕的运动会被分成连续数拍的采样（加速—匀速—减速），
 * 因此"前一拍 < 9 度且后一拍 < 9 度"这个组合在真人身上极难出现；
 * 而瞬转外挂是直接改写朝向，服务端只看到孤立的一跳。
 *
 * <h3>为什么不能只看"角度大"</h3>
 * 单看"某拍转了很多度"会把所有 flick 型玩家判成作弊——那是 PVP 的基本功。
 * 瞬时转向本身不是违规，**"没有中间帧"才是**。
 *
 * <h3>第二重变体（[STRONG_SNAP_DEGREES]）</h3>
 * 前后两拍**完全**没有任何变化（差值为 0）时，把中间那拍的门槛从 40 度放宽到 25 度。
 * 理由：低帧率客户端与网络攒包会让朝向采样变稀疏，此时一次中等幅度的瞬转
 * 前后都可能采样到"完全未变化"。放宽下界能让这类情况下仍可检出，
 * 代价只是需要 [SNAP_DEGREES] 之外的证据量来平衡（见 [weight]）。
 *
 * <p>参考 intave `check/combat/heuristics/combatpatterns/rotation/RotationSnapHeuristic`
 * 的 `isRotationSnapDetected`（`prev &lt; 9 &amp;&amp; last &gt; 40 &amp;&amp; current &lt; 9`，
 * 并要求邻近有挥臂/攻击）。本类只承载**纯判定**，让路条件与状态由
 * [AimC] 负责，这样判据本身能离线测。</p>
 */
object RotationSnap {

    /** 一拍转动小于该值视为"鼠标没动"（度）。 */
    const val QUIET_DEGREES = 9.0

    /** 三拍指纹里中间那一拍的判定下界（度）。 */
    const val SNAP_DEGREES = 40.0

    /** 前后两拍**完全**静止时，中间那一拍放宽后的下界（度）。 */
    const val STRONG_SNAP_DEGREES = 25.0

    /** "完全静止"的容差。yaw 是 Float，差值本身可以精确为 0，这里留一点浮点余量。 */
    private const val ZERO_EPSILON = 1.0e-3

    /**
     * 三拍指纹是否成立。
     *
     * @param previousYawMotion 上一拍的 |Δyaw|（度）
     * @param middleYawMotion 本拍的 |Δyaw|（度）
     * @param currentYawMotion 下一拍的 |Δyaw|（度）
     */
    @JvmStatic
    fun isSnap(
        previousYawMotion: Double,
        middleYawMotion: Double,
        currentYawMotion: Double
    ): Boolean {
        // 前后两拍都必须在"没转"的状态，否则这是连续甩鼠标，不是瞬转
        if (previousYawMotion >= QUIET_DEGREES || currentYawMotion >= QUIET_DEGREES) return false

        if (middleYawMotion > SNAP_DEGREES) return true

        return previousYawMotion <= ZERO_EPSILON && middleYawMotion > STRONG_SNAP_DEGREES
    }

    /**
     * 单次瞬转的证据量。
     *
     * <p>分级依据是**人类手腕在 50ms 内能完成多少度**，而不是参考实现的原始数值：
     * 参考实现在未归一化的角度差上分档（因此有 >360 一档），
     * 这里用的是归一化约定（|Δyaw| ∈ [0, 180]），所以档位边界整体下移。</p>
     *
     * <p>角度越大越"不可能是人手"，权重按档位跃升；基础档 7 分意味着
     * 弱证据需要累积多次才能告警（见 [AimC.FLAG_BALANCE]），
     * 而一次 180 度的单帧转向几乎立刻触发。</p>
     */
    @JvmStatic
    fun weight(snapDegrees: Double): Double = when {
        snapDegrees > IMPOSSIBLE_DEGREES -> 50.0
        snapDegrees > HUGE_DEGREES -> 20.0
        snapDegrees > BIG_DEGREES -> 10.0
        else -> BASE_WEIGHT
    }

    /** 单帧转了半圈以上：人类手腕在这个时间尺度内做不到。 */
    const val IMPOSSIBLE_DEGREES = 178.0

    /** 单帧转 90 度以上。 */
    const val HUGE_DEGREES = 90.0

    /** 单帧转 50 度以上，明显超过正常跟枪幅度。 */
    const val BIG_DEGREES = 50.0

    /** 基础证据量（恰好越过 [SNAP_DEGREES] 的那一档）。 */
    const val BASE_WEIGHT = 7.0
}
