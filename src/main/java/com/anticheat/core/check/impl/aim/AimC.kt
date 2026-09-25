package com.anticheat.core.check.impl.aim

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.CoreMath
import kotlin.math.abs

/**
 * 瞬转瞄准 —— 单帧把准星"瞬移"到目标（snap aim）。
 *
 * <h3>它补的是 [AimA] 的盲区</h3>
 * [AimA] 统计的是「|Δyaw| 的标准差」，抓的是**平滑瞄准**：把准星按固定角速度
 * 喂到目标上，于是每拍增量几乎相同、标准差趋近 0。
 *
 * <p>而"暴力瞬转"走的是相反的极端：大部分拍**根本不动**，偶尔一拍直接跳几十到
 * 一百多度。这种模式的增量标准差**极大**，恰好从 [AimA] 的判据下顺利通过——
 * 也就是说两类瞄准辅助在同一个统计量上互为反面，只做一个必然漏另一个。
 * 本检测用 [RotationSnap] 的"静止 → 突跳 → 静止"三拍指纹把这一半补上。</p>
 *
 * <h3>为什么按 tick 采样，而不是逐朝向包</h3>
 * 一次朝向更新（[com.anticheat.core.util.update.RotationUpdate]）对应**一个包**，
 * 一个 tick 内可能到达多个。若逐包计算增量，客户端只要把一次大转弯拆成
 * 若干个小包发出来，三拍指纹就永远不成立——等于给出了一条现成的绕过路径。
 * 按服务端 tick 采样后，"一拍"就是服务端看到的一帧，拆包不再有意义。
 *
 * <h3>三道让路</h3>
 * 1. **骑乘 / 滑翔**：朝向由载具或滑翔物理参与驱动，不是纯手腕输入；
 * 2. **传送窗口 / 刚登录**：包序与朝向历史都不可信，且历史未填满；
 * 3. **延迟 > [MAX_JUDGEABLE_PING]**：高延迟下客户端补发的朝向包会成簇到达，
 *    在服务端表现为"没动 → 猛跳"的假三拍。
 *
 * <h3>为什么累积而不是单次判定</h3>
 * 单帧大角度转向本身不是违规——**甩枪（flick）是 PVP 基本功**，
 * 真人在被偷袭后瞬间回头同样会产生一次这样的三拍。因此单次只记证据
 * （[RotationSnap.weight]，7~50 分），累积到 [FLAG_BALANCE] 才告警；
 * 且balance 每 tick 自行降温 [DECAY_PER_TICK]，[COOLDOWN_TICKS] 内没有新证据
 * 就归零，避免把「一局游戏里陆续攒下的几次甩枪」算成一次持续作弊。
 *
 * <p>参考 intave `check/combat/heuristics/combatpatterns/rotation/RotationSnapHeuristic`
 * （三拍指纹 + 分级权重 + 邻近挥臂/攻击门槛）。参考实现在权重上做了一次
 * `vl /= 3` 来压低帧率客户端的误报；本检测没有 FPS 数据可用，
 * 改用"提高累积阈值 + 时间窗降温"达到同样的保守程度。</p>
 */
@CheckData(
    name = "AimC",
    decay = 0.05,
    setback = 0.0,
    description = "单帧把准星瞬移到目标（snap aim / 暴力瞬转瞄准）"
)
class AimC(player: PlayerData) : Check(player), ServerTickListener {

    /** 上一个 tick 的朝向；NaN 表示还没有可比的值。 */
    private var lastYaw = Float.NaN

    /** 第 N-1 拍的 |Δyaw|。 */
    private var motionBeforeLast = 0.0

    /** 第 N 拍的 |Δyaw|。 */
    private var lastMotion = 0.0

    /** 证据累积器（单位：权重分）。 */
    private var balance = 0.0

