package com.anticheat.core.check.impl.autoclicker

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.check.type.ServerTickListener
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog

/**
 * 连击爆发 —— 连续多个 tick 不间断地发送攻击包。
 *
 * <h3>为什么需要它：一个版本门造成的整块盲区</h3>
 * [AutoClickerA] 与 [AutoClickerB] 的判据建立在 **1.8~1.12 客户端的连点时序**上
 * （间隔标准差 / 香农熵），因此它们对 1.13+ 客户端**直接跳过**——
 * 这不是疏忽，而是因为那些阈值是在旧客户端的时序上调出来的，
 * 拿去做现代客户端必然误报。结果是：玩家只要换一个现代客户端
 * （经 ViaVersion 连到 1.8 服务端），点击类检测就整类失效。
 *
 * <p>本检测刻意**不依赖任何版本相关的时序假设**，只数"哪些 tick 发出了攻击包"，
 * 因此对全客户端版本生效，正好把这块盲区补上。</p>
 *
 * <h3>判据的物理依据</h3>
 * 原版 1.8 客户端的左键有 **10 tick 冷却**（`leftClickCounter` 在一次攻击后置位），
 * 所以正常玩家按住左键的出手节奏接近每 10 拍一次，**不可能出现连续 6 拍每拍都出手**。
 * 由 [ClickStreaks] 统计"不间断的攻击段"，超过 [ClickStreaks.MIN_STREAK] 的段
 * 累加权重，达到 [ClickStreaks.FLAG_VL] 即告警。
 *
 * <h3>为什么只数攻击包，不数挥臂</h3>
 * 挥臂（`ARM_ANIMATION`）在挖掘、放置、空挥时都会发，"按住左键"也会持续发，
 * 用它做连击判据会把建筑与挖矿玩家成批算进去。攻击包
 * （`USE_ENTITY(ATTACK)`）只在真的打实体时发出，语义干净——
 * 这也是本检测不需要"放置方块时作废"这条补救逻辑的原因。</p>
 *
 * <h3>标定（首次上线建议先观察）</h3>
 * 该判据的阈值来自"1.8 客户端 10 tick 冷却"这条机制，而非实测分布。
 * 把它打开 `calibrate: true` 后，每 [DEFAULT_CALIBRATE_WINDOWS] 个窗口
 * （约 5 分钟）会打印一次实测的"最长连续段 / 违规级别峰值 / 攻击 tick 数"，
 * 用来确认阈值离真实玩家的行为有多远：
 *
 * <p>特别注意 **1.13+ 客户端**——它们在 1.9+ 的冷却模型下运行，
 * 而经 ViaVersion 连到 1.8 服务端时冷却是否被客户端禁用，取决于客户端实现。
 * 若标定发现正常现代客户端玩家的最长段普遍接近阈值，应上调
 * [ClickStreaks.MIN_STREAK] 对应的档位而不是放任误报。</p>
 *
 * <p>参考 intave `check/combat/clickpatterns/Bursts`（窗口 40 tick、段长 &gt; 5、
 * vl 累积到 20）。本实现的偏离见 [ClickStreaks] 的类注释。</p>
 */
@CheckData(
    name = "AutoClickerD",
    decay = 0.1,
    setback = 0.0,
    description = "连续多个 tick 不间断发送攻击包（机械连击 / KillAura 连发）"
)
class AutoClickerD(player: PlayerData) : Check(player), ServerTickListener {

    /** 窗口内每个 tick 是否发出过攻击包。 */
    private val acted = BooleanArray(WINDOW_TICKS)

    /** 窗口内每个 tick 是否发出过多次攻击包。 */
    private val multi = BooleanArray(WINDOW_TICKS)

    /** 环形写入游标；归零时 [acted] / [multi] 的内容恰好就是完整的写入顺序。 */
    private var cursor = 0

    // ---- 标定统计（只在 calibrate 打开时被读取）----
    private var windows = 0L
    private var attackTicks = 0L
    private var longestStreak = 0
    private var peakVl = 0.0

    /** 段长度门槛；由 `min-streak` 下发，可被自动调参收紧。 */
    @Volatile
    private var minStreak: Int = ClickStreaks.MIN_STREAK

    /** 窗口内触发告警所需的违规级别；由 `flag-vl` 下发，可被自动调参收紧。 */
    @Volatile
    private var flagVl: Double = DEFAULT_FLAG_VL

    @Volatile
    private var calibrate: Boolean = false

    @Volatile
    private var calibrateWindows: Long = DEFAULT_CALIBRATE_WINDOWS

