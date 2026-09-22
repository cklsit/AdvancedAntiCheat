package com.anticheat.core.db

import java.util.UUID

/**
 * 数据库行模型。全部是**裸值对象**（无行为、无平台依赖），
 * 这样仓储与业务之间没有隐式耦合，映射逻辑也能离线测。
 *
 * <p>时间一律用 epoch 毫秒表示，而不是 `Instant`/`Timestamp`：
 * 插件里已有的时间语义（封禁到期、审计时间戳、事件时间）全是毫秒，
 * 在边界上混用两套表示是"时间悄悄偏 8 小时"的经典成因。</p>
 */

/** 玩家档案（`player_profile`）。 */
class ProfileRow(
    val uuid: UUID,
    val name: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastIp: String?,
    val sessions: Int,
    val playtimeSeconds: Long,
    /** 0~100 的风险评分，由 [RiskScorer] 计算。 */
    val riskScore: Double,
    val totalViolations: Int,
    /** 近 24 小时违规数（由风险重算任务写入，看板直接读，避免每次查询全表扫描）。 */
    val violations24h: Int,
    val violations7d: Int,
    val profileUpdated: Long?
)

/**
 * 一条"某玩家用过某名字"的历史记录（`player_name`）。
 *
 * <p>单独一张表而不是塞进档案的一个字符串列：按旧名反查账号
 * （"这个号以前叫什么""这个名字被哪些 uuid 用过"）是小号识别的基本手段，
 * 字符串列做不到。</p>
 */
class PlayerNameRow(
    val uuid: UUID,
    val name: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val uses: Int
)

/** 一条"玩家用过某个 IP"的记录（`player_ip`）。 */
class IpRow(
    val uuid: UUID,
    val ip: String,
    /** 4 或 6。 */
    val family: Int,
    /** 归并后的网段（IPv4 /24、IPv6 /64）。 */
    val cidr: String,
    val asn: Int?,
    val org: String?,
    val country: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val loginCount: Int
)

/** 封禁记录（`ban`）。支持 UUID / IP / CIDR 三种目标，临时与永久两种时长。 */
class BanRow(
    val id: Long,
    /** `uuid` / `ip` / `cidr`。 */
    val kind: String,
    /**
     * 封禁目标：`kind=uuid` 时是 uuid 字符串，`kind=ip`/`cidr` 时是地址或网段。
     *
     * <p>三种目标共用一个列，是为了让"同一目标同时只有一条生效封禁"这个约束
     * 能用**一个可空唯一键**（`active_key`）表达——两个引擎都支持唯一索引里
     * 存在多个 NULL，而 PostgreSQL 的部分唯一索引 H2 没有。</p>
     */
    val target: String,
    /** 被封玩家的名字（IP/CIDR 封禁下是当时记下的关联名字，可能为空）。 */
    val name: String?,
    val uuid: UUID?,
    /** `temp` / `perm`。 */
    val scope: String,
    val reason: String,
    val operator: String,
    val issuedAt: Long,
    /** null = 永久。 */
    val expiresAt: Long?,
    /** `active` / `expired` / `revoked`。 */
    val status: String,
    val revokedBy: String?,
    val revokedAt: Long?,
    val revokeReason: String?,
    val serverName: String?,
    val violationId: Long?
) {
    /** 当前是否仍然生效（把"时间上已过期但状态还没刷"也算进去）。 */
    fun isEffective(now: Long = System.currentTimeMillis()): Boolean =
        status == BanStatus.ACTIVE && (expiresAt == null || expiresAt > now)
}

/** 封禁状态与类型的字面量，避免各处硬编码字符串拼错。 */
object BanStatus {
    const val ACTIVE = "active"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
}

object BanKind {
    const val UUID = "uuid"
    const val IP = "ip"
    const val CIDR = "cidr"
}

object BanScope {
    const val TEMP = "temp"
    const val PERM = "perm"
}

