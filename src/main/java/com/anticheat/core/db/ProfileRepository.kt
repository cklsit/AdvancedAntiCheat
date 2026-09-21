package com.anticheat.core.db

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * 玩家档案（`player_profile` + `player_name`）。
 *
 * <h3>与旧 `player_profiles` 表的区别</h3>
 * 旧表只有 `(player_uuid, profile_data, last_updated)` 三列——**除了整体反序列化，
 * 什么也查不了**："这个人第一次来是什么时候""这个名字以前被谁用过"
 * 这类问题在旧结构下无法用 SQL 回答。新结构把可查询的事实摊成列
 * （首末登录 / 最后 IP / 会话数 / 游戏时长 / 风险分 / 违规总数 / 近 24h 与 7d 违规数），
 * 用户名历史独立成 `player_name` 表（能按旧名反查），
 * 同时**保留 `profile_blob` 列**继续承载行为画像的整体序列化数据。
 *
 * <p>那个 blob 刻意与旧格式逐字兼容（Java 原生序列化 + Base64）：
 * 改它的字段会让所有历史档案在反序列化时抛 `InvalidClassException`，
 * 而档案是"删了就没了"的数据，不值得为省一列去冒这个险。</p>
 */
class ProfileRepository(private val pool: JdbcPool) {

    private val profileColumns =
        "uuid, name, first_seen, last_seen, last_ip, sessions, playtime_secs, risk_score, " +
            "total_violations, violations_24h, violations_7d, profile_updated"

    /**
     * 玩家登录：建档案 / 更新"最近一次"，并累加本次用到的用户名。
     *
     * <p>整件事在一个事务里：档案行与名字行必须同时存在或同时不存在——
     * 只写了一半的话，`player_name` 里会出现指向不存在档案的孤儿行。</p>
     */
    fun onLogin(uuid: UUID, name: String, ip: String?, nowMillis: Long) {
        pool.withTransaction { connection ->
            Sql.upsert(
                connection,
                updateSql = "UPDATE player_profile SET name = ?, last_seen = ?, last_ip = ?, " +
                    "sessions = sessions + 1 WHERE uuid = ?",
                bindUpdate = { statement ->
                    statement.setString(1, name)
                    statement.setLong(2, nowMillis)
                    Sql.setString(statement, 3, ip)
                    statement.setObject(4, uuid)
                },
                insertSql = "INSERT INTO player_profile (uuid, name, first_seen, last_seen, last_ip, sessions) " +
                    "VALUES (?, ?, ?, ?, ?, 1)",
                bindInsert = { statement ->
                    statement.setObject(1, uuid)
                    statement.setString(2, name)
                    statement.setLong(3, nowMillis)
                    statement.setLong(4, nowMillis)
                    Sql.setString(statement, 5, ip)
                }
            )
            Sql.upsert(
                connection,
                updateSql = "UPDATE player_name SET last_seen = ?, uses = uses + 1 WHERE uuid = ? AND name = ?",
                bindUpdate = { statement ->
                    statement.setLong(1, nowMillis)
                    statement.setObject(2, uuid)
                    statement.setString(3, name)
                },
                insertSql = "INSERT INTO player_name (uuid, name, first_seen, last_seen, uses) VALUES (?, ?, ?, ?, 1)",
                bindInsert = { statement ->
                    statement.setObject(1, uuid)
                    statement.setString(2, name)
                    statement.setLong(3, nowMillis)
                    statement.setLong(4, nowMillis)
                }
            )
            true
        }
    }