    override fun onServerTick() {
        // 先降温：证据是有时效的，很久以前的甩枪不该和现在的拼在一起
        balance = (balance - DECAY_PER_TICK).coerceAtLeast(0.0)

        if (!canJudge()) {
            reset()
            reward()
            return
        }

        val yaw = player.yaw
        val previousYaw = lastYaw
        lastYaw = yaw
        if (previousYaw.isNaN()) return

        val motion = abs(CoreMath.deltaDegrees(previousYaw, yaw)).toDouble()

        // 推进三拍窗口：(motionBeforeLast, lastMotion, motion)
        val twoAgo = motionBeforeLast
        motionBeforeLast = lastMotion
        lastMotion = motion

        if (!inCombat()) return

        if (!RotationSnap.isSnap(twoAgo, lastMotion, motion)) {
            reward()
            return
        }

        balance += RotationSnap.weight(lastMotion)
        if (balance > FLAG_BALANCE) {
            // 只扣掉一个阈值而不是清零：连续瞬转能持续告警，
            // 同时避免同一批证据被反复用来触发多次
            balance -= FLAG_BALANCE
            flag(
                "单帧瞬转：|Δyaw| 三拍=" + format(twoAgo) + "/" + format(lastMotion) + "/" + format(motion) +
                    "°，本次权重 " + format(RotationSnap.weight(lastMotion)) +
                    "（累积 " + format(balance + FLAG_BALANCE) + "）",
                VIOLATION_WEIGHT
            )
        }
    }

    /**
     * 现在能不能做瞬转判定。
     *
     * <p>让路条件集中在这里；[reset] 与它成对出现——让路期间必须清掉三拍窗口，
     * 否则"传送前的最后一拍 + 传送后的第一拍"会被拼成一个假的三拍指纹。</p>
     */
    private fun canJudge(): Boolean {
        if (!player.alive) return false
        if (player.serverInVehicle || player.serverGliding) return false

        val tick = AntiCheatCore.tickManager.currentTick
        if (tick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS) return false
        if (tick - player.joinTick < JOIN_GRACE_TICKS) return false
        if (player.ping > MAX_JUDGEABLE_PING) return false

        return true
    }

    /**
     * 最近是否处于战斗中。
     *
     * <p>瞬转的唯一目的是"让下一刀命中"，所以它必然紧贴着攻击或挥臂出现。
     * 这道门是**必需**的：没有它，正常玩家在赶路、环视、看风景时的一次快速甩头
     * 也会进入判定（实测对照组样本里唯一一次三拍命中就落在非战斗状态，
     * 加上这道门后被正确排除）。</p>
     */
    private fun inCombat(): Boolean {
        val now = System.currentTimeMillis()
        if (now - player.lastAttackMillis <= COMBAT_WINDOW_MILLIS) return true
        return now - player.lastSwingMillis <= COMBAT_WINDOW_MILLIS
    }

    private fun reset() {
        lastYaw = Float.NaN
        motionBeforeLast = 0.0
        lastMotion = 0.0
    }

    private fun format(value: Double): String = String.format("%.1f", value)

    companion object {
        /**
         * 累积到该值才告警。
         *
         * <p>60 分约等于"两次 90 度以上的单帧瞬转"，或"五六次刚好过线的小幅瞬转"。
         * 取值明显高于参考实现（其等效阈值约 30，但它额外做了 `vl /= 3` 的
         * 低帧率补偿）：本检测没有 FPS 数据，只能靠更高的门槛来买保守。</p>
         */
        const val FLAG_BALANCE = 60.0

        /**
         * 每个 tick 的证据降温量。
         *
         * <p>0.3/tick ⇒ 200 tick（10 秒）不出现新证据就自然归零。
         * 这个窗口比典型交火时间长，比"一局游戏"短得多，正好把
         * "连续作战中反复瞬转"与"偶尔甩一次鼠标"区分开。</p>
         */
        const val DECAY_PER_TICK = 0.3

        /** 攻击 / 挥手后多久内算"战斗中"（毫秒）。比 AimA 的 500ms 紧，瞬转服务于"下一刀"。 */
        const val COMBAT_WINDOW_MILLIS = 300L

        const val TELEPORT_IMMUNITY_TICKS = 40L

        const val JOIN_GRACE_TICKS = 100L

        /** 超过该延迟不做判定（与 TargetTracker 的视线类判据保持一致）。 */
        const val MAX_JUDGEABLE_PING = 400

        const val VIOLATION_WEIGHT = 1.0
    }
}