/** 一次违规（`violation`）——写入侧的输入对象。 */
class ViolationInput(
    val uuid: UUID,
    val name: String,
    val checkName: String,
    val checkGroup: String?,
    /** 记账后的总违规分。 */
    val vl: Double,
    /** 本次增加量。 */
    val vlDelta: Double,
    /** 1~4，由 [RiskScorer.severityOf] 推出。 */
    val severity: Int,
    val createdAt: Long,
    /** 写入时算好的"天"分组键（[Sql.dayBucket]）：趋势统计靠它，免掉 date_trunc 的方言差异。 */
    val dayBucket: Long,
    val serverName: String?,
    val world: String?,
    val x: Double?,
    val y: Double?,
    val z: Double?,
    val ping: Int?,
    val tps: Float?,
    val clientVersion: String?,
    /** 触发时的数据包上下文（包类型 + 检测的 verbose 摘要）。 */
    val packetType: String?,
    val detail: String?,
    val experimental: Boolean,
    val punished: Boolean,
    val punishAction: String?
)

/** 一次违规的落库行（读取侧）。 */
class ViolationRow(
    val id: Long,
    val uuid: UUID,
    val name: String,
    val checkName: String,
    val checkGroup: String?,
    val vl: Double,
    val vlDelta: Double,
    val severity: Int,
    val createdAt: Long,
    val world: String?,
    val packetType: String?,
    val detail: String?
)

/** 一个检测的阈值配置（`check_rule`）。 */
class CheckRuleRow(
    val checkName: String,
    val enabled: Boolean,
    val decay: Double?,
    val setback: Double?,
    val experimental: Boolean,
    val description: String?,
    /** 该检测的专属阈值（例如 `max-reach` / `tolerance` / `ping-slack`），来自 config.yml。 */
    val thresholds: Map<String, String>,
    val updatedAt: Long
)

/** 惩罚阶梯的一级（`punishment_ladder`）。 */
class LadderStep(
    val step: Int,
    val minVl: Double,
    val action: String,
    val duration: String?,
    val reason: String?
)

/** 白名单条目（`whitelist_entry`）。 */
class WhitelistRow(
    val id: Long,
    val uuid: UUID?,
    val name: String?,
    val reason: String?,
    val addedBy: String?,
    val addedAt: Long,
    val expiresAt: Long?
)

/** 检查命中率统计桶（`check_stat`）。 */
class CheckStatRow(
    val bucket: Long,
    val checkName: String,
    val evaluations: Long,
    val flags: Long,
    val vlSum: Double,
    val players: Int
) {
    val hitRate: Double get() = if (evaluations <= 0) 0.0 else flags.toDouble() / evaluations
}

/** 待写库的统计增量（核心层每 [com.anticheat.core.db.DatabaseSettings.PostgresSettings] 个桶刷一次）。 */
class CheckStatDelta(
    val checkName: String,
    val evaluations: Long,
    val flags: Long,
    val vlSum: Double,
    val players: Int
)

/** 审计记录（`audit_log`），字段与旧 `AuditRecord` 一一对应以保持功能不变。 */
class AuditRow(
    val id: Long?,
    val timestamp: Long,
    val operator: String?,
    val operatorRole: Int,
    val type: String?,
    val target: String?,
    val ip: String?,
    val result: String?,
    val detail: String?
)

/** 审计查询条件，语义与旧 `AuditQuery` 保持一致（精确匹配 + 关键字模糊 + 时间区间 + 分页）。 */
class AuditFilter(
    val type: String? = null,
    val result: String? = null,
    val keyword: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val page: Int = 1,
    val pageSize: Int = 20
) {
    /** 归一化：页码从 1 起，页长钳到 1~200（与旧实现一致）。 */
    fun normalized(): AuditFilter = AuditFilter(
        type = type?.takeIf { it.isNotBlank() },
        result = result?.takeIf { it.isNotBlank() },
        keyword = keyword?.takeIf { it.isNotBlank() },
        startTime = startTime,
        endTime = endTime,
        page = if (page < 1) 1 else page,
        pageSize = when {
            pageSize < 1 -> 1
            pageSize > 200 -> 200
            else -> pageSize
        }
    )

    val offset: Int get() = (page - 1) * pageSize
}

