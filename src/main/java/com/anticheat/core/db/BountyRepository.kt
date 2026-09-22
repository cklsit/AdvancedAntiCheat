package com.anticheat.core.db

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * 赏金沙箱的持久化（钱包 / 案例 / 每日时长 / 商城 / 人类基线）。
 *
 * <p>遵守本包的 SQL 纪律（见 [Sql] 类注释）：时间一律 BIGINT 毫秒 + 写入时算 `day_bucket`、
 * 自增用 IDENTITY、不写 `ON CONFLICT`/`RETURNING`、"一次性事物只能发生一次"用可空唯一键。</p>
 *
 * <h3>为什么代币的扣减必须和记录写在同一个事务里</h3>
 * 兑换是"扣钱"和"发东西"两件事。分开写就会留下两种坏结果：先扣钱后记录失败
 * （玩家白扣）或先记录后扣钱失败（白拿）。所以 [purchase] 把"查是否已购 → 扣款 →
 * 写记录"三步放进同一个 [JdbcPool.withTransaction]：任一步失败整体回滚。
 */
class BountyRepository(private val pool: JdbcPool) {

    // ------------------------------------------------------------------ 钱包

    fun findWallet(uuid: UUID): BountyWalletRow? = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT uuid, name, tokens, earned, spent, updated_at FROM bounty_wallet WHERE uuid = ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.executeQuery().use { rows -> if (rows.next()) readWallet(rows) else null }
        }
    }

    /**
     * 发放代币。
     *
     * @param amount 必须为正；负数请走 [spend]，否则 `earned` 与 `spent` 的账目会对不上
     * @return 发放后的余额
     */
    fun addTokens(uuid: UUID, name: String, amount: Long, nowMillis: Long): Long {
        if (amount <= 0L) return findWallet(uuid)?.tokens ?: 0L
        return pool.withTransaction { connection ->
            val updated = connection.prepareStatement(
                "UPDATE bounty_wallet SET name = ?, tokens = tokens + ?, earned = earned + ?, updated_at = ? " +
                    "WHERE uuid = ?"
            ).use { statement ->
                statement.setString(1, name)
                statement.setLong(2, amount)
                statement.setLong(3, amount)
                statement.setLong(4, nowMillis)
                statement.setObject(5, uuid)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    "INSERT INTO bounty_wallet (uuid, name, tokens, earned, spent, updated_at) " +
                        "VALUES (?, ?, ?, ?, 0, ?)"
                ).use { statement ->
                    statement.setObject(1, uuid)
                    statement.setString(2, name)
                    statement.setLong(3, amount)
                    statement.setLong(4, amount)
                    statement.setLong(5, nowMillis)
                    statement.executeUpdate()
                }
                amount
            } else {
                balance(connection, uuid)
            }
        }
    }

    /**
     * 扣减代币（条件更新：余额不足则一行都不改）。
     *
     * <p>用 `WHERE tokens >= ?` 而不是"先查余额再扣"：后者在并发兑换下会双重扣穿，
     * 而条件更新让数据库自己保证原子性（这也是唯一不需要额外锁的写法）。</p>
     *
     * @return true = 扣款成功
     */
    fun spend(uuid: UUID, amount: Long, nowMillis: Long): Boolean {
        if (amount <= 0L) return true
        return pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE bounty_wallet SET tokens = tokens - ?, spent = spent + ?, updated_at = ? " +
                    "WHERE uuid = ? AND tokens >= ?"
            ).use { statement ->
                statement.setLong(1, amount)
                statement.setLong(2, amount)
                statement.setLong(3, nowMillis)
                statement.setObject(4, uuid)
                statement.setLong(5, amount)
                statement.executeUpdate() > 0
            }
        }
    }

    /** 排行榜：按累计获得排序（不是余额——余额会被消费掉，看不出贡献）。 */
    fun topWallets(limit: Int): List<BountyRankRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT uuid, name, tokens, earned FROM bounty_wallet ORDER BY earned DESC, tokens DESC LIMIT ?"
        ).use { statement ->
            statement.setInt(1, limit.coerceIn(1, 200))
            statement.executeQuery().use { rows ->
                val out = ArrayList<BountyRankRow>(16)
                while (rows.next()) {
                    out.add(
                        BountyRankRow(
                            uuid = Sql.uuid(rows, "uuid") ?: continue,
                            name = rows.getString("name"),
                            tokens = rows.getLong("tokens"),
                            earned = rows.getLong("earned")
                        )
                    )
                }
                out
            }
        }
    }

    // ------------------------------------------------------------------ 案例

    fun insertCase(
        uuid: UUID,
        name: String,
        task: String,
        verdict: String,
        confidence: String,
        tokens: Long,
        flags: Int,
        maxVl: Double,
        anomalyScore: Double,
        baselineReady: Boolean,
        samples: Int,
        reason: String?,
        summary: String?,
        evidencePath: String?,
        status: String,
        nowMillis: Long
    ): Long = pool.withConnection { connection ->
        Sql.insertReturningId(
            connection,
            "INSERT INTO bounty_case (uuid, name, task, verdict, confidence, tokens, flags, max_vl, " +
                "anomaly_score, baseline_ready, samples, reason, summary, evidence_path, status, " +
                "created_at, day_bucket) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ) { statement ->
            statement.setObject(1, uuid)
            statement.setString(2, name)
            statement.setString(3, task)
            statement.setString(4, verdict)
            statement.setString(5, confidence)
            statement.setLong(6, tokens)
            statement.setInt(7, flags)
            statement.setDouble(8, maxVl)
            statement.setDouble(9, anomalyScore)
            statement.setBoolean(10, baselineReady)
            statement.setInt(11, samples)
            Sql.setString(statement, 12, reason)
            Sql.setString(statement, 13, summary)
            Sql.setString(statement, 14, evidencePath)
            statement.setString(15, status)
            statement.setLong(16, nowMillis)
            statement.setLong(17, Sql.dayBucket(nowMillis))
        }
    }

    fun findCase(id: Long): BountyCaseRow? = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $CASE_COLUMNS FROM bounty_case WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) readCase(rows) else null }
        }
    }

    /** 按状态取案例（`pending` = 待人工复核队列）。 */
    fun listCases(status: String, limit: Int): List<BountyCaseRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $CASE_COLUMNS FROM bounty_case WHERE status = ? ORDER BY created_at DESC LIMIT ?"
        ).use { statement ->
            statement.setString(1, status)
            statement.setInt(2, limit.coerceIn(1, 500))
            statement.executeQuery().use { rows -> collectCases(rows) }
        }
    }

    fun listCasesOf(uuid: UUID, limit: Int): List<BountyCaseRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT $CASE_COLUMNS FROM bounty_case WHERE uuid = ? ORDER BY created_at DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setInt(2, limit.coerceIn(1, 200))
            statement.executeQuery().use { rows -> collectCases(rows) }
        }
    }

    fun countCases(status: String): Long = pool.withConnection { connection ->
        connection.prepareStatement("SELECT count(*) FROM bounty_case WHERE status = ?").use { statement ->
            statement.setString(1, status)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    /**
     * 审核一条案例。
     *
     * <p>`AND status = 'pending'` 让审核**幂等**：同一条案例被两个管理员同时点
     * "采纳"时，只有第一个会改到行，第二个得到 0——避免同一条特征被采纳两次。</p>
     *
     * @return 实际改动的行数（0 = 案例不存在或已被审核过）
     */
    fun reviewCase(id: Long, status: String, by: String, nowMillis: Long): Int =
        pool.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE bounty_case SET status = ?, reviewed_by = ?, reviewed_at = ? " +
                    "WHERE id = ? AND status = ?"
            ).use { statement ->
                statement.setString(1, status)
                statement.setString(2, by)
                statement.setLong(3, nowMillis)
                statement.setLong(4, id)
                statement.setString(5, STATUS_PENDING)
                statement.executeUpdate()
            }
        }

    // ------------------------------------------------------------------ 每日沙箱时长

    /** 某玩家某一天已用的沙箱秒数。 */
    fun secondsUsed(uuid: UUID, dayMillis: Long): Long = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT seconds FROM bounty_daily WHERE uuid = ? AND day_bucket = ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setLong(2, dayMillis)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    fun addSessionSeconds(uuid: UUID, dayMillis: Long, seconds: Long, nowMillis: Long) {
        if (seconds <= 0L) return
        pool.withTransaction { connection ->
            Sql.upsert(
                connection,
                updateSql = "UPDATE bounty_daily SET seconds = seconds + ?, sessions = sessions + 1, " +
                    "updated_at = ? WHERE uuid = ? AND day_bucket = ?",
                bindUpdate = { statement ->
                    statement.setLong(1, seconds)
                    statement.setLong(2, nowMillis)
                    statement.setObject(3, uuid)
                    statement.setLong(4, dayMillis)
                },
                insertSql = "INSERT INTO bounty_daily (uuid, day_bucket, seconds, sessions, updated_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                bindInsert = { statement ->
                    statement.setObject(1, uuid)
                    statement.setLong(2, dayMillis)
                    statement.setLong(3, seconds)
                    statement.setLong(4, nowMillis)
                }
            )
        }
    }

    /** 清理过期日行（只留最近 N 天）。 */
    fun pruneDailyBefore(dayMillis: Long): Int = pool.withConnection { connection ->
        connection.prepareStatement("DELETE FROM bounty_daily WHERE day_bucket < ?").use { statement ->
            statement.setLong(1, dayMillis)
            statement.executeUpdate()
        }
    }

    // ------------------------------------------------------------------ 商城

    /**
     * 兑换：查是否已购 → 扣款 → 记流水，**全程一个事务**。
     *
     * @param oneTime true = 一次性条目（称号之类），靠 `purchase_key` 唯一约束兜底；
     *   false = 可重复购买
     * @return true = 兑换成功；false = 余额不足或已买过
     */
    fun purchase(
        uuid: UUID,
        name: String,
        itemId: String,
        cost: Long,
        oneTime: Boolean,
        nowMillis: Long
    ): Boolean = pool.withTransaction { connection ->
        if (oneTime && hasPurchased(connection, uuid, itemId)) {
            return@withTransaction false
        }
        val paid = connection.prepareStatement(
            "UPDATE bounty_wallet SET tokens = tokens - ?, spent = spent + ?, updated_at = ? " +
                "WHERE uuid = ? AND tokens >= ?"
        ).use { statement ->
            statement.setLong(1, cost)
            statement.setLong(2, cost)
            statement.setLong(3, nowMillis)
            statement.setObject(4, uuid)
            statement.setLong(5, cost)
            statement.executeUpdate()
        }
        if (paid == 0) return@withTransaction false

        connection.prepareStatement(
            "INSERT INTO bounty_purchase (uuid, name, item_id, cost, created_at, purchase_key) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setString(2, name)
            statement.setString(3, itemId)
            statement.setLong(4, cost)
            statement.setLong(5, nowMillis)
            // 一次性条目才写 purchase_key；Sql.setString 会把空串转成 NULL，
            // 于是可重复购买的行不参与唯一约束（唯一索引允许多个 NULL）
            Sql.setString(statement, 6, if (oneTime) uuid.toString() + ":" + itemId else null)
            statement.executeUpdate()
        }
        true
    }

    fun hasPurchased(uuid: UUID, itemId: String): Boolean = pool.withConnection { connection ->
        hasPurchased(connection, uuid, itemId)
    }

    fun listPurchases(uuid: UUID): List<BountyPurchaseRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, uuid, name, item_id, cost, created_at FROM bounty_purchase " +
                "WHERE uuid = ? ORDER BY created_at DESC"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.executeQuery().use { rows ->
                val out = ArrayList<BountyPurchaseRow>(8)
                while (rows.next()) {
                    out.add(
                        BountyPurchaseRow(
                            id = rows.getLong("id"),
                            uuid = Sql.uuid(rows, "uuid") ?: continue,
                            name = rows.getString("name"),
                            itemId = rows.getString("item_id"),
                            cost = rows.getLong("cost"),
                            createdAt = rows.getLong("created_at")
                        )
                    )
                }
                out
            }
        }
    }

    // ------------------------------------------------------------------ 人类基线

    fun loadBaselines(): List<BountyBaselineRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT metric_key, mean, sd, samples, direction FROM bounty_baseline"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val out = ArrayList<BountyBaselineRow>(8)
                while (rows.next()) {
                    out.add(
                        BountyBaselineRow(
                            metricKey = rows.getString("metric_key"),
                            mean = rows.getDouble("mean"),
                            sd = rows.getDouble("sd"),
                            samples = rows.getLong("samples"),
                            direction = rows.getString("direction")
                        )
                    )
                }
                out
            }
        }
    }

    /** 整体覆盖式写入（基线是"全局快照"，不存在按行增量语义）。 */
    fun saveBaselines(rows: List<BountyBaselineRow>, nowMillis: Long) {
        if (rows.isEmpty()) return
        pool.withTransaction { connection ->
            for (row in rows) {
                Sql.upsert(
                    connection,
                    updateSql = "UPDATE bounty_baseline SET mean = ?, sd = ?, samples = ?, direction = ?, " +
                        "updated_at = ? WHERE metric_key = ?",
                    bindUpdate = { statement ->
                        statement.setDouble(1, row.mean)
                        statement.setDouble(2, row.sd)
                        statement.setLong(3, row.samples)
                        statement.setString(4, row.direction)
                        statement.setLong(5, nowMillis)
                        statement.setString(6, row.metricKey)
                    },
                    insertSql = "INSERT INTO bounty_baseline (metric_key, mean, sd, samples, direction, " +
                        "updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    bindInsert = { statement ->
                        statement.setString(1, row.metricKey)
                        statement.setDouble(2, row.mean)
                        statement.setDouble(3, row.sd)
                        statement.setLong(4, row.samples)
                        statement.setString(5, row.direction)
                        statement.setLong(6, nowMillis)
                    }
                )
            }
            true
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun balance(connection: Connection, uuid: UUID): Long =
        connection.prepareStatement("SELECT tokens FROM bounty_wallet WHERE uuid = ?").use { statement ->
            statement.setObject(1, uuid)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }

    private fun hasPurchased(connection: Connection, uuid: UUID, itemId: String): Boolean =
        connection.prepareStatement(
            "SELECT count(*) FROM bounty_purchase WHERE uuid = ? AND item_id = ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setString(2, itemId)
            statement.executeQuery().use { rows -> rows.next() && rows.getLong(1) > 0L }
        }

    private fun readWallet(rows: ResultSet): BountyWalletRow = BountyWalletRow(
        uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
        name = rows.getString("name") ?: "?",
        tokens = rows.getLong("tokens"),
        earned = rows.getLong("earned"),
        spent = rows.getLong("spent"),
        updatedAt = rows.getLong("updated_at")
    )

    private fun readCase(rows: ResultSet): BountyCaseRow = BountyCaseRow(
        id = rows.getLong("id"),
        uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
        name = rows.getString("name") ?: "?",
        task = rows.getString("task") ?: "?",
        verdict = rows.getString("verdict") ?: "?",
        confidence = rows.getString("confidence") ?: "?",
        tokens = rows.getLong("tokens"),
        flags = rows.getInt("flags"),
        maxVl = rows.getDouble("max_vl"),
        anomalyScore = rows.getDouble("anomaly_score"),
        baselineReady = rows.getBoolean("baseline_ready"),
        samples = rows.getInt("samples"),
        reason = rows.getString("reason"),
        summary = rows.getString("summary"),
        evidencePath = rows.getString("evidence_path"),
        status = rows.getString("status") ?: STATUS_PENDING,
        createdAt = rows.getLong("created_at"),
        reviewedBy = rows.getString("reviewed_by"),
        reviewedAt = Sql.millisOrNull(rows, "reviewed_at")
    )

    private fun collectCases(rows: ResultSet): List<BountyCaseRow> {
        val out = ArrayList<BountyCaseRow>(16)
        while (rows.next()) out.add(readCase(rows))
        return out
    }

    companion object {
        /** 待人工复核。 */
        const val STATUS_PENDING = "pending"

        /** 特征已采纳（可用于更新生产检测/白名单）。 */
        const val STATUS_ACCEPTED = "accepted"

        /** 已驳回。 */
        const val STATUS_REJECTED = "rejected"

        /**
         * 只登记、无需复核。
         *
         * <p>用于 [com.anticheat.core.bounty.BountyVerdict.DETECTED]（现有检测有效）与
         * [com.anticheat.core.bounty.BountyVerdict.INCONCLUSIVE]（没有结论）：
         * 这两类也是有用的历史，但它们**不是"发现"**，不该占用人工复核队列。</p>
         */
        const val STATUS_LOGGED = "logged"

        private const val CASE_COLUMNS =
            "id, uuid, name, task, verdict, confidence, tokens, flags, max_vl, anomaly_score, " +
                "baseline_ready, samples, reason, summary, evidence_path, status, created_at, " +
                "reviewed_by, reviewed_at"
    }
}
