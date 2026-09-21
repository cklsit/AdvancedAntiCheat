package com.anticheat.core.db

/**
 * 数据库配置。**从一份扁平的键值表解析**（Bukkit 的 `ConfigurationSection.getValues(true)`
 * 给出的就是这种形态），因此不需要 Bukkit 就能单测——配置解析写错的代价是
 * "连到了错误的库"或"池子被设成 0 导致永远借不到连接"，属于必须在 CI 里挡住的一类。
 */
class DatabaseSettings(
    /** 是否启用持久化。false = 完全不碰数据库（反作弊照常工作）。 */
    val enabled: Boolean,
    /** 后端类型。 */
    val backend: Backend,
    /** 写入 ban / violation 的子服名。 */
    val serverName: String,
    /** H2 的库文件名（相对插件数据目录，不带扩展名）。 */
    val h2Path: String,
    val postgres: PostgresSettings,
    val poolSize: Int,
    val connectionTimeoutMs: Int,
    val autoMigrate: Boolean,
    val reconnectDelaySeconds: Int,
    val violation: ViolationSettings,
    val stats: StatsSettings,
    /** 离线 IP 情报规则（按最长前缀匹配）。 */
    val ipIntelRules: List<IpIntelRule>
) {

    /** 后端类型。 */
    enum class Backend {
        /** 嵌入式 H2（默认、保底）。 */
        H2,

        /** 外置 PostgreSQL。 */
        POSTGRESQL;

        companion object {
            /**
             * 解析配置里的 `type`。
             *
             * <p>认不出来的值一律**回到 H2**：H2 是嵌入式默认后端，退到它至少能落库；
             * 而如果这里返回 null 让上层去报错，结果就是"管理员把 type 拼错 → 完全不落库"，
             * 一个拼写错误换来静默的功能缺失。</p>
             */
            @JvmStatic
            fun parse(raw: String?): Backend {
                val text = raw?.trim()?.lowercase() ?: return H2
                return when (text) {
                    "postgres", "postgresql", "pg", "pgsql" -> POSTGRESQL
                    else -> H2
                }
            }
        }
    }

    class PostgresSettings(
        val host: String,
        val port: Int,
        val database: String,
        val username: String,
        val password: String
    ) {
        fun jdbcUrl(): String = "jdbc:postgresql://" + host + ":" + port + "/" + database

        /** 排障用的安全描述（**不含密码**）。 */
        fun describe(): String = username + "@" + host + ":" + port + "/" + database

        companion object {
            const val DEFAULT_PORT = 5432
        }
    }

    class ViolationSettings(
        val batchSize: Int,
        val flushIntervalMs: Long,
        val queueCapacity: Int
    ) {
        companion object {
            const val DEFAULT_BATCH_SIZE = 64
            const val DEFAULT_FLUSH_INTERVAL_MS = 5000L
            const val DEFAULT_QUEUE_CAPACITY = 4096
        }
    }

    class StatsSettings(
        val bucketMinutes: Int,
        val riskRefreshMinutes: Int
    ) {
        companion object {
            const val DEFAULT_BUCKET_MINUTES = 5
            const val DEFAULT_RISK_REFRESH_MINUTES = 10
        }
    }

    /** JDBC 驱动类名。 */
    fun driverClass(): String = when (backend) {
        Backend.H2 -> "org.h2.Driver"
        Backend.POSTGRESQL -> "org.postgresql.Driver"
    }

    /**
     * 构造 JDBC URL。
     *
     * @param baseDir H2 的库文件目录（插件数据目录的绝对路径）。PostgreSQL 忽略它。
     */
    fun jdbcUrl(baseDir: String): String = when (backend) {
        Backend.H2 -> {
            val separator = if (baseDir.endsWith("/") || baseDir.endsWith("\\")) "" else "/"
            // 刻意**不**开 H2 的 PostgreSQL 兼容模式：兼容模式会改类型名与 NULL 排序语义，
            // 而我们已经把 SQL 收敛到两个引擎都支持的标准子集，开它只会引入
            // "两个后端行为不一致"这个新变量。DB_CLOSE_ON_EXIT=FALSE 让插件重载时不
            // 由 JVM 关闭钩子抢先关库（由我们自己的 stop() 负责）。
            "jdbc:h2:file:" + baseDir + separator + h2Path +
                ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
        }

        Backend.POSTGRESQL -> postgres.jdbcUrl()
    }

    /** 排障用描述（不含密码）。 */
    fun describe(): String = when (backend) {
        Backend.H2 -> "h2:" + h2Path + "（嵌入式）"
        Backend.POSTGRESQL -> "postgresql:" + postgres.describe()
    }

    fun withBackend(backend: Backend): DatabaseSettings = DatabaseSettings(
        enabled, backend, serverName, h2Path, postgres, poolSize, connectionTimeoutMs,
        autoMigrate, reconnectDelaySeconds, violation, stats, ipIntelRules
    )

    companion object {

        /** 池子上限。开太大没有意义：反作弊的写入是批量异步的，查询是偶发的。 */
        const val MAX_POOL_SIZE = 32

        /** 借连接超时的下限：太短会在库稍微慢一点时立刻失败，太长会拖住调用方线程。 */
        const val MIN_TIMEOUT_MS = 250
        const val MAX_TIMEOUT_MS = 60_000

        /**
         * 从扁平键值表解析。
         *
         * <p>键名与 `config.yml` 里的层级一一对应，例如 `postgres.host`、`violation.batch-size`。
         * 找不到的键一律取默认值，**不抛异常**：配置文件比插件版本旧是常态。</p>
         *
         * @param section `database:` 段的值（`getValues(true)`）。为 null 表示没有这一段。
         */
        @JvmStatic
        fun from(section: Map<String, Any?>?): DatabaseSettings {
            if (section == null) return defaults(enabled = false)

            return DatabaseSettings(
                enabled = bool(section, "enabled", true),
                backend = Backend.parse(str(section, "type", "h2")),
                serverName = str(section, "server-name", "Server-1"),
                h2Path = str(section, "h2.path", "anticheat-v2"),
                postgres = PostgresSettings(
                    host = str(section, "postgres.host", "127.0.0.1"),
                    port = clampInt(int(section, "postgres.port", PostgresSettings.DEFAULT_PORT), 1, 65535),
                    database = str(section, "postgres.database", "anticheat"),
                    username = str(section, "postgres.username", "anticheat"),
                    password = str(section, "postgres.password", "")
                ),
                poolSize = clampInt(int(section, "pool-size", 4), 1, MAX_POOL_SIZE),
                connectionTimeoutMs = clampInt(int(section, "connection-timeout-ms", 5000), MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
                autoMigrate = bool(section, "auto-migrate", true),
                reconnectDelaySeconds = clampInt(int(section, "reconnect-delay-seconds", 30), 5, 3600),
                violation = ViolationSettings(
                    batchSize = clampInt(
                        int(section, "violation.batch-size", ViolationSettings.DEFAULT_BATCH_SIZE), 1, 1000
                    ),
                    flushIntervalMs = int(
                        section, "violation.flush-interval-ms", ViolationSettings.DEFAULT_FLUSH_INTERVAL_MS.toInt()
                    ).toLong().coerceAtLeast(200L),
                    queueCapacity = clampInt(
                        int(section, "violation.queue-capacity", ViolationSettings.DEFAULT_QUEUE_CAPACITY),
                        64, 1_000_000
                    )
                ),
                stats = StatsSettings(
                    bucketMinutes = clampInt(
                        int(section, "stats.bucket-minutes", StatsSettings.DEFAULT_BUCKET_MINUTES), 1, 60
                    ),
                    riskRefreshMinutes = clampInt(
                        int(section, "stats.risk-refresh-minutes", StatsSettings.DEFAULT_RISK_REFRESH_MINUTES), 1, 1440
                    )
                ),
                ipIntelRules = IpIntel.parseRules(section["ip-intel.rules"])
            )
        }

        /** 无数据库段时的兜底（`enabled=false`，调用方不会尝试连接）。 */
        @JvmStatic
        fun defaults(enabled: Boolean): DatabaseSettings = DatabaseSettings(
            enabled = enabled,
            backend = Backend.H2,
            serverName = "Server-1",
            h2Path = "anticheat-v2",
            postgres = PostgresSettings("127.0.0.1", PostgresSettings.DEFAULT_PORT, "anticheat", "anticheat", ""),
            poolSize = 4,
            connectionTimeoutMs = 5000,
            autoMigrate = true,
            reconnectDelaySeconds = 30,
            violation = ViolationSettings(
                ViolationSettings.DEFAULT_BATCH_SIZE,
                ViolationSettings.DEFAULT_FLUSH_INTERVAL_MS,
                ViolationSettings.DEFAULT_QUEUE_CAPACITY
            ),
            stats = StatsSettings(StatsSettings.DEFAULT_BUCKET_MINUTES, StatsSettings.DEFAULT_RISK_REFRESH_MINUTES),
            ipIntelRules = emptyList()
        )

        private fun str(values: Map<String, Any?>, key: String, fallback: String): String {
            val raw = values[key] ?: return fallback
            val text = raw.toString().trim()
            return if (text.isEmpty()) fallback else text
        }

        private fun int(values: Map<String, Any?>, key: String, fallback: Int): Int {
            val raw = values[key] ?: return fallback
            return when (raw) {
                is Number -> raw.toInt()
                else -> raw.toString().trim().toIntOrNull() ?: fallback
            }
        }

        private fun bool(values: Map<String, Any?>, key: String, fallback: Boolean): Boolean {
            val raw = values[key] ?: return fallback
            return when (raw) {
                is Boolean -> raw
                else -> raw.toString().trim().equals("true", ignoreCase = true)
            }
        }

        private fun clampInt(value: Int, min: Int, max: Int): Int =
            if (value < min) min else if (value > max) max else value
    }
}
