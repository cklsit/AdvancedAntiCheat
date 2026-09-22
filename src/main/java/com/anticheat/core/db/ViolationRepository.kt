package com.anticheat.core.db

import java.sql.ResultSet
import java.util.UUID

/**
 * 违规记录（`violation`）——核心层每判定一次违规就往这里写一条。
 *
 * <h3>为什么必须批量写</h3>
 * 违规是**最高频**的写操作：一次交火里一个检测可能连出几十条。一条一次 `INSERT`
 * 会把库和网络都打满，而且这些写发生在异步线程上、与判定链路共享连接池。
 * 所以走"攒批 + 事务 + `addBatch`"，节奏由 [ViolationRecorder] 控制；
 * 仓储这层只负责"把这一批写完"，不做缓冲（缓冲留在上层，便于丢包计数与超时刷写）。
 *
 * <h3>写入内容刻意包含"当时的环境"</h3>
 * 坐标 / 世界 / 子服 / ping / TPS / 客户端版本 / 包类型 / 检测的 verbose 摘要，
 * 都取**判定那一刻**的值。事后无法重建：等你去查的时候，玩家早就不在那个位置、
 * TPS 也恢复了。"事后可复算"才是这类表最重要的价值。
 */
class ViolationRepository(private val pool: JdbcPool) {

    private val selectColumns =
        "id, uuid, name, check_name, check_group, vl, vl_delta, severity, created_at, world, packet_type, detail"

