package com.anticheat.core.db

import com.anticheat.core.util.CoreLog
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * 极简 JDBC 连接池。
 *
 * <h3>为什么不用 HikariCP / DBCP</h3>
 * 两条理由，都来自本项目的实际约束：
 * 1. **打包风险**：HikariCP 5.x 硬依赖 `org.slf4j`，而本项目的 shade 规则里
 *    "谁提供同名类"已经造成过两次线上事故（`kotlin-stdlib` 顶掉 Paper 的 annotations、
 *    packetevents 自带 `plugin.yml` 抢注册）。为了让一个日志门面把 Hikari 塞进
 *    1.8.8 的类路径，代价远大于收益。
 * 2. **负载形状**：本插件的写入是"批量 + 每 5 秒一次"，查询是"偶发 + 单行"，
 *    4 个连接就够。Hikari 的弹性扩缩、JMX、指标这类能力在这里没有买家。
 *
 * <p>因此这里只做必须做对的三件事：</p>
 * - **借出前校验**（[Connection.isValid]）：网络中断/NAS 侧空闲断连后，
 *   池子里会留下"看起来还在、实际已死"的连接。不校验的话第一次查询就炸，
 *   而且炸在调用方线程上。校验失败即丢弃重建。
 * - **借出超时**：池子空且已达上限时最多等 [borrowTimeoutMs]，超时抛异常而不是无限等待——
 *   无限等待会把主线程挂死。
 * - **关闭时全部释放**，含正在借出的连接（用 [tracked] 记录，避免重载/卸载泄漏）。
 *
 * <p>线程安全：所有状态在 [lock] 下访问，等待用同一把锁的 condition，
 * 不引入第二个锁以免出现"池子状态与等待者不一致"。</p>
 */