/** 风险事件（只给 [RiskScorer] 用）。 */
class RiskEvent(
    val vlDelta: Double,
    val atMillis: Long,
    val severity: Int
)

/** 玩家风险画像（`v_player_risk` 视图）。 */
class PlayerRiskRow(
    val uuid: UUID,
    val name: String,
    val riskScore: Double,
    val totalViolations: Int,
    val lastSeen: Long,
    val lastIp: String?,
    val violations24h: Int,
    val violations7d: Int,
    val ipCount: Int
)

/** 违规趋势的一行（`v_violation_trend` 视图）。 */
class ViolationTrendRow(
    val dayMillis: Long,
    val checkName: String,
    val violations: Long,
    val players: Long,
    val avgVlDelta: Double,
    val maxVl: Double
)

/** 检查命中率的一行（`v_check_hit_rate` 视图）。 */
class HitRateRow(
    val checkName: String,
    val evaluations: Long,
    val flags: Long,
    val hitRate: Double?
)

/** 玩家 × 检查的违规分布（风险画像的下钻）。 */
class PlayerCheckStatRow(
    val checkName: String,
    val violations: Long,
    val maxVl: Double,
    val lastAt: Long
)

// ======================================================================
// 赏金沙箱（bounty）
// ======================================================================

/**
 * 赏金钱包（`bounty_wallet`）。
 *
 * <p>余额与"累计获得/累计消费"分成三列：只存余额的话，事后无法回答
 * "这个人到底贡献过多少"——而排行榜与称号发放用的正是累计值。</p>
 */
class BountyWalletRow(
    val uuid: UUID,
    val name: String,
    val tokens: Long,
    val earned: Long,
    val spent: Long,
    val updatedAt: Long
)

/**
 * 一条赏金案例（`bounty_case`）。
 *
 * <p>把判定时的**全部输入**（flags / maxVl / 异常分 / 基线是否就绪 / 采样数）
 * 一起存下来：判定是可复算的，但基线会随时间变化，只存结论的话
 * 事后永远说不清"当时为什么判它是绕过"。</p>
 *
 * @param status `pending`（待人工复核）/ `accepted`（特征已采纳）/ `rejected`（驳回）
 */
class BountyCaseRow(
    val id: Long,
    val uuid: UUID,
    val name: String,
    val task: String,
    val verdict: String,
    val confidence: String,
    val tokens: Long,
    val flags: Int,
    val maxVl: Double,
    val anomalyScore: Double,
    val baselineReady: Boolean,
    val samples: Int,
    val reason: String?,
    val summary: String?,
    val evidencePath: String?,
    val status: String,
    val createdAt: Long,
    val reviewedBy: String?,
    val reviewedAt: Long?
)

/** 每日沙箱时长（`bounty_daily`），主键 = (玩家, 天)。 */
class BountyDailyRow(
    val uuid: UUID,
    val dayMillis: Long,
    val seconds: Long,
    val sessions: Int
)

/**
 * 商城兑换记录（`bounty_purchase`）。
 *
 * <p>`one_time` 的条目靠 `purchase_key` 的唯一约束保证"只能兑换一次"——
 * 这也是"可空唯一键"这个惯用法的又一次应用（见 `Sql` 的类注释）。</p>
 */
class BountyPurchaseRow(
    val id: Long,
    val uuid: UUID,
    val name: String,
    val itemId: String,
    val cost: Long,
    val createdAt: Long
)

/** 人类基线的一行（`bounty_baseline`）。 */
class BountyBaselineRow(
    val metricKey: String,
    val mean: Double,
    val sd: Double,
    val samples: Long,
    val direction: String
)

/** 赏金排行榜一行。 */
class BountyRankRow(
    val uuid: UUID,
    val name: String,
    val tokens: Long,
    val earned: Long
)
