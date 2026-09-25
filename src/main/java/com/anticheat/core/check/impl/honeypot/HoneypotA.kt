package com.anticheat.core.check.impl.honeypot

import com.anticheat.core.check.Check
import com.anticheat.core.check.CheckData
import com.anticheat.core.player.PlayerData

/**
 * 蜜罐命中（由蜜罐模块**外部上报**，不是包驱动）。
 *
 * <h3>为什么把它做成核心层检测</h3>
 * 蜜罐（幻象矿石 / 假掉落 / 不可能破坏进度 / 假逃脱）原本上报给旧引擎的
 * `ViolationManager`，再由旧引擎自己数违规、自己封禁。旧引擎整体移除后，
 * 蜜罐如果没有新的落点就会变成"检测到也没人管"的装饰品，因此改为上报给核心层：
 * **走 [flag] 这一条唯一违规入口**，于是 VL 记账、`violation` 落库、惩罚阶梯、
 * `audit_log` 留痕、沙箱豁免全都自动一致，不必再维护第二套处罚语义。
 *
 * <h3>为什么不衰减（没有 reward 调用）</h3>
 * 核心层其它检测都在"合规路径"上调用 `reward()` 让分数回落，而蜜罐是
 * **事件驱动且近乎确认性**的判据（挖掉一个服务端根本不存在的钻石矿、
 * 收集一个刚生成的假掉落物），没有对应的"合规动作"可以降温。
 *
 * <p>这不构成误封风险：违规分是**会话内**的量（检测实例随玩家上下线重建），
 * 单次命中只有 0.5~1.0 分，而惩罚阶梯第一档要 `min-vl: 8`；
 * 跨会话的升档依据是 `violation.punished` 的条数，不是这个分数。</p>
 *
 * <p>权重必须留在 0.2~5.0：给太高会让"手滑一次"直接跳到重档，
 * 给太低则让确认性证据失去意义。上报方按判据强度给（幻象矿石 1.0、
 * 瞬挖 0.8、假掉落 0.85）。</p>
 */
@CheckData(
    name = "HoneypotA",
    decay = 0.0,
    setback = 0.0,
    description = "蜜罐命中（幻象矿石 / 假掉落 / 不可能破坏进度 / 假逃脱；外部上报）"
)
class HoneypotA(player: PlayerData) : Check(player) {

    /**
     * 外部上报一次蜜罐命中。
     *
     * @param detail 命中细节（会写进 `violation.verbose`，排障靠它）
     * @param weight 证据权重，超出区间会被夹住
     * @return true 表示本次记账成功（开关/豁免/沙箱都没拦掉）
     */
    fun report(detail: String, weight: Double): Boolean =
        flag(detail, weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT))

    /** 当前会话内的蜜罐证据分（给蜜罐的"假逃脱"阈值判定用）。 */
    fun evidence(): Double = violations

    companion object {
        /** 稳定标识：核心层按它取实例（见 `HoneypotHooks`）。 */
        const val NAME = "HoneypotA"

        const val MIN_WEIGHT = 0.2
        const val MAX_WEIGHT = 5.0
    }
}
