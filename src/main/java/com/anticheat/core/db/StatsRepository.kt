package com.anticheat.core.db

import java.sql.ResultSet
import java.util.UUID

/**
 * 统计与分析：违规趋势、检查命中率、玩家风险画像。
 *
 * <h3>为什么重活交给视图</h3>
 * 聚合口径（按天 × 检测、每检测命中率、风险画像）在视图里定义一次，
 * 看板 / 命令 / 排障三条路径共用同一套口径。放在插件里各写一遍 SQL 的结果是
 * "同一个指标在三个地方算出三个值"——那比没有指标更糟。
 *
 * <p>写入侧的聚合（风险分重算）刻意留在 Kotlin（[RiskScorer]）：它是**策略**，
 * 需要能单测、能离线复算历史数据。</p>
 */
class StatsRepository(private val pool: JdbcPool) {

    /** 风险画像列表（`v_player_risk`，按分数倒序）。近 24h/7d 违规数是档案里的列，不现算。 */
    fun playerRisks(limit: Int = 20, minScore: Double = 0.0): List<PlayerRiskRow> =
        pool.withConnection { connection ->
            // 直查档案表并带上"用过几个 IP"的子查询：口径与 v_player_risk 视图一致，
            // 但省掉一次视图解析，也让 ip_count 始终存在（不用探测结果集列数）。
            connection.prepareStatement(
                "SELECT p.uuid, p.name, p.risk_score, p.total_violations, p.violations_24h, p.violations_7d, " +
                    "p.last_seen, p.last_ip, (SELECT count(*) FROM player_ip i WHERE i.uuid = p.uuid) AS ip_count " +
                    "FROM player_profile p WHERE p.risk_score >= ? " +
                    "ORDER BY p.risk_score DESC, p.violations_24h DESC LIMIT ?"
            ).use { statement ->
                statement.setDouble(1, minScore)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows -> collectRisk(rows) }
            }
        }

    /**
     * 某段时间内违规最多的玩家。
     *
     * <p>`violations_24h` / `violations_7d` 这两列在这里是"窗口内命中数"，
     * 窗口由调用方算好传入（[Sql] 的纪律：时间一律 BIGINT 参数）。</p>
     */
    fun topViolators(sinceMillis: Long, limit: Int = 20): List<PlayerRiskRow> =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "SELECT p.uuid, p.name, p.risk_score, p.total_violations, count(v.id) AS violations_24h, " +
                    "0 AS violations_7d, p.last_seen, p.last_ip " +
                    "FROM violation v JOIN player_profile p ON p.uuid = v.uuid " +
                    "WHERE v.created_at > ? " +
                    "GROUP BY p.uuid, p.name, p.risk_score, p.total_violations, p.last_seen, p.last_ip " +
                    "ORDER BY violations_24h DESC LIMIT ?"
            ).use { statement ->
                statement.setLong(1, sinceMillis)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows -> collectRisk(rows) }
            }
        }

    /** 某玩家的风险变化历史（看"是不是在变好"）。 */
    fun riskHistory(uuid: UUID, limit: Int = 50): List<Pair<Long, Double>> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT computed_at, score FROM risk_snapshot WHERE uuid = ? ORDER BY computed_at DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<Pair<Long, Double>>(limit)
                while (rows.next()) out.add(rows.getLong("computed_at") to rows.getDouble("score"))
                out
            }
        }
    }

    /** 记录一批风险快照（风险重算的产物，用来看趋势）。 */
    fun insertRiskSnapshots(scores: Map<UUID, Double>, counts: Map<UUID, Int>, windowHours: Int, nowMillis: Long) {
        if (scores.isEmpty()) return
        pool.withTransaction { connection ->
            connection.prepareStatement(
                "INSERT INTO risk_snapshot (uuid, score, violations, window_hours, computed_at) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                for ((uuid, score) in scores) {
                    statement.setObject(1, uuid)
                    statement.setDouble(2, score)
                    statement.setInt(3, counts[uuid] ?: 0)
                    statement.setInt(4, windowHours)
                    statement.setLong(5, nowMillis)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            true
        }
    }

    /** 玩家违规的检查分布（排障直查，不依赖视图）。 */
    fun checkBreakdown(uuid: UUID, sinceMillis: Long, limit: Int = 12): List<Pair<String, Long>> =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "SELECT check_name, count(*) AS c FROM violation WHERE uuid = ? AND created_at > ? " +
                    "GROUP BY check_name ORDER BY c DESC LIMIT ?"
            ).use { statement ->
                statement.setObject(1, uuid)
                statement.setLong(2, sinceMillis)
                statement.setInt(3, limit)
                statement.executeQuery().use { rows ->
                    val out = ArrayList<Pair<String, Long>>(limit)
                    while (rows.next()) out.add((rows.getString("check_name") ?: "?") to rows.getLong("c"))
                    out
                }
            }
        }

    /** 一行总览，写进启动日志 / 命令输出。 */
    fun summary(): String = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT (SELECT count(*) FROM player_profile) AS profiles, " +
                    "(SELECT count(*) FROM player_ip) AS ips, " +
                    "(SELECT count(*) FROM violation) AS violations, " +
                    "(SELECT count(*) FROM ban WHERE status = 'active') AS active_bans, " +
                    "(SELECT count(*) FROM check_rule) AS rules"
            ).use { rows ->
                if (!rows.next()) {
                    "无数据"
                } else {
                    "档案=" + rows.getLong("profiles") + " IP=" + rows.getLong("ips") +
                        " 违规=" + rows.getLong("violations") + " 生效封禁=" + rows.getLong("active_bans") +
                        " 规则=" + rows.getLong("rules")
                }
            }
        }
    }

    private fun collectRisk(rows: ResultSet): List<PlayerRiskRow> {
        val out = ArrayList<PlayerRiskRow>(16)
        while (rows.next()) {
            out.add(
                PlayerRiskRow(
                    uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
                    name = rows.getString("name") ?: "?",
                    riskScore = rows.getDouble("risk_score"),
                    totalViolations = rows.getInt("total_violations"),
                    lastSeen = Sql.millis(rows, "last_seen"),
                    lastIp = rows.getString("last_ip"),
                    violations24h = rows.getInt("violations_24h"),
                    violations7d = rows.getInt("violations_7d"),
                    ipCount = rows.getInt("ip_count")
                )
            )
        }
        return out
    }
}
