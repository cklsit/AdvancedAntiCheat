package com.anticheat.core.db

import java.sql.ResultSet
import java.util.UUID

/**
 * IP 记录（`player_ip`）：玩家用过哪些地址、地址族、网段、ASN、国家。
 *
 * <h3>为什么没有任何 SQL 侧的网络运算</h3>
 * PostgreSQL 有 `INET`/`CIDR` 类型与 `>>=` 运算符，但 H2 没有。为了两个后端
 * 共用同一套 SQL，地址与网段都按字符串存，**网段匹配交给 [IpIntel]**（纯逻辑、已单测）。
 * 代价是 CIDR 匹配用不上索引——但被封禁的网段数量是个位数，
 * 真正要优化的查询（"这个玩家用过的 IP""这个 IP 的账号）都是精确等值匹配，索引照用。
 *
 * <h3>为什么把 ASN/国家**冗余存进这一行**</h3>
 * 规范化的做法是 `ip_info(ip, asn, country)` + 关联表。这里刻意反规范化：
 * "某玩家用过的 IP（带情报）"与"某 IP 被哪些账号用过"都要读这一行的情报；
 * 而且 IP 情报会随时间变化（换运营商），**把当时的值留在当时那行**才是正确的语义。
 *
 * <p>情报来源只有管理员配置的网段规则（[IpIntel.match]），**不联网查询**。</p>
 */
class IpRepository(
    private val pool: JdbcPool,
    /** 情报规则用供应器而不是快照：`/ac reload` 换规则后立刻生效。 */
    private val rulesSupplier: () -> List<IpIntelRule>
) {

    /**
     * 记录一次登录用的地址。
     *
     * @param rawIp 客户端原始地址串（可能带端口/方括号/zone），内部会归一化
     * @return 落库后的行；地址无法解析时返回 null（**不落库**，而不是存一行垃圾）
     */
    fun recordLogin(uuid: UUID, rawIp: String?, nowMillis: Long): IpRow? {
        val ip = IpIntel.normalize(rawIp) ?: return null
        val family = IpIntel.familyOf(ip)
        if (family == 0) return null
        val cidr = IpIntel.cidrOf(ip) ?: return null
        val intel = IpIntel.match(ip, rulesSupplier())

        pool.withTransaction { connection ->
            Sql.upsert(
                connection,
                updateSql = "UPDATE player_ip SET last_seen = ?, login_count = login_count + 1, cidr = ?, " +
                    "asn = ?, asn_org = ?, country = ? WHERE uuid = ? AND ip = ?",
                bindUpdate = { statement ->
                    statement.setLong(1, nowMillis)
                    statement.setString(2, cidr)
                    Sql.setInt(statement, 3, intel?.asn)
                    Sql.setString(statement, 4, intel?.org)
                    Sql.setString(statement, 5, intel?.country)
                    statement.setObject(6, uuid)
                    statement.setString(7, ip)
                },
                insertSql = "INSERT INTO player_ip (uuid, ip, ip_family, cidr, asn, asn_org, country, " +
                    "first_seen, last_seen, login_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                bindInsert = { statement ->
                    statement.setObject(1, uuid)
                    statement.setString(2, ip)
                    statement.setInt(3, family)
                    statement.setString(4, cidr)
                    Sql.setInt(statement, 5, intel?.asn)
                    Sql.setString(statement, 6, intel?.org)
                    Sql.setString(statement, 7, intel?.country)
                    statement.setLong(8, nowMillis)
                    statement.setLong(9, nowMillis)
                }
            )
            true
        }

        return IpRow(uuid, ip, family, cidr, intel?.asn, intel?.org, intel?.country, nowMillis, nowMillis, 1)
    }

    /** 某玩家用过的地址（最近在先）。 */
    fun listOf(uuid: UUID, limit: Int = 20): List<IpRow> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT uuid, ip, ip_family, cidr, asn, asn_org, country, first_seen, last_seen, login_count " +
                "FROM player_ip WHERE uuid = ? ORDER BY last_seen DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, uuid)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows -> collect(rows) }
        }
    }

    /** 用过某个地址的账号（小号识别与共享 IP 排查的主要入口）。 */
    @JvmOverloads
    fun playersOf(ip: String, limit: Int = 50): List<UUID> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT uuid FROM player_ip WHERE ip = ? ORDER BY last_seen DESC LIMIT ?"
        ).use { statement ->
            statement.setString(1, ip)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<UUID>(4)
                while (rows.next()) Sql.uuid(rows, "uuid")?.let { out.add(it) }
                out
            }
        }
    }

    /** 同一网段下的账号（`/24` 或 `/64`）。比精确 IP 更能抓住"换地址重连"的小号。 */
    @JvmOverloads
    fun playersInCidr(cidr: String, limit: Int = 100): List<UUID> = pool.withConnection { connection ->
        connection.prepareStatement(
            "SELECT uuid FROM player_ip WHERE cidr = ? ORDER BY last_seen DESC LIMIT ?"
        ).use { statement ->
            statement.setString(1, cidr)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<UUID>(4)
                while (rows.next()) Sql.uuid(rows, "uuid")?.let { out.add(it) }
                out
            }
        }
    }

    /** 某个地址被多少个账号用过（>1 是可疑信号，但**不构成结论**）。 */
    fun accountCountOf(ip: String): Int = pool.withConnection { connection ->
        connection.prepareStatement("SELECT count(*) FROM player_ip WHERE ip = ?").use { statement ->
            statement.setString(1, ip)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }

    fun count(): Long = pool.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM player_ip").use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    private fun collect(rows: ResultSet): List<IpRow> {
        val out = ArrayList<IpRow>(8)
        while (rows.next()) {
            out.add(
                IpRow(
                    uuid = Sql.uuid(rows, "uuid") ?: UUID(0L, 0L),
                    ip = rows.getString("ip") ?: "",
                    family = rows.getInt("ip_family"),
                    cidr = rows.getString("cidr") ?: "",
                    asn = Sql.intOrNull(rows, "asn"),
                    org = rows.getString("asn_org"),
                    country = rows.getString("country"),
                    firstSeen = Sql.millis(rows, "first_seen"),
                    lastSeen = Sql.millis(rows, "last_seen"),
                    loginCount = rows.getInt("login_count")
                )
            )
        }
        return out
    }
}
