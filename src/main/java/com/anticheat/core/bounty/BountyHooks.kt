package com.anticheat.core.bounty

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.player.PlayerData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 赏金沙箱与核心层之间的**唯一接缝**（与 `DatabaseGlue` 同一角色）。
 *
 * <h3>沙箱为什么不能复用 [PlayerData.exempt]</h3>
 * `exempt` 的语义是"完全豁免"：`Check.flag()` 的第一行就是
 * `if (player.exempt) return false`，于是检测**根本不跑**。
 * 而文档要求沙箱"关闭自动惩罚、但检测算法照常运行并打分"——
 * 用 `exempt` 实现出来的沙箱会变成"没有任何证据的沙箱"，
 * 于是每次任务都只能判"绕过成功"，这正是要修掉的那类假结论。
 *
 * <p>所以新增独立的 [PlayerData.sandbox] 标记，语义是
 * **"检测照常评分，但不处罚、不落生产库、不拉回"**：</p>
 * - 不处罚：`PunishmentManager.handleViolation` 直接返回 null；
 * - 不落生产库：沙箱里的违规若写进 `violation` 表，会污染风险评分与命中率统计
 *   （文档第六节要求的"特征库污染防护"）；
 * - 不拉回（setback）：把正在测试飞行的玩家拽回地面会让沙箱无法使用；
 * - **照常记分**：违规交给本类登记的 [SandboxDetectionListener]，写进案例与证据包。
 */
object BountyHooks {

    /** 沙箱内一次检测命中的回调。实现方**不得**抛异常（本类已 runCatching 兜底）。 */
    interface SandboxDetectionListener {
        fun onDetection(uuid: UUID, checkName: String, vl: Double, delta: Double)
    }

    private val listeners = ConcurrentHashMap<UUID, SandboxDetectionListener>()

    /** 沙箱会话开始：登记监听器并打上沙箱标记。 */
    @JvmStatic
    fun enterSandbox(uuid: UUID, listener: SandboxDetectionListener): Boolean {
        listeners[uuid] = listener
        val data = AntiCheatCore.playerDataManager.get(uuid) ?: return false
        data.sandbox = true
        return true
    }

    /** 沙箱会话结束：撤掉监听器与标记。 */
    @JvmStatic
    fun leaveSandbox(uuid: UUID) {
        listeners.remove(uuid)
        AntiCheatCore.playerDataManager.get(uuid)?.sandbox = false
    }

    @JvmStatic
    fun isSandbox(uuid: UUID): Boolean =
        AntiCheatCore.playerDataManager.get(uuid)?.sandbox ?: false

    /** 核心层在沙箱玩家触发检测时调用（[com.anticheat.core.check.Check.flag]）。 */
    @JvmStatic
    fun onSandboxFlag(player: PlayerData, checkName: String, vl: Double, delta: Double) {
        val listener = listeners[player.uuid] ?: return
        // 证据收集坏掉不能影响检测流程本身（沙箱判定是可以降级的，检测不行）
        runCatching { listener.onDetection(player.uuid, checkName, vl, delta) }
    }

    /** 当前有几个沙箱会话（给 `/ac` 排障用）。 */
    @JvmStatic
    fun sandboxCount(): Int = listeners.size
}
