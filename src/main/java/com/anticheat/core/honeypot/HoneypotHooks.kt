package com.anticheat.core.honeypot

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.impl.honeypot.HoneypotA
import com.anticheat.core.util.CoreLog
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蜜罐模块与核心层之间的**唯一接缝**（与 `DatabaseGlue` / `BountyHooks` 同一角色）。
 *
 * <h3>为什么需要它</h3>
 * 蜜罐住在旧引擎之外（`listeners/HoneypotListener`），却在旧引擎里上报违规。
 * 旧引擎整体移除后，蜜罐改调这里：由本类把"哪个玩家的哪个检测"翻译成
 * 核心层的 [HoneypotA] 实例，再走 `Check.flag` —— **不新增第二套处罚路径**。
 * 这是刻意的：这次线上误封的根因就是"两个引擎各有一套阈值与封禁逻辑"。
 *
 * <p>核心层未启用（`core.enabled: false` 或初始化失败降级）时，本类**只告警一次**
 * 然后返回 false，而不是静默丢弃——"蜜罐检测到了但没人处理"必须在日志里看得见。</p>
 */
object HoneypotHooks {

    private val warnedUnavailable = AtomicBoolean(false)

    /**
     * 上报一次蜜罐命中。
     *
     * @param player 命中玩家
     * @param detail 细节（写进 `violation.verbose`）
     * @param weight 证据权重（0.2~5.0，由 [HoneypotA] 夹住）
     * @return true 表示已记账；核心层未就绪或检测被关闭时返回 false
     */
    @JvmStatic
    fun report(player: Player, detail: String, weight: Double): Boolean {
        val check = checkOf(player)
        if (check == null) {
            warnUnavailableOnce()
            return false
        }
        return check.report(detail, weight)
    }

    /**
     * 该玩家当前会话内的蜜罐证据分。
     *
     * <p>用于蜜罐自己的"假逃脱"阈值判定（原实现用的是旧引擎的违规条数）。
     * 语义变化：现在是**加权证据分**而不是"条数"，
     * 所以 `honeypot.fake-escape.threshold` 的量纲随之变化（见配置注释）。</p>
     */
    @JvmStatic
    fun evidence(player: Player): Double = checkOf(player)?.evidence() ?: 0.0

    /** 该玩家是否持有可用的蜜罐检测（核心层就绪且检测已登记）。 */
    @JvmStatic
    fun available(player: Player): Boolean = checkOf(player) != null

    private fun checkOf(player: Player): HoneypotA? {
        val data = AntiCheatCore.playerDataManager.get(player.uniqueId) ?: return null
        return data.checkManager.check(HoneypotA.NAME) as? HoneypotA
    }

    private fun warnUnavailableOnce() {
        if (warnedUnavailable.compareAndSet(false, true)) {
            CoreLog.warn(
                "蜜罐命中无法上报：核心层未就绪（core.enabled=false 或初始化降级）。" +
                    "核心层是蜜罐违规的唯一落点，此状态下蜜罐只记录控制台告警、不入库不处罚。"
            )
        }
    }
}
