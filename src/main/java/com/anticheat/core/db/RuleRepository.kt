package com.anticheat.core.db

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * 规则与白名单（`check_rule` / `punishment_ladder` / `whitelist_entry` / `check_stat`）。
 *
 * <h3>`check_rule` 的"谁说了算"</h3>
 * 这张表同时被两边写：插件启动时登记**代码里的事实**（描述、是否实验性，
 * 以及首次登记时的默认阈值），管理员则可能直接改库里的策略字段
 * （`enabled` / `decay` / `setback` / `thresholds`）。冲突时分开处理：
 * 代码事实覆盖，策略字段**不动**（库里是权威）。
 *
 * <p>反过来做（启动时用 config.yml 覆盖库）会让管理员调好的阈值在下次重启时被
 * 悄悄改回去——"改完没生效也没报错"比直接报错难查得多。而如果库里永远权威，
 * 第一次部署时又没人往里写策略值，所以**首次 INSERT 必须带上 config.yml 的值**。</p>
 */
class RuleRepository(private val pool: JdbcPool) {

    /** 一个检测的登记信息（来自代码与 config.yml）。 */
    class CheckSeed(
        val checkName: String,
        val enabled: Boolean,
        val decay: Double?,
        val setback: Double?,
        val experimental: Boolean,
        val description: String?,
        /** 该检测的专属阈值，原样写进 `thresholds`（JSON 文本）。 */
        val thresholds: Map<String, Any?>
    )

    /**
     * 登记/更新检测规则。
     *
     * @param by 变更来源（写进 `updated_by`，例如 `"startup"`）
     * @return 受影响的检测名
     */
    fun syncCheckRules(seeds: List<CheckSeed>, by: String, nowMillis: Long): List<String> {
        if (seeds.isEmpty()) return emptyList()
        val touched = ArrayList<String>(seeds.size)
        pool.withTransaction { connection ->
            connection.prepareStatement(
                "UPDATE check_rule SET experimental = ?, description = ?, updated_at = ?, updated_by = ? " +
                    "WHERE check_name = ?"
            ).use { update ->
                connection.prepareStatement(
                    "INSERT INTO check_rule (check_name, enabled, decay, setback, experimental, description, " +
                        "thresholds, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { insert ->
                    for (seed in seeds) {
                        update.setBoolean(1, seed.experimental)
                        Sql.setString(update, 2, seed.description)
                        update.setLong(3, nowMillis)
                        update.setString(4, by)
                        update.setString(5, seed.checkName)
                        if (update.executeUpdate() > 0) {
                            touched.add(seed.checkName)
                            continue
                        }
                        insert.setString(1, seed.checkName)
                        insert.setBoolean(2, seed.enabled)
                        Sql.setDouble(insert, 3, seed.decay)
                        Sql.setDouble(insert, 4, seed.setback)
                        insert.setBoolean(5, seed.experimental)
                        Sql.setString(insert, 6, seed.description)
                        insert.setString(7, toJson(seed.thresholds))
                        insert.setLong(8, nowMillis)
                        insert.setString(9, by)
                        try {
                            insert.executeUpdate()
                            touched.add(seed.checkName)
                        } catch (conflict: java.sql.SQLException) {
                            // 并发下另一个线程刚插入：本次的"代码事实"已经由它写进去了，跳过即可
                        }
                    }
                }
            }
            true
        }
        return touched
    }

