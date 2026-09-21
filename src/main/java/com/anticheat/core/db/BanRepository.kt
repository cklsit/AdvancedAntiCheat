package com.anticheat.core.db

import java.sql.ResultSet
import java.util.UUID

/**
 * 封禁记录（`ban`）：UUID 封禁、IP 封禁、CIDR 网段封禁；临时与永久；完整状态机。
 *
 * <h3>状态由"列 + 时间"共同决定</h3>
 * `status` 是**人工意图**（active/expired/revoked），`expires_at` 是**时间事实**。
 * 查询一律写成 `status = 'active' AND (expires_at IS NULL OR expires_at > ?)`——
 * 这样即使 [expireOverdue] 还没跑到，刚过期的封禁也不会继续生效。
 * 只信 `status` 的写法会在"过期扫描滞后"时放进一个本该被踢的人。
 *
 * <h3>"同一目标只有一条生效封禁"用可空唯一键表达</h3>
 * `active_key` 只在生效时非空（`uuid:<id>` / `ip:<x>` / `cidr:<x>`），
 * 由唯一约束兜住并发。撤销/过期时把它置回 NULL，于是历史记录可以有多条、
 * 而生效的永远只有一条。这样避免了 PostgreSQL 的部分唯一索引（H2 没有）。
 *
 * <h3>CIDR 覆盖判定在 Kotlin 里做</h3>
 * 生效中的网段封禁是个位数，取出来用 [IpIntel.contains] 逐个判即可，
 * 不必为此引入 `cidr >>= inet`（H2 没有）乃至 `btree_gist` 扩展。</p>
 */
class BanRepository(private val pool: JdbcPool) {

    private val columns = "id, kind, target, name, uuid, scope, reason, operator, issued_at, expires_at, " +
        "status, revoked_by, revoked_at, revoke_reason, server_name, violation_id"

    /**
     * 落一条封禁。
     *
     * <p>同一目标已有生效封禁时会被唯一约束拒绝（[java.sql.SQLException]）。
     * 这里刻意**不提前查一遍**：并发下"先查后插"一定会漏，让约束兜住才是唯一正确的做法，
     * 调用方应把冲突转成"已有封禁"的语义。</p>
     *
     * @return 新记录 id
     */
    fun insert(
        kind: String,
        target: String,
        name: String?,
        uuid: UUID?,
        scope: String,
        reason: String,
        operator: String,
        issuedAt: Long,
        expiresAt: Long?,
        serverName: String?,
        violationId: Long? = null
    ): Long = pool.withConnection { connection ->
        Sql.insertReturningId(
            connection,
            "INSERT INTO ban (kind, target, name, uuid, scope, reason, operator, issued_at, expires_at, " +
                "status, server_name, violation_id, active_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)"
        ) { statement ->
            statement.setString(1, kind)
            statement.setString(2, target)
            Sql.setString(statement, 3, name)
            if (uuid == null) statement.setNull(4, java.sql.Types.OTHER) else statement.setObject(4, uuid)
            statement.setString(5, scope)
            statement.setString(6, reason)
            statement.setString(7, operator)
            statement.setLong(8, issuedAt)
            Sql.setLong(statement, 9, expiresAt)
            Sql.setString(statement, 10, serverName)
            Sql.setLong(statement, 11, violationId)
            statement.setString(12, activeKeyOf(kind, target))
        }
    }

    /**
     * 一次调用同时判定 UUID 封禁与地址封禁（**登录路径上唯一的同步判定**）。
     *
     * <p>三条查询而不是一条 UNION：每条的过滤条件都能吃到索引
     * （`idx_ban_uuid` / `idx_ban_target`），而"生效中的 CIDR 封禁"本来就只有几条，
     * 取回来在 Kotlin 里判覆盖比写方言相关的 SQL 运算符更可靠。</p>
     *
     * @param ip 可为 null（拿不到地址时只判 UUID）
     * @return 命中的封禁；没有则 null
     */
    fun findEffective(uuid: UUID, ip: String?, nowMillis: Long): BanRow? {
        findEffectiveByUuid(uuid, nowMillis)?.let { return it }

        val normalized = IpIntel.normalize(ip)
        if (normalized != null) {
            pool.withConnection { connection ->
                connection.prepareStatement(
                    "SELECT $columns FROM ban WHERE status = 'active' AND kind = 'ip' AND target = ? " +
                        "AND (expires_at IS NULL OR expires_at > ?) ORDER BY issued_at DESC LIMIT 1"
                ).use { statement ->
                    statement.setString(1, normalized)
                    statement.setLong(2, nowMillis)
                    statement.executeQuery().use { rows -> if (rows.next()) map(rows) else null }
                }
            }?.let { return it }

            // 网段封禁：数量少，取回本地判覆盖（最长前缀优先——先按 target 长度降序，
            // 更具体的网段先判，避免 /8 把 /24 的例外盖掉）
            val cidrBans = pool.withConnection { connection ->
                connection.prepareStatement(
                    "SELECT $columns FROM ban WHERE status = 'active' AND kind = 'cidr' " +
                        "AND (expires_at IS NULL OR expires_at > ?) ORDER BY issued_at DESC"
                ).use { statement ->
                    statement.setLong(1, nowMillis)
                    statement.executeQuery().use { rows -> collect(rows) }
                }
            }
            for (ban in cidrBans.sortedByDescending { it.target.length }) {
                if (IpIntel.contains(ban.target, normalized)) return ban
            }
        }
        return null
    }

