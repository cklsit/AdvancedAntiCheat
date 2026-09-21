package com.anticheat.core.db

import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * 审计日志（`audit_log`）。
 *
 * <p>字段与旧 `AuditRecord` 一一对应（时间戳仍是 epoch 毫秒），查询语义也完全照搬
 * 旧实现：`type`/`result` 精确匹配、`keyword` 在 `operator`/`target`/`detail`
 * 三列上 LIKE 模糊、起点/终点是闭区间、`ORDER BY ts DESC` + `LIMIT/OFFSET` 分页。
 *
 * <p>**为什么先冻结语义再迁移**：审计是"事后追责"用的，口径一变就意味着
 * 老记录和新记录不可比。所以这次只换存储，不动语义。列名用 `ts` 而不是
 * `timestamp`：后者在 H2 里是数据类型关键字，当列名有兼容风险。</p>
 */
class AuditRepository(private val pool: JdbcPool) {

    fun insert(row: AuditRow): Long = pool.withConnection { connection ->
        Sql.insertReturningId(
            connection,
            "INSERT INTO audit_log (ts, operator, operator_role, type, target, ip, result, detail) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ) { statement ->
            statement.setLong(1, row.timestamp)
            Sql.setString(statement, 2, row.operator)
            statement.setInt(3, row.operatorRole)
            Sql.setString(statement, 4, row.type)
            Sql.setString(statement, 5, row.target)
            Sql.setString(statement, 6, row.ip)
            Sql.setString(statement, 7, row.result)
            Sql.setString(statement, 8, row.detail)
        }
    }

    /** 按条件分页查询（时间倒序）。 */
    fun query(filter: AuditFilter): List<AuditRow> {
        val normalized = filter.normalized()
        val (where, params) = AuditWhere.build(normalized)
        return pool.withConnection { connection ->
            connection.prepareStatement(
                "SELECT id, ts, operator, operator_role, type, target, ip, result, detail " +
                    "FROM audit_log " + where + " ORDER BY ts DESC LIMIT ? OFFSET ?"
            ).use { statement ->
                var index = bind(statement, params)
                statement.setInt(index++, normalized.pageSize)
                statement.setInt(index, normalized.offset)
                statement.executeQuery().use { rows -> collect(rows) }
            }
        }
    }

    /** 同条件的总数（分页用）。 */
    fun count(filter: AuditFilter): Long {
        val normalized = filter.normalized()
        val (where, params) = AuditWhere.build(normalized)
        return pool.withConnection { connection ->
            connection.prepareStatement("SELECT count(*) FROM audit_log " + where).use { statement ->
                bind(statement, params)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
        }
    }

    fun countAll(): Long = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM audit_log").use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    private fun bind(statement: PreparedStatement, params: List<Any?>): Int {
        params.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setNull(index + 1, java.sql.Types.VARCHAR)
                is Long -> statement.setLong(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                else -> statement.setString(index + 1, value.toString())
            }
        }
        return params.size + 1
    }

    private fun collect(rows: ResultSet): List<AuditRow> {
        val out = ArrayList<AuditRow>(16)
        while (rows.next()) {
            out.add(
                AuditRow(
                    id = rows.getLong("id"),
                    timestamp = rows.getLong("ts"),
                    operator = rows.getString("operator"),
                    operatorRole = rows.getInt("operator_role"),
                    type = rows.getString("type"),
                    target = rows.getString("target"),
                    ip = rows.getString("ip"),
                    result = rows.getString("result"),
                    detail = rows.getString("detail")
                )
            )
        }
        return out
    }
}

/**
 * 审计查询条件的 SQL 拼装（纯函数，可离线单测）。
 *
 * <p>抽出来单独测的理由：这是**唯一会拼动态 WHERE 的地方**，而拼错的表现是
 * "查出来的记录少了/多了"——不会报错。参数个数与占位符不匹配这类错误在运行时才炸，
 * 且只在特定筛选组合下出现，靠手工点界面几乎测不全。</p>
 */
object AuditWhere {

    /** 无条件时的 WHERE 子句（带 1=1 便于拼接）。 */
    const val EMPTY = "WHERE 1 = 1"

    /**
     * @return `(where 子句, 参数列表)`；参数顺序与占位符顺序严格一致
     */
    @JvmStatic
    fun build(filter: AuditFilter): Pair<String, List<Any?>> {
        val conditions = ArrayList<String>(5)
        val params = ArrayList<Any?>(6)

        if (!filter.type.isNullOrBlank()) {
            conditions.add("type = ?")
            params.add(filter.type)
        }
        if (!filter.result.isNullOrBlank()) {
            conditions.add("result = ?")
            params.add(filter.result)
        }
        if (!filter.keyword.isNullOrBlank()) {
            conditions.add("(operator LIKE ? OR target LIKE ? OR detail LIKE ?)")
            val like = "%" + filter.keyword + "%"
            params.add(like)
            params.add(like)
            params.add(like)
        }
        if (filter.startTime != null) {
            conditions.add("ts >= ?")
            params.add(filter.startTime)
        }
        if (filter.endTime != null) {
            conditions.add("ts <= ?")
            params.add(filter.endTime)
        }

        if (conditions.isEmpty()) return EMPTY to emptyList()
        return "WHERE " + conditions.joinToString(" AND ") to params
    }
}