    override fun onServerTick() {
        if (!canJudge()) {
            // 让路期间必须清空窗口：把"传送前的一串连击"与"传送后的"拼成一段，
            // 会凭空造出一个足够长的段
            resetWindow()
            reward()
            return
        }

        val attacks = player.attacksThisTick
        acted[cursor] = attacks > 0
        multi[cursor] = attacks > 1

        cursor++
        if (cursor < WINDOW_TICKS) return

        // 窗口刚好写满一轮，且内容就是写入顺序
        cursor = 0
        analyse()
    }

    private fun analyse() {
        val vl = ClickStreaks.violationLevel(acted, multi, minStreak)
        val streak = ClickStreaks.longestStreak(acted)

        var attackTicksThisWindow = 0
        for (i in acted.indices) {
            if (acted[i]) attackTicksThisWindow++
        }
        trackCalibration(attackTicksThisWindow, streak, vl)

        if (vl >= flagVl) {
            flag(
                "窗口内连续攻击段过长：违规级别 " + format(vl) +
                    "（最长段 " + streak + " tick，阈值 " + format(flagVl) +
                    "；窗口 " + WINDOW_TICKS + " tick 内有 " + attackTicksThisWindow + " 拍攻击）",
                VIOLATION_WEIGHT
            )
        } else {
            reward()
        }
    }

    private fun trackCalibration(attackTicksThisWindow: Int, streak: Int, vl: Double) {
        windows++
        attackTicks += attackTicksThisWindow
        if (streak > longestStreak) longestStreak = streak
        if (vl > peakVl) peakVl = vl

        if (!calibrate || calibrateWindows <= 0L) return
        if (windows % calibrateWindows != 0L) return

        CoreLog.info(
            "[AutoClickerD 标定] " + player.name +
                " 窗口=" + windows +
                " 攻击tick=" + attackTicks +
                " 最长段=" + longestStreak + " tick" +
                " 违规级别峰值=" + format(peakVl) +
                "（判定线 " + format(flagVl) + "）"
        )
        longestStreak = 0
        peakVl = 0.0
    }

    private fun canJudge(): Boolean {
        if (!player.alive) return false

        val tick = AntiCheatCore.tickManager.currentTick
        if (tick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS) return false
        if (tick - player.joinTick < JOIN_GRACE_TICKS) return false

        return true
    }

    private fun resetWindow() {
        acted.fill(false)
        multi.fill(false)
        cursor = 0
    }

    private fun format(value: Double): String = String.format("%.1f", value)

    override fun reload() {
        super.reload()
        val manager = AntiCheatCore.configManager
        minStreak = manager.optionInt(configName, "min-streak", ClickStreaks.MIN_STREAK)
        flagVl = manager.optionDouble(configName, "flag-vl", DEFAULT_FLAG_VL)
        calibrate = manager.optionBoolean(configName, "calibrate", false)
        calibrateWindows = manager.optionInt(
            configName, "calibrate-windows", DEFAULT_CALIBRATE_WINDOWS.toInt()
        ).toLong().coerceAtLeast(1L)
    }

    companion object {
        /**
         * 分析窗口长度（tick）。
         *
         * <p>取 40（2 秒）与参考实现一致：太短则一个正常交火里的小停顿就会切段，
         * 让长段（我们要找的）显得比实际短；太长则告警延迟明显。</p>
         */
        const val WINDOW_TICKS = 40

        /** 标定汇总的默认窗口数（40 tick × 150 ≈ 5 分钟）。 */
        const val DEFAULT_CALIBRATE_WINDOWS = 150L

        const val TELEPORT_IMMUNITY_TICKS = 40L

        const val JOIN_GRACE_TICKS = 100L

        const val VIOLATION_WEIGHT = 1.0

        /** 判定线的默认值（可被 `core.checks.AutoClickerD.flag-vl` 覆盖）。 */
        const val DEFAULT_FLAG_VL = ClickStreaks.FLAG_VL

        /**
         * 自动调参的硬下限。
         *
         * <p>依据：一个**刚好越过门槛**的段（6 拍）贡献 `6 + 2 = 8` 分，
         * 自动调整不得让**单个刚过线的段**就单独告警（那等于把门槛游戏化），
         * 所以判定线必须严格大于 8，取 10。</p>
         */
        const val AUTO_TUNE_FLOOR_FLAG_VL = 10.0

        /**
         * `min-streak` 的自动调参硬下限。
         *
         * <p>与 `CombatCheckThresholdsTest` 的断言同源：门槛低于 3 时
         * 人类的高频点击（蝴蝶点击）也能连成这么长的段。</p>
         */
        const val AUTO_TUNE_FLOOR_MIN_STREAK = 3
    }
}