    /** 某个 UUID 的生效封禁。 */
    fun findEffectiveByUuid(uuid: UUID, nowMillis: Long): BanRow? = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $columns FROM ban WHERE status = 'active' AND kind = 'uuid' AND uuid = ? " +
                "AND (expires_at IS NULL OR expires_at > ?) ORDER BY issued_at DESC LIMIT 1"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setLong(2, nowMillis)
            statement.executeQuery().use { rows -> if (rows.next()) map(rows) else null }
        }
    }

    /** 生效封禁列表（最近在先）。 */
    fun listEffective(nowMillis: Long, limit: Int = 200): List<BanRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $columns FROM ban WHERE status = 'active' AND (expires_at IS NULL OR expires_at > ?) " +
                "ORDER BY issued_at DESC LIMIT ?"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows -> collect(rows) }
        }
    }

    /** 全部分页（含已过期/已撤销）。 */
    fun listPage(offset: Int, limit: Int): List<BanRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $columns FROM ban ORDER BY issued_at DESC LIMIT ? OFFSET ?"
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset.coerceAtLeast(0))
            statement.executeQuery().use { rows -> collect(rows) }
        }
    }

    fun countEffective(nowMillis: Long): Long = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM ban WHERE status = 'active' AND (expires_at IS NULL OR expires_at > ?)"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    fun countAll(): Long = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM ban").use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    /** 撤销某个 UUID 的生效封禁。 */
    fun revokeByUuid(uuid: UUID, by: String, reason: String?, nowMillis: Long): Int =
        revokeByTarget(BanKind.UUID, uuid.toString(), by, reason, nowMillis)

    /** 撤销某个地址或网段的生效封禁。 */
    fun revokeByIp(target: String, by: String, reason: String?, nowMillis: Long): Int {
        val normalized = IpIntel.normalize(target) ?: target
        var affected = revokeByTarget(BanKind.IP, normalized, by, reason, nowMillis)
        if (affected == 0) affected = revokeByTarget(BanKind.CIDR, normalized, by, reason, nowMillis)
        return affected
    }

    /** 按 id 撤销。 */
    fun revokeById(id: Long, by: String, reason: String?, nowMillis: Long): Int =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE ban SET status = 'revoked', active_key = NULL, revoked_by = ?, revoked_at = ?, " +
                    "revoke_reason = ? WHERE status = 'active' AND id = ?"
            ).use { statement ->
                statement.setString(1, by)
                statement.setLong(2, nowMillis)
                Sql.setString(statement, 3, reason)
                statement.setLong(4, id)
                statement.executeUpdate()
            }
        }

    private fun revokeByTarget(kind: String, target: String, by: String, reason: String?, nowMillis: Long): Int =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE ban SET status = 'revoked', active_key = NULL, revoked_by = ?, revoked_at = ?, " +
                    "revoke_reason = ? WHERE status = 'active' AND kind = ? AND target = ?"
            ).use { statement ->
                statement.setString(1, by)
                statement.setLong(2, nowMillis)
                Sql.setString(statement, 3, reason)
                statement.setString(4, kind)
                statement.setString(5, target)
                statement.executeUpdate()
            }
        }

    /**
     * 把已到期的封禁标成 `expired` 并释放唯一键。
     *
     * <p>这只让状态列跟上现实（判定本来就不受影响，见类注释）。
     * 定期跑它的意义是：让看板不把过期封禁算成生效中的，
     * 并让 `active_key` 腾出来给新的封禁用——**不释放的话，给同一个人再次封禁会撞唯一键**。</p>
     */
    fun expireOverdue(nowMillis: Long): Int = pool.withConnection { connection ->
        connection.prepareStatement(
            "UPDATE ban SET status = 'expired', active_key = NULL " +
                "WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at <= ?"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.executeUpdate()
        }
    }

    private fun collect(rows: ResultSet): List<BanRow> {
        val out = ArrayList<BanRow>(8)
        while (rows.next()) out.add(map(rows))
        return out
    }

    private fun map(rows: ResultSet): BanRow = BanRow(
        id = rows.getLong("id"),
        kind = rows.getString("kind") ?: BanKind.UUID,
        target = rows.getString("target") ?: "?",
        name = rows.getString("name"),
        uuid = Sql.uuid(rows, "uuid"),
        scope = rows.getString("scope") ?: BanScope.TEMP,
        reason = rows.getString("reason") ?: "",
        operator = rows.getString("operator") ?: "console",
        issuedAt = Sql.millis(rows, "issued_at"),
        expiresAt = Sql.millisOrNull(rows, "expires_at"),
        status = rows.getString("status") ?: BanStatus.ACTIVE,
        revokedBy = rows.getString("revoked_by"),
        revokedAt = Sql.millisOrNull(rows, "revoked_at"),
        revokeReason = rows.getString("revoke_reason"),
        serverName = rows.getString("server_name"),
        violationId = Sql.longOrNull(rows, "violation_id")
    )

    companion object {

        /** 生效唯一键：`kind:target`。撤销/过期时置 NULL 释放。 */
        fun activeKeyOf(kind: String, target: String): String = kind + ":" + target

        /** 一条可读的封禁描述（告警/日志用）。 */
        fun describe(row: BanRow): String {
            val until = if (row.expiresAt == null) {
                "永久"
            } else {
                "至 " + java.time.Instant.ofEpochMilli(row.expiresAt).toString()
            }
            return row.kind + " " + row.target + " / " + row.scope + " / " + until + " / " + row.reason
        }
    }
}