    /** 读取全部检测规则（用于把库里的策略回灌到运行时）。 */
    fun loadCheckRules(): Map<String, CheckRuleRow> = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT check_name, enabled, decay, setback, experimental, description, thresholds, " +
                    "updated_at, updated_by FROM check_rule"
            ).use { rows ->
                val out = HashMap<String, CheckRuleRow>(32)
                while (rows.next()) {
                    val name = rows.getString("check_name") ?: continue
                    out[name] = CheckRuleRow(
                        checkName = name,
                        enabled = rows.getBoolean("enabled"),
                        decay = Sql.doubleOrNull(rows, "decay"),
                        setback = Sql.doubleOrNull(rows, "setback"),
                        experimental = rows.getBoolean("experimental"),
                        description = rows.getString("description"),
                        thresholds = fromJson(rows.getString("thresholds")),
                        updatedAt = Sql.millis(rows, "updated_at"),
                        updatedBy = rows.getString("updated_by")
                    )
                }
                out
            }
        }
    }

    /** 只改某个检测的开关（管理员最常用的操作）。 */
    fun setCheckEnabled(checkName: String, enabled: Boolean, by: String, nowMillis: Long): Int =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE check_rule SET enabled = ?, updated_at = ?, updated_by = ? WHERE check_name = ?"
            ).use { statement ->
                statement.setBoolean(1, enabled)
                statement.setLong(2, nowMillis)
                statement.setString(3, by)
                statement.setString(4, checkName)
                statement.executeUpdate()
            }
        }

    /** 覆盖某个检测的专属阈值（管理员调参入口）。 */
    fun setCheckThresholds(checkName: String, thresholds: Map<String, Any?>, by: String, nowMillis: Long): Int =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE check_rule SET thresholds = ?, updated_at = ?, updated_by = ? WHERE check_name = ?"
            ).use { statement ->
                statement.setString(1, toJson(thresholds))
                statement.setLong(2, nowMillis)
                statement.setString(3, by)
                statement.setString(4, checkName)
                statement.executeUpdate()
            }
        }

    // ------------------------------------------------------------------ 惩罚阶梯

    fun loadLadder(): List<LadderStep> = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT step, min_vl, action, duration, reason FROM punishment_ladder ORDER BY step"
            ).use { rows ->
                val out = ArrayList<LadderStep>(8)
                while (rows.next()) {
                    out.add(
                        LadderStep(
                            step = rows.getInt("step"),
                            minVl = rows.getDouble("min_vl"),
                            action = rows.getString("action") ?: "alert",
                            duration = rows.getString("duration"),
                            reason = rows.getString("reason")
                        )
                    )
                }
                out
            }
        }
    }

    /** 整体替换惩罚阶梯（先清后插，在一个事务里，避免出现"半套阶梯"）。 */
    fun replaceLadder(steps: List<LadderStep>, nowMillis: Long) {
        pool.withTransaction { connection ->
            connection.createStatement().use { it.execute("DELETE FROM punishment_ladder") }
            connection.prepareStatement(
                "INSERT INTO punishment_ladder (step, min_vl, action, duration, reason, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                for (step in steps) {
                    statement.setInt(1, step.step)
                    statement.setDouble(2, step.minVl)
                    statement.setString(3, step.action)
                    Sql.setString(statement, 4, step.duration)
                    Sql.setString(statement, 5, step.reason)
                    statement.setLong(6, nowMillis)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            true
        }
    }

    // ------------------------------------------------------------------ 白名单

    fun addWhitelist(
        uuid: UUID?,
        name: String?,
        reason: String?,
        addedBy: String,
        nowMillis: Long,
        expiresAt: Long?
    ): Long = pool.withConnection { connection ->
        Sql.insertReturningId(
            connection,
            "INSERT INTO whitelist_entry (uuid, name, reason, added_by, added_at, expires_at, active_key) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        ) { statement ->
            if (uuid == null) statement.setNull(1, java.sql.Types.OTHER) else statement.setObject(1, uuid)
            Sql.setString(statement, 2, name)
            Sql.setString(statement, 3, reason)
            statement.setString(4, addedBy)
            statement.setLong(5, nowMillis)
            Sql.setLong(statement, 6, expiresAt)
            statement.setString(
                7,
                if (uuid != null) "uuid:" + uuid else "name:" + (name ?: "").lowercase()
            )
        }
    }

    /**
     * 是否在白名单里（含按名字匹配，用于"还没进服就先加白"）。
     *
     * <p>过期的条目不生效——白名单也要有到期时间，否则一次临时放行会变成永久豁免。</p>
     */
    fun isWhitelisted(uuid: UUID, name: String?, nowMillis: Long): Boolean = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM whitelist_entry WHERE (expires_at IS NULL OR expires_at > ?) " +
                "AND (uuid = ? OR lower(name) = lower(?)) LIMIT 1"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.setObject(2, uuid)
            statement.setString(3, name ?: "")
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    /**
     * 补齐一条白名单（仅当目标下没有**仍生效**的条目时才插入）。
     *
     * <p>为什么不直接调 [addWhitelist]：那会在同一线程里**嵌套借连接**。
     * 池子是有上限的，配置成小池时嵌套借连接会在等待里死锁（等的就是自己持有的那个）。
     * 因此插入写在同一个连接上完成。</p>
     *
     * <p>已存在时不更新 reason、不延长 expires_at：声明式配置的语义是“补齐缺失项”，
     * 不是“以 config 覆盖库里的人工修改”。</p>
     *
     * @return true = 本次真的插入了
     */
    fun ensureWhitelist(
        uuid: UUID?,
        name: String?,
        reason: String?,
        addedBy: String,
        nowMillis: Long,
        expiresAt: Long?
    ): Boolean = pool.withConnection { connection ->
        var exists = false
        connection.prepareStatement(
            "SELECT id FROM whitelist_entry WHERE (expires_at IS NULL OR expires_at > ?) " +
                "AND ((uuid IS NOT NULL AND uuid = ?) OR (name IS NOT NULL AND lower(name) = lower(?))) LIMIT 1"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            if (uuid == null) statement.setNull(2, java.sql.Types.OTHER) else statement.setObject(2, uuid)
            statement.setString(3, name ?: "")
            statement.executeQuery().use { rows -> exists = rows.next() }
        }
        if (exists) {
            false
        } else {
            Sql.insertReturningId(
                connection,
                "INSERT INTO whitelist_entry (uuid, name, reason, added_by, added_at, expires_at, active_key) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
            ) { statement ->
                if (uuid == null) statement.setNull(1, java.sql.Types.OTHER) else statement.setObject(1, uuid)
                Sql.setString(statement, 2, name)
                Sql.setString(statement, 3, reason)
                statement.setString(4, addedBy)
                statement.setLong(5, nowMillis)
                Sql.setLong(statement, 6, expiresAt)
                statement.setString(
                    7,
                    if (uuid != null) "uuid:" + uuid else "name:" + (name ?: "").lowercase()
                )
            }
            true
        }
    }

    /**
     * 释放**已过期**条目占的唯一键占位（`active_key = NULL`）。
     *
     * <h3>为什么退役不能只靠判定时过滤</h3>
     * `whitelist_entry` 用**可空唯一键** `active_key` 表达“同一目标只能有一条生效”。
     * 但过期只是“判定时不生效”：行还在、键还占着，于是同一目标**再也加不回来**
     * （插入直接违反唯一约束）——这是本轮被测试真的撞出来的。
     *
     * <p>释放成 NULL 后，多条历史行可以共存（两个引擎的唯一索引都允许多个 NULL），
     * 而“最多一条生效”仍然由唯一键保证。</p>
     *
     * @return 本次释放的条数（写进维护日志）
     */
    fun expireWhitelist(nowMillis: Long): Int = pool.withConnection { connection ->
        connection.prepareStatement(
            "UPDATE whitelist_entry SET active_key = NULL " +
                "WHERE active_key IS NOT NULL AND expires_at IS NOT NULL AND expires_at <= ?"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.executeUpdate()
        }
    }

    /** 当前**仍生效**的白名单条数（写进日志，让“库里到底有没有数据”可见）。 */
    fun countWhitelist(nowMillis: Long): Int = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM whitelist_entry WHERE (expires_at IS NULL OR expires_at > ?)"
        ).use { statement ->
            statement.setLong(1, nowMillis)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }

    /** 移除白名单条目（按 uuid 或名字）。删行即释放唯一键，之后可以重新加白。 */
    fun removeWhitelist(target: String): Int = pool.withConnection { connection ->
        val uuid = Sql.uuidOrNull(target)
        connection.prepareStatement(
            "DELETE FROM whitelist_entry WHERE (uuid IS NOT NULL AND uuid = ?) OR name = ?"
        ).use { statement ->
            if (uuid == null) statement.setNull(1, java.sql.Types.OTHER) else statement.setObject(1, uuid)
            statement.setString(2, target)
            statement.executeUpdate()
        }
    }

    fun listWhitelist(limit: Int = 200): List<WhitelistRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, uuid, name, reason, added_by, added_at, expires_at FROM whitelist_entry " +
                "ORDER BY added_at DESC LIMIT ?"
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<WhitelistRow>(16)
                while (rows.next()) {
                    out.add(
                        WhitelistRow(
                            id = rows.getLong("id"),
                            uuid = Sql.uuid(rows, "uuid"),
                            name = rows.getString("name"),
                            reason = rows.getString("reason"),
                            addedBy = rows.getString("added_by"),
                            addedAt = Sql.millis(rows, "added_at"),
                            expiresAt = Sql.millisOrNull(rows, "expires_at")
                        )
                    )
                }
                out
            }
        }
    }

    // ------------------------------------------------------------------ 检查命中率

    /**
     * 累加检查命中率统计。
     *
     * <p>先 UPDATE 再 INSERT（可移植 upsert）：多个子服/多个线程可能同时写同一个桶，
     * 累加必须由数据库原子完成。`players` 取"较大值"而不是相加——它是
     * "桶内出现过多少个玩家"，相加会变成人次，口径就错了。</p>
     */
    fun accumulateCheckStats(bucketMillis: Long, deltas: List<CheckStatDelta>): Int {
        if (deltas.isEmpty()) return 0
        return pool.withTransaction { connection ->
            var affected = 0
            connection.prepareStatement(
                "UPDATE check_stat SET evaluations = evaluations + ?, flags = flags + ?, vl_sum = vl_sum + ?, " +
                    "players = CASE WHEN players > ? THEN players ELSE ? END WHERE bucket = ? AND check_name = ?"
            ).use { update ->
                connection.prepareStatement(
                    "INSERT INTO check_stat (bucket, check_name, evaluations, flags, vl_sum, players) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                ).use { insert ->
                    for (delta in deltas) {
                        update.setLong(1, delta.evaluations)
                        update.setLong(2, delta.flags)
                        update.setDouble(3, delta.vlSum)
                        update.setInt(4, delta.players)
                        update.setInt(5, delta.players)
                        update.setLong(6, bucketMillis)
                        update.setString(7, delta.checkName)
                        if (update.executeUpdate() > 0) {
                            affected++
                            continue
                        }
                        insert.setLong(1, bucketMillis)
                        insert.setString(2, delta.checkName)
                        insert.setLong(3, delta.evaluations)
                        insert.setLong(4, delta.flags)
                        insert.setDouble(5, delta.vlSum)
                        insert.setInt(6, delta.players)
                        try {
                            insert.executeUpdate()
                            affected++
                        } catch (conflict: java.sql.SQLException) {
                            // 并发插入撞主键：交给下一轮累加（丢了这一桶的增量，
                            // 但比把异常抛给刷写线程、导致后续批次全部失败要好）
                        }
                    }
                }
            }
            affected
        }
    }

    /** 按检测汇总命中率（`v_check_hit_rate` 视图）。 */
    fun checkHitRates(): List<HitRateRow> = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT check_name, evaluations, flags, hit_rate FROM v_check_hit_rate ORDER BY evaluations DESC"
            ).use { rows ->
                val out = ArrayList<HitRateRow>(32)
                while (rows.next()) {
                    out.add(
                        HitRateRow(
                            checkName = rows.getString("check_name") ?: "",
                            evaluations = rows.getLong("evaluations"),
                            flags = rows.getLong("flags"),
                            hitRate = Sql.doubleOrNull(rows, "hit_rate")
                        )
                    )
                }
                out
            }
        }
    }

    /** 某个检测的历史统计（趋势）。 */
    fun checkStatsSince(checkName: String, sinceMillis: Long): List<CheckStatRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT bucket, check_name, evaluations, flags, vl_sum, players FROM check_stat " +
                "WHERE check_name = ? AND bucket >= ? ORDER BY bucket"
        ).use { statement ->
            statement.setString(1, checkName)
            statement.setLong(2, Sql.dayBucket(sinceMillis))
            statement.executeQuery().use { rows ->
                val out = ArrayList<CheckStatRow>(48)
                while (rows.next()) {
                    out.add(
                        CheckStatRow(
                            bucket = rows.getLong("bucket"),
                            checkName = rows.getString("check_name") ?: "",
                            evaluations = rows.getLong("evaluations"),
                            flags = rows.getLong("flags"),
                            vlSum = rows.getDouble("vl_sum"),
                            players = rows.getInt("players")
                        )
                    )
                }
                out
            }
        }
    }

    internal fun toJson(values: Map<String, Any?>): String {
        val root = JsonObject()
        for ((key, value) in values) {
            when (value) {
                null -> Unit
                is Number -> root.addProperty(key, value)
                is Boolean -> root.addProperty(key, value)
                else -> root.addProperty(key, value.toString())
            }
        }
        return root.toString()
    }

    internal fun fromJson(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val parsed = JsonParser.parseString(text)
            if (!parsed.isJsonObject) return emptyMap()
            val out = HashMap<String, String>(8)
            for ((key, value) in parsed.asJsonObject.entrySet()) {
                val raw = value.toString()
                out[key] = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw.substring(1, raw.length - 1)
                } else {
                    raw
                }
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }
}