    fun insertBatch(records: List<ViolationInput>): Int {
        if (records.isEmpty()) return 0
        return pool.withTransaction { connection ->
            connection.prepareStatement(
                "INSERT INTO violation (uuid, name, check_name, check_group, vl, vl_delta, severity, created_at, " +
                    "day_bucket, server_name, world, x, y, z, ping, tps, client_version, packet_type, detail, " +
                    "experimental, punished, punish_action) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                for (record in records) {
                    statement.setObject(1, record.uuid)
                    statement.setString(2, record.name)
                    statement.setString(3, record.checkName)
                    Sql.setString(statement, 4, record.checkGroup)
                    statement.setDouble(5, record.vl)
                    statement.setDouble(6, record.vlDelta)
                    statement.setInt(7, record.severity)
                    statement.setLong(8, record.createdAt)
                    statement.setLong(9, record.dayBucket)
                    Sql.setString(statement, 10, record.serverName)
                    Sql.setString(statement, 11, record.world)
                    Sql.setDouble(statement, 12, record.x)
                    Sql.setDouble(statement, 13, record.y)
                    Sql.setDouble(statement, 14, record.z)
                    Sql.setInt(statement, 15, record.ping)
                    Sql.setFloat(statement, 16, record.tps)
                    Sql.setString(statement, 17, record.clientVersion)
                    Sql.setString(statement, 18, record.packetType)
                    Sql.setString(statement, 19, record.detail)
                    statement.setBoolean(20, record.experimental)
                    statement.setBoolean(21, record.punished)
                    Sql.setString(statement, 22, record.punishAction)
                    statement.addBatch()
                }
                statement.executeBatch().size
            }
        }
    }

    fun recent(uuid: UUID, limit: Int = 50): List<ViolationRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $selectColumns FROM violation WHERE uuid = ? ORDER BY created_at DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows -> collect(rows) }
        }
    }

    /** 按检测名查最近违规——排查"某个检测是不是在误报"的第一入口。 */
    fun recentByCheck(checkName: String, limit: Int = 50): List<ViolationRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $selectColumns FROM violation WHERE check_name = ? ORDER BY created_at DESC LIMIT ?"
        ).use { statement ->
            statement.setString(1, checkName)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows -> collect(rows) }
        }
    }

    /** 某玩家每个检测的违规分布（风险画像下钻）。 */
    fun statsOf(uuid: UUID, sinceMillis: Long): List<PlayerCheckStatRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT check_name, count(*) AS violations, max(vl) AS max_vl, max(created_at) AS last_at " +
                "FROM violation WHERE uuid = ? AND created_at > ? GROUP BY check_name ORDER BY violations DESC"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setLong(2, sinceMillis)
            statement.executeQuery().use { rows ->
                val out = ArrayList<PlayerCheckStatRow>(8)
                while (rows.next()) {
                    out.add(
                        PlayerCheckStatRow(
                            checkName = rows.getString("check_name") ?: "",
                            violations = rows.getLong("violations"),
                            maxVl = rows.getDouble("max_vl"),
                            lastAt = Sql.millis(rows, "last_at")
                        )
                    )
                }
                out
            }
        }
    }

    /**
     * 该玩家**历史上被处罚过**的违规条数。
     *
     * <p>惩罚阶梯的升档依据就是它（而不是 VL）：每次处罚只会把触发它的
     * 那一条违规标为 `punished`，所以条数 ≈ 历史被处罚次数（不新建计数表：
     * 再加一份计数就多一个会不一致的地方）。</p>
     */
    fun countPunished(uuid: UUID): Int = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM violation WHERE uuid = ? AND punished = TRUE"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }

    fun countSince(sinceMillis: Long): Long = pool.withConnection { connection ->
        connection.prepareStatement("SELECT count(*) FROM violation WHERE created_at > ?").use { statement ->
            statement.setLong(1, sinceMillis)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    /** 时间窗口内命中次数最多的检测（"哪个检测在干活"）。 */
    fun topChecksSince(sinceMillis: Long, limit: Int = 10): List<Pair<String, Long>> =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "SELECT check_name, count(*) AS c FROM violation WHERE created_at > ? " +
                    "GROUP BY check_name ORDER BY c DESC LIMIT ?"
            ).use { statement ->
                statement.setLong(1, sinceMillis)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows ->
                    val out = ArrayList<Pair<String, Long>>(limit)
                    while (rows.next()) out.add((rows.getString("check_name") ?: "?") to rows.getLong("c"))
                    out
                }
            }
        }

    /** 违规趋势（按天 × 检测，走 `v_violation_trend` 视图）。 */
    fun trend(sinceMillis: Long): List<ViolationTrendRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT day_bucket, check_name, violations, players, avg_vl_delta, max_vl FROM v_violation_trend " +
                "WHERE day_bucket >= ? ORDER BY day_bucket DESC, violations DESC"
        ).use { statement ->
            statement.setLong(1, Sql.dayBucket(sinceMillis))
            statement.executeQuery().use { rows ->
                val out = ArrayList<ViolationTrendRow>(32)
                while (rows.next()) {
                    out.add(
                        ViolationTrendRow(
                            dayMillis = rows.getLong("day_bucket"),
                            checkName = rows.getString("check_name") ?: "",
                            violations = rows.getLong("violations"),
                            players = rows.getLong("players"),
                            avgVlDelta = rows.getDouble("avg_vl_delta"),
                            maxVl = rows.getDouble("max_vl")
                        )
                    )
                }
                out
            }
        }
    }

    /**
     * 时间窗口内每个玩家的违规事件（风险重算的输入）。
     *
     * <p>用 `created_at`（BIGINT 毫秒）与调用方算好的阈值比较，
     * 不用 `now() - interval`：后者是方言相关的，且无法用索引做范围扫描。</p>
     */
    @JvmOverloads
    fun eventsByPlayer(sinceMillis: Long, limit: Int = 20_000): Map<UUID, MutableList<RiskEvent>> =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "SELECT uuid, vl_delta, severity, created_at FROM violation WHERE created_at > ? ORDER BY uuid LIMIT ?"
            ).use { statement ->
                statement.setLong(1, sinceMillis)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows ->
                    val out = HashMap<UUID, MutableList<RiskEvent>>(64)
                    while (rows.next()) {
                        val uuid = Sql.uuid(rows, "uuid") ?: continue
                        out.getOrPut(uuid) { ArrayList(4) }.add(
                            RiskEvent(
                                vlDelta = rows.getDouble("vl_delta"),
                                atMillis = rows.getLong("created_at"),
                                severity = rows.getInt("severity")
                            )
                        )
                    }
                    out
                }
            }
        }

    /** 删除早于给定时间的违规（保留策略，避免库无限增长）。 */
    fun deleteOlderThan(beforeMillis: Long): Int = pool.withConnection { connection ->
        connection.prepareStatement("DELETE FROM violation WHERE created_at < ?").use { statement ->
            statement.setLong(1, beforeMillis)
            statement.executeUpdate()
        }
    }

    private fun collect(rows: ResultSet): List<ViolationRow> {
        val out = ArrayList<ViolationRow>(16)
        while (rows.next()) {
            out.add(
                ViolationRow(
                    id = rows.getLong("id"),
                    uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
                    name = rows.getString("name") ?: "?",
                    checkName = rows.getString("check_name") ?: "?",
                    checkGroup = rows.getString("check_group"),
                    vl = rows.getDouble("vl"),
                    vlDelta = rows.getDouble("vl_delta"),
                    severity = rows.getInt("severity"),
                    createdAt = Sql.millis(rows, "created_at"),
                    world = rows.getString("world"),
                    packetType = rows.getString("packet_type"),
                    detail = rows.getString("detail")
                )
            )
        }
        return out
    }
}