    /** 玩家离线：累加本次在线时长（会话数已在登录时计入）。 */
    fun onLogout(uuid: UUID, playtimeSeconds: Long, nowMillis: Long) {
        pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE player_profile SET playtime_secs = playtime_secs + ?, last_seen = ? WHERE uuid = ?"
            ).use { statement ->
                statement.setLong(1, playtimeSeconds.coerceAtLeast(0L))
                statement.setLong(2, nowMillis)
                statement.setObject(3, uuid)
                statement.executeUpdate()
            }
            true
        }
    }

    fun find(uuid: UUID): ProfileRow? = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $profileColumns FROM player_profile WHERE uuid = ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.executeQuery().use { rows -> if (rows.next()) map(rows) else null }
        }
    }

    /**
     * 按名字（含**历史名**）查档案——小号识别的基本入口。
     *
     * <p>用 join 而不是"先查名字表再逐个查档案"：那样 N 个结果会有 N+1 次查询，
     * 而这条查询恰恰会在"一查一大堆"的场景下被用到。</p>
     */
    @JvmOverloads
    fun findByAnyName(name: String, limit: Int = 10): List<ProfileRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT p.* FROM player_profile p " +
                "JOIN player_name n ON n.uuid = p.uuid WHERE n.name = ? " +
                "ORDER BY p.last_seen DESC LIMIT ?"
        ).use { statement ->
            statement.setString(1, name)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<ProfileRow>(4)
                while (rows.next()) out.add(map(rows))
                out
            }
        }
    }

    /** 某个玩家用过的所有名字（最近在先）。 */
    @JvmOverloads
    fun namesOf(uuid: UUID, limit: Int = 32): List<PlayerNameRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT uuid, name, first_seen, last_seen, uses FROM player_name " +
                "WHERE uuid = ? ORDER BY last_seen DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<PlayerNameRow>(4)
                while (rows.next()) {
                    out.add(
                        PlayerNameRow(
                            uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
                            name = rows.getString("name") ?: "?",
                            firstSeen = Sql.millis(rows, "first_seen"),
                            lastSeen = Sql.millis(rows, "last_seen"),
                            uses = rows.getInt("uses")
                        )
                    )
                }
                out
            }
        }
    }

    /** 风险最高的玩家（看板用）。 */
    fun topByRisk(limit: Int = 10): List<ProfileRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $profileColumns FROM player_profile WHERE risk_score > 0 " +
                "ORDER BY risk_score DESC LIMIT ?"
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<ProfileRow>(limit)
                while (rows.next()) out.add(map(rows))
                out
            }
        }
    }

    /** 保存行为画像 blob（与旧 `ProfileSerializer` 的格式逐字兼容）。 */
    fun saveBlob(uuid: UUID, name: String?, blob: String, nowMillis: Long) {
        pool.withTransaction { connection ->
            Sql.upsert(
                connection,
                updateSql = "UPDATE player_profile SET profile_blob = ?, profile_updated = ? WHERE uuid = ?",
                bindUpdate = { statement ->
                    statement.setString(1, blob)
                    statement.setLong(2, nowMillis)
                    statement.setObject(3, uuid)
                },
                insertSql = "INSERT INTO player_profile (uuid, name, first_seen, last_seen, profile_blob, " +
                    "profile_updated) VALUES (?, ?, ?, ?, ?, ?)",
                bindInsert = { statement ->
                    statement.setObject(1, uuid)
                    // 档案行还没建（例如玩家从未触发 login 钩子就退出）：用 'UNKNOWN' 占位，
                    // 否则这次保存会被静默丢弃
                    statement.setString(2, name ?: "UNKNOWN")
                    statement.setLong(3, nowMillis)
                    statement.setLong(4, nowMillis)
                    statement.setString(5, blob)
                    statement.setLong(6, nowMillis)
                }
            )
            true
        }
    }

    /** 读取行为画像 blob；没有则返回 null。 */
    fun loadBlob(uuid: UUID): String? = pool.withConnection { connection ->
        connection.prepareStatement("SELECT profile_blob FROM player_profile WHERE uuid = ?").use { statement ->
            statement.setObject(1, uuid)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString("profile_blob") else null }
        }
    }

    /**
     * 批量更新风险分与窗口计数（风险重算任务的落库端）。
     *
     * <p>窗口计数（近 24h / 7d）**存在档案列里而不是每次查询现算**：
     * 看板一次要读几十个玩家，现算就是几十次带时间范围的聚合；
     * 而且"最近 24 小时"的截止时间要由调用方算好绑定成参数
     * （见 [Sql] 里关于"时间一律 BIGINT"的说明）。</p>
     */
    fun updateRisk(updates: List<RiskUpdate>, nowMillis: Long) {
        if (updates.isEmpty()) return
        pool.withTransaction { connection ->
            // 刻意不动 total_violations：它是"历史上总共多少条"的累计值，
            // 由 [addViolationCounts] 增量维护；重算拿不到全量历史，
            // 用窗口内的条数去覆盖会让这个数字每次重算都变小。
            connection.prepareStatement(
                "UPDATE player_profile SET risk_score = ?, risk_updated = ?, violations_24h = ?, " +
                    "violations_7d = ? WHERE uuid = ?"
            ).use { statement ->
                for (update in updates) {
                    statement.setDouble(1, update.score)
                    statement.setLong(2, nowMillis)
                    statement.setInt(3, update.violations24h)
                    statement.setInt(4, update.violations7d)
                    statement.setObject(5, update.uuid)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            true
        }
    }

    /** 批量累加违规总数（不用重算风险时的轻量路径）。 */
    fun addViolationCounts(counts: Map<UUID, Int>) {
        if (counts.isEmpty()) return
        pool.withTransaction { connection ->
            connection.prepareStatement(
                "UPDATE player_profile SET total_violations = total_violations + ? WHERE uuid = ?"
            ).use { statement ->
                for ((uuid, count) in counts) {
                    statement.setInt(1, count)
                    statement.setObject(2, uuid)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            true
        }
    }

    fun count(): Long = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM player_profile").use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    private fun map(rows: ResultSet): ProfileRow = ProfileRow(
        uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
        name = rows.getString("name") ?: "UNKNOWN",
        firstSeen = Sql.millis(rows, "first_seen"),
        lastSeen = Sql.millis(rows, "last_seen"),
        lastIp = rows.getString("last_ip"),
        sessions = rows.getInt("sessions"),
        playtimeSeconds = rows.getLong("playtime_secs"),
        riskScore = rows.getDouble("risk_score"),
        totalViolations = rows.getInt("total_violations"),
        violations24h = rows.getInt("violations_24h"),
        violations7d = rows.getInt("violations_7d"),
        profileUpdated = Sql.millisOrNull(rows, "profile_updated")
    )

    companion object {
        /** 一行描述（排障日志用）。 */
        fun describe(row: ProfileRow?): String =
            if (row == null) "无档案"
            else row.name + " 会话=" + row.sessions + " 风险=" + RiskScorer.describe(row.riskScore)
    }
}

/** 一次风险重算的结果（[ProfileRepository.updateRisk] 的输入）。 */
class RiskUpdate(
    val uuid: UUID,
    val score: Double,
    val violations24h: Int,
    val violations7d: Int
)