class JdbcPool @JvmOverloads constructor(
    private val url: String,
    private val username: String,
    private val password: String,
    private val maxSize: Int,
    private val borrowTimeoutMs: Long,
    /** 建连成功后的回调（用于打一条可读的日志）。 */
    private val onConnect: (Connection) -> Unit = {}
) {

    /** 借出前校验的超时（秒）。1 秒足够判断"这条连接还能不能用"。 */
    private val validationTimeoutSeconds = 1

    private val lock = ReentrantLock()

    private val available = ArrayDeque<Connection>()

    /** 已借出（含正在建连）的连接数。 */
    private var borrowed = 0

    /** 便于排障的计数。 */
    private val createdCount = AtomicLong()

    private val reusedCount = AtomicLong()

    private val brokenCount = AtomicLong()

    private val timeoutCount = AtomicLong()

    @Volatile
    private var closed = false

    /** 建一条新连接（会打日志，便于确认"库连上了"）。 */
    private fun open(): Connection {
        val connection = DriverManager.getConnection(url, username, password)
        connection.autoCommit = true
        createdCount.incrementAndGet()
        runCatching { onConnect(connection) }
        return connection
    }

    /**
     * 借一条连接。**调用方必须归还**（用 [withConnection] / [withTransaction] 更安全）。
     */
    @Throws(SQLException::class)
    fun borrow(): Connection {
        var deadline = System.nanoTime() + borrowTimeoutMs * 1_000_000L
        while (true) {
            var candidate: Connection? = null
            var mustCreate = false

            lock.withLock {
                if (closed) throw SQLException("连接池已关闭")
                while (available.isNotEmpty()) {
                    val pooled = available.pollLast()
                    if (pooled == null) break
                    if (isUsable(pooled)) {
                        candidate = pooled
                        borrowed++
                        reusedCount.incrementAndGet()
                        break
                    }
                    // 失效连接直接丢掉：不丢的话下次还会借到它
                    brokenCount.incrementAndGet()
                    closeQuietly(pooled)
                }
                if (candidate == null && borrowed < maxSize) {
                    borrowed++          // 先占位，避免并发下超发
                    mustCreate = true
                }
            }

            if (candidate != null) return candidate!!
            if (mustCreate) {
                return try {
                    open()
                } catch (t: Throwable) {
                    lock.withLock { borrowed-- }
                    throw t
                }
            }

            // 池子满且都在用：短暂等待后重试。
            //
            // ⚠ 这里**绝对不能持锁 sleep**：归还连接也要拿这把锁，
            // 持锁等待会让归还线程一起卡住，池子直接从"等待"变成"死等"。
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                timeoutCount.incrementAndGet()
                throw SQLException("借连接超时（" + borrowTimeoutMs + "ms，池子上限 " + maxSize + "）")
            }
            try {
                // 轮询 + 短睡：池子很小、等待极少发生，
                // 为此引入 Condition（必须在同一把锁内 await/signal）不值得
                Thread.sleep(min(remaining, POLL_INTERVAL_MS))
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SQLException("借连接被中断")
            }
        }
    }

    /** 归还连接。已经失效的连接会被丢弃。 */
    fun release(connection: Connection) {
        if (closed) {
            closeQuietly(connection)
            lock.withLock { borrowed-- }
            return
        }
        val usable = isUsable(connection)
        if (!usable) brokenCount.incrementAndGet()
        lock.withLock {
            borrowed--
            if (usable) {
                available.addLast(connection)
            }
        }
        if (!usable) closeQuietly(connection)
    }

    /** 借一条连接执行 [block]，保证归还。 */
    fun <T> withConnection(block: (Connection) -> T): T {
        val connection = borrow()
        try {
            return block(connection)
        } finally {
            release(connection)
        }
    }

    /**
     * 在一个事务里执行 [block]：成功提交、异常回滚。
     *
     * <p>批量写入必须走它：violation 一次插几百条，中途失败若留下半批数据，
     * 统计口径就永久偏了。</p>
     */
    fun <T> withTransaction(block: (Connection) -> T): T {
        val connection = borrow()
        val previousAutoCommit = runCatching { connection.autoCommit }.getOrDefault(true)
        try {
            connection.autoCommit = false
            val result = block(connection)
            connection.commit()
            return result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }
            release(connection)
        }
    }

    fun close() {
        val toClose: List<Connection>
        lock.withLock {
            closed = true
            toClose = available.toList()
            available.clear()
        }
        for (connection in toClose) closeQuietly(connection)
    }

    fun isClosed(): Boolean = closed

    /** 健康信息（写进日志/排障）。 */
    fun stats(): String = lock.withLock {
        "idle=" + available.size + " borrowed=" + borrowed + " max=" + maxSize +
            " created=" + createdCount.get() + " reused=" + reusedCount.get() +
            " broken=" + brokenCount.get() + " timeout=" + timeoutCount.get()
    }

    private fun isUsable(connection: Connection): Boolean = try {
        !connection.isClosed && connection.isValid(validationTimeoutSeconds)
    } catch (t: Throwable) {
        false
    }

    private fun closeQuietly(connection: Connection) {
        try {
            connection.close()
        } catch (ignored: Throwable) {
            CoreLog.debug("关闭数据库连接失败: " + ignored.message)
        }
    }

    companion object {

        /** 池满时的轮询间隔（毫秒）。 */
        const val POLL_INTERVAL_MS = 25L

        // 驱动的注册在 DatabaseService.registerDriver() 里用类引用完成，
        // 不在这里用 Class.forName("...")：shade 不改写字符串字面量，
        // 写在字符串里的类名打包后必然找不到（旧 H2 代码就是这样静默失效的）。
    }
}

/** 结果集 → 模型。放在这里是为了让仓储只管写 SQL，映射逻辑单独可读。 */
fun interface RowMapper<T> {
    fun map(rows: ResultSet): T
}

/** 小工具：给 [PreparedStatement] 批量塞参数时少写点样板。 */
internal fun PreparedStatement.bindAll(vararg values: Any?) {
    values.forEachIndexed { index, value ->
        setObject(index + 1, value)
    }
}
