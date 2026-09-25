package com.anticheat.core.db

import com.anticheat.core.util.CoreLog
import java.io.File
import java.sql.Driver
import java.sql.DriverManager
import java.util.UUID

/**
 * 数据库门面：连接、迁移、仓储、批量写入与重算任务的**唯一入口**。
 *
 * <h3>失败即降级（与核心层其它部分一致的纪律）</h3>
 * 连不上库、建表失败、驱动缺失——任何一种情况都**不会**让插件被禁用：
 * 反作弊照常判定与告警，只是不再落库，日志出现**一条** WARN
 * （不打堆栈：容器里这类失败常成片出现，每批打一次堆栈会把真正的线索埋掉）。
 * 上层会按 `reconnect-delay-seconds` 定时重试，连上后自动补建表结构。
 *
 * <h3>为什么用"直接引用驱动类"而不是 `Class.forName("org.postgresql.Driver")`</h3>
 * 本项目的 shade 会把驱动重定位到 `com.anticheat.libs.*`，而 **shade 不会改写字符串字面量**，
 * 于是 `Class.forName("org.postgresql.Driver")` 在打包后必然抛 `ClassNotFoundException`
 * （旧代码里 H2 那行 `Class.forName("org.h2.Driver")` 就是这么坏掉的，
 * 只是因为生产一直在用 sqlite 而没人发现）。改成直接 `new org.postgresql.Driver()`：
 * 这是**类引用**，会被 shade 正确改写，而且在单测（未重定位）里同样成立。</p>
 */
class DatabaseService(
    val settings: DatabaseSettings,
    /** 插件数据目录（H2 的库文件放这里）。 */
    private val dataFolder: File
) {

    enum class State {
        /** `database.enabled: false`，完全不碰数据库。 */
        DISABLED,
        /** 从未尝试或正在重试。 */
        CONNECTING,
        /** 可用。 */
        READY,
        /** 最近一次尝试失败（会继续重试）。 */
        FAILED
    }

    @Volatile
    var state: State = if (settings.enabled) State.CONNECTING else State.DISABLED
        private set

    /** 最近一次失败原因（只留一行，写进 `/ac` 输出与日志）。 */
    @Volatile
    var lastError: String? = null
        private set

    private var pool: JdbcPool? = null

    private var profiles: ProfileRepository? = null

    private var ips: IpRepository? = null

    private var bans: BanRepository? = null

    private var violations: ViolationRepository? = null

    private var rules: RuleRepository? = null

    private var audits: AuditRepository? = null

    private var stats: StatsRepository? = null

    private var bounties: BountyRepository? = null

    private var recorder: ViolationRecorder? = null

    /** 当前的检查统计桶起点（_bucket-minutes_ 对齐）。 */
    private var currentBucket: Long = 0L

    val isReady: Boolean get() = state == State.READY

    val isEnabled: Boolean get() = settings.enabled

    // ------------------------------------------------------------------ 生命周期

    /**
     * 尝试建立连接、执行迁移并装配仓储。可重复调用（失败后由上层定时重试）。
     *
     * @return true = 本次之后可用
     */
    fun tryStart(): Boolean {
        if (!settings.enabled) {
            state = State.DISABLED
            return false
        }
        if (isReady) return true

        var created: JdbcPool? = null
        return try {
            registerDriver(settings.backend)
            val user = if (settings.backend == DatabaseSettings.Backend.POSTGRESQL) {
                settings.postgres.username
            } else {
                "sa"
            }
            val password = if (settings.backend == DatabaseSettings.Backend.POSTGRESQL) {
                settings.postgres.password
            } else {
                ""
            }
            val url = settings.jdbcUrl(dataFolder.absolutePath)
            if (settings.backend == DatabaseSettings.Backend.H2) {
                // H2 的库文件目录必须存在，否则驱动会报"目录不存在"这种与配置无关的错
                dataFolder.mkdirs()
            }

            created = JdbcPool(
                url = url,
                username = user,
                password = password,
                maxSize = settings.poolSize,
                borrowTimeoutMs = settings.connectionTimeoutMs.toLong()
            )
            // 立刻借一条连接验证：池子是懒建连接的，不验证的话"配置错了"要等到第一次写才暴露
            created.withConnection { connection ->
                connection.createStatement().use { it.execute("SELECT 1") }
                true
            }

            if (settings.autoMigrate) {
                val result = Migrator(created).migrate()
                CoreLog.info("数据库结构已就绪: " + result.describe())
            }

            val ruleRepo = RuleRepository(created)
            val violationRepo = ViolationRepository(created)
            val newRecorder = ViolationRecorder(
                settings.violation, violationRepo, ruleRepo, settings.stats.bucketMinutes
            )

            profiles = ProfileRepository(created)
            ips = IpRepository(created) { settings.ipIntelRules }
            bans = BanRepository(created)
            violations = violationRepo
            rules = ruleRepo
            audits = AuditRepository(created)
            stats = StatsRepository(created)
            bounties = BountyRepository(created)
            recorder = newRecorder
            pool = created
            currentBucket = newRecorder.bucketMillis(System.currentTimeMillis())
            lastError = null
            state = State.READY

            CoreLog.info("数据库已就绪（" + settings.describe() + "）：" + (stats?.summary() ?: "?"))
            true
        } catch (t: Throwable) {
            runCatching { created?.close() }
            pool = null
            profiles = null
            ips = null
            bans = null
            violations = null
            rules = null
            audits = null
            stats = null
            bounties = null
            recorder = null
            state = State.FAILED
            lastError = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(200)
            CoreLog.warn(
                "数据库不可用（反作弊照常工作，只是不落库）：" + settings.describe() + " -> " + lastError +
                    "；将在 " + settings.reconnectDelaySeconds + " 秒后重试"
            )
            false
        }
    }

    /** 刷掉残留数据并关闭连接池。 */
    fun stop() {
        runCatching { flushNow() }
        runCatching { pool?.close() }
        pool = null
        profiles = null
        ips = null
        bans = null
        violations = null
        rules = null
        audits = null
        stats = null
        bounties = null
        recorder = null
        state = if (settings.enabled) State.CONNECTING else State.DISABLED
    }

    // ------------------------------------------------------------------ 会话

    /** 一次登录的数据库侧结果。 */
    class SessionInfo(
        val profile: ProfileRow?,
        /** 命中生效封禁时的记录（null = 没被封）。 */
        val ban: BanRow?,
        /** 是否在数据库白名单里（调用方据此把玩家标记为豁免）。 */
        val whitelisted: Boolean,
        /** 归一化后的地址；无法解析时为 null。 */
        val ip: String?
    )

    /**
     * 玩家接入：建档案、记 IP、查封禁与白名单。
     *
     * <p>**只在异步线程调用**：它包含 3~5 次查询，放在主线程会直接吃掉 tick。
     * 调用方（[com.anticheat.core.db.DatabaseHooks]）负责投递到异步线程。</p>
     */
    fun onSessionStart(uuid: UUID, name: String, rawIp: String?, nowMillis: Long): SessionInfo? {
        if (!isReady) return null
        return runCatching {
            profiles!!.onLogin(uuid, name, IpIntel.normalize(rawIp), nowMillis)
            val recorded = ips!!.recordLogin(uuid, rawIp, nowMillis)
            val ban = bans!!.findEffective(uuid, recorded?.ip, nowMillis)
            val whitelisted = rules!!.isWhitelisted(uuid, name, nowMillis)
            SessionInfo(profiles!!.find(uuid), ban, whitelisted, recorded?.ip)
        }.getOrElse { t ->
            warnOnce("玩家接入落库失败", t)
            null
        }
    }

    /** 玩家离线：累加在线时长。 */
    fun onSessionEnd(uuid: UUID, playtimeSeconds: Long, nowMillis: Long) {
        if (!isReady) return
        runCatching { profiles!!.onLogout(uuid, playtimeSeconds, nowMillis) }
            .onFailure { warnOnce("离线落库失败", it) }
    }

    // ------------------------------------------------------------------ 违规

    /** 入队一条违规（**不阻塞、不抛异常**）。 */
    fun onViolation(input: ViolationInput) {
        recorder?.record(input)
    }

    /** 记一次检测被评估（命中率分母）。 */
    fun noteCheckEvaluation(checkName: String) {
        recorder?.noteEvaluation(checkName)
    }

    /** 记一次检测判出违规（命中率分子 + VL 累计）。 */
    fun noteCheckFlag(checkName: String, vlDelta: Double, uuid: UUID) {
        recorder?.noteFlag(checkName, vlDelta, uuid)
    }

    /** 立即刷写（定时任务/关服时调用）。 */
    fun flushNow(): Boolean {
        val current = recorder ?: return false
        val (written, statRows) = current.flush()
        if (written > 0) {
            CoreLog.debug("违规落库 " + written + " 条，检测统计 " + statRows + " 项")
        }
        return written >= 0
    }

    // ------------------------------------------------------------------ 封禁

    fun findBan(uuid: UUID, ip: String?, nowMillis: Long): BanRow? {
        if (!isReady) return null
        return runCatching { bans!!.findEffective(uuid, ip, nowMillis) }
            .onFailure { warnOnce("封禁查询失败", it) }
            .getOrNull()
    }

    /** 封 UUID。@return 新记录 id；失败或库不可用返回 null */
    @JvmOverloads
    fun banUuid(
        uuid: UUID, name: String?, reason: String, operator: String, expiresAt: Long?,
        nowMillis: Long, violationId: Long? = null
    ): Long? = ban(BanKind.UUID, uuid.toString(), name, uuid, reason, operator, expiresAt, nowMillis, violationId)

    /** 封 IP。 */
    @JvmOverloads
    fun banIp(
        ip: String, name: String?, reason: String, operator: String, expiresAt: Long?,
        nowMillis: Long, violationId: Long? = null
    ): Long? {
        val normalized = IpIntel.normalize(ip) ?: return null
        return ban(BanKind.IP, normalized, name, null, reason, operator, expiresAt, nowMillis, violationId)
    }

    /** 封网段（CIDR）。 */
    @JvmOverloads
    fun banCidr(
        cidr: String, reason: String, operator: String, expiresAt: Long?,
        nowMillis: Long, violationId: Long? = null
    ): Long? = ban(BanKind.CIDR, cidr, null, null, reason, operator, expiresAt, nowMillis, violationId)

    private fun ban(
        kind: String, target: String, name: String?, uuid: UUID?, reason: String, operator: String,
        expiresAt: Long?, nowMillis: Long, violationId: Long?
    ): Long? {
        if (!isReady) return null
        val scope = if (expiresAt == null) BanScope.PERM else BanScope.TEMP
        return runCatching {
            bans!!.insert(
                kind, target, name, uuid, scope, reason, operator, nowMillis, expiresAt,
                settings.serverName, violationId
            )
        }.getOrElse { t ->
            warnOnce("写入封禁失败", t)
            null
        }
    }

    fun unbanUuid(uuid: UUID, by: String, reason: String?, nowMillis: Long): Int {
        if (!isReady) return 0
        return runCatching { bans!!.revokeByUuid(uuid, by, reason, nowMillis) }
            .onFailure { warnOnce("解封失败", it) }
            .getOrDefault(0)
    }

    fun unbanIp(target: String, by: String, reason: String?, nowMillis: Long): Int {
        if (!isReady) return 0
        return runCatching { bans!!.revokeByIp(target, by, reason, nowMillis) }
            .onFailure { warnOnce("解封失败", it) }
            .getOrDefault(0)
    }

    fun listActiveBans(nowMillis: Long, limit: Int = 200): List<BanRow> {
        if (!isReady) return emptyList()
        return runCatching { bans!!.listEffective(nowMillis, limit) }
            .onFailure { warnOnce("封禁列表查询失败", it) }
            .getOrDefault(emptyList())
    }

    fun listBanPage(offset: Int, limit: Int): List<BanRow> {
        if (!isReady) return emptyList()
        return runCatching { bans!!.listPage(offset, limit) }.getOrDefault(emptyList())
    }

    /** 过期封禁落状态（顺带释放唯一键，供重新封禁使用）。 */
    fun expireOverdue(nowMillis: Long): Int {
        if (!isReady) return 0
        return runCatching { bans!!.expireOverdue(nowMillis) }.getOrDefault(0)
    }

    // ------------------------------------------------------------------ 档案（兼容旧 ProfileManager）

    fun saveProfileBlob(uuid: UUID, name: String?, blob: String) {
        if (!isReady) return
        runCatching { profiles!!.saveBlob(uuid, name, blob, System.currentTimeMillis()) }
            .onFailure { warnOnce("保存玩家档案失败", it) }
    }

    fun loadProfileBlob(uuid: UUID): String? {
        if (!isReady) return null
        return runCatching { profiles!!.loadBlob(uuid) }
            .onFailure { warnOnce("读取玩家档案失败", it) }
            .getOrNull()
    }

    fun profileOf(uuid: UUID): ProfileRow? {
        if (!isReady) return null
        return runCatching { profiles!!.find(uuid) }.getOrNull()
    }

    fun namesOf(uuid: UUID): List<PlayerNameRow> {
        if (!isReady) return emptyList()
        return runCatching { profiles!!.namesOf(uuid) }.getOrDefault(emptyList())
    }

    fun findProfilesByName(name: String, limit: Int = 10): List<ProfileRow> {
        if (!isReady) return emptyList()
        return runCatching { profiles!!.findByAnyName(name, limit) }.getOrDefault(emptyList())
    }

    fun ipsOf(uuid: UUID, limit: Int = 20): List<IpRow> {
        if (!isReady) return emptyList()
        return runCatching { ips!!.listOf(uuid, limit) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ 审计

    /** 该玩家历史上被处罚过的条数（惩罚阶梯升档的依据）。 */
    fun countPunished(uuid: UUID): Int {
        if (!isReady) return 0
        return runCatching { violations!!.countPunished(uuid) }
            .onFailure { warnOnce("统计历史处罚数失败", it) }
            .getOrDefault(0)
    }

    fun saveAudit(row: AuditRow): Long? {
        if (!isReady) return null
        return runCatching { audits!!.insert(row) }
            .onFailure { warnOnce("保存审计失败", it) }
            .getOrNull()
    }

    fun queryAudits(filter: AuditFilter): List<AuditRow> {
        if (!isReady) return emptyList()
        return runCatching { audits!!.query(filter) }
            .onFailure { warnOnce("查询审计失败", it) }
            .getOrDefault(emptyList())
    }

    fun countAudits(filter: AuditFilter): Long {
        if (!isReady) return 0L
        return runCatching { audits!!.count(filter) }.getOrDefault(0L)
    }

    // ------------------------------------------------------------------ 规则与统计

    /** 登记检测规则（启动时调用；库里的策略字段不会被覆盖）。 */
    fun syncCheckRules(seeds: List<RuleRepository.CheckSeed>, by: String): Int {
        if (!isReady || seeds.isEmpty()) return 0
        return runCatching { rules!!.syncCheckRules(seeds, by, System.currentTimeMillis()).size }
            .onFailure { warnOnce("登记检测规则失败", it) }
            .getOrDefault(0)
    }

    fun loadCheckRules(): Map<String, CheckRuleRow> {
        if (!isReady) return emptyMap()
        return runCatching { rules!!.loadCheckRules() }.getOrDefault(emptyMap())
    }

    fun saveLadder(steps: List<LadderStep>) {
        if (!isReady) return
        runCatching { rules!!.replaceLadder(steps, System.currentTimeMillis()) }
            .onFailure { warnOnce("写入惩罚阶梯失败", it) }
    }

    fun loadLadder(): List<LadderStep> {
        if (!isReady) return emptyList()
        return runCatching { rules!!.loadLadder() }.getOrDefault(emptyList())
    }

    fun addWhitelist(uuid: UUID?, name: String?, reason: String?, by: String, expiresAt: Long?): Long? {
        if (!isReady) return null
        return runCatching { rules!!.addWhitelist(uuid, name, reason, by, System.currentTimeMillis(), expiresAt) }
            .onFailure { warnOnce("写白名单失败", it) }
            .getOrNull()
    }

    /**
     * 补齐一条白名单（已存在且仍生效则不动）。
     *
     * @return true = 本次真的新增了一条
     */
    fun ensureWhitelist(uuid: UUID?, name: String?, reason: String?, by: String, expiresAt: Long?): Boolean {
        if (!isReady) return false
        return runCatching { rules!!.ensureWhitelist(uuid, name, reason, by, System.currentTimeMillis(), expiresAt) }
            .onFailure { warnOnce("补白名单失败", it) }
            .getOrDefault(false)
    }

    /** 释放已过期白名单条目的唯一键占位（不释放就无法重新加白）。 */
    fun expireWhitelist(nowMillis: Long): Int {
        if (!isReady) return 0
        return runCatching { rules!!.expireWhitelist(nowMillis) }
            .onFailure { warnOnce("释放过期白名单占位失败", it) }
            .getOrDefault(0)
    }

    /** 当前仍生效的白名单条数。 */
    fun countWhitelist(): Int {
        if (!isReady) return 0
        return runCatching { rules!!.countWhitelist(System.currentTimeMillis()) }.getOrDefault(0)
    }

    fun removeWhitelist(target: String): Int {
        if (!isReady) return 0
        return runCatching { rules!!.removeWhitelist(target) }.getOrDefault(0)
    }

    fun listWhitelist(limit: Int = 200): List<WhitelistRow> {
        if (!isReady) return emptyList()
        return runCatching { rules!!.listWhitelist(limit) }.getOrDefault(emptyList())
    }

    /**
     * 覆盖某个检测的专属阈值。
     *
     * <p>这是"自动调参"的落点：`config.yml` 里的默认值**不会**被改写
     * （那会让升级时的配置合并逻辑与人工编辑互相打架），改的是库里的
     * `check_rule.thresholds` —— 它优先级最高，且改完调用
     * [com.anticheat.core.AntiCheatCore.reload] 即热生效，不必重启。</p>
     *
     * @return 受影响行数；0 表示该检测在库里还没有规则行（需先跑一次规则登记）
     */
    fun setCheckThresholds(checkName: String, thresholds: Map<String, Any?>, by: String): Int {
        if (!isReady) return 0
        return runCatching {
            rules!!.setCheckThresholds(checkName, thresholds, by, System.currentTimeMillis())
        }.getOrDefault(0)
    }

    /**
     * 某时间点之后各检测的违规条数（按条数降序）。
     *
     * <p>给自动调参的观察期用：判断"某个检测被调紧之后是否开始大量误报"，
     * 最直接的信号就是它在**改动之后**产生的违规条数。</p>
     */
    fun topViolatingChecksSince(sinceMillis: Long, limit: Int = 10): List<Pair<String, Long>> {
        if (!isReady) return emptyList()
        return runCatching { violations!!.topChecksSince(sinceMillis, limit) }.getOrDefault(emptyList())
    }

    fun checkHitRates(): List<HitRateRow> {
        if (!isReady) return emptyList()
        return runCatching { rules!!.checkHitRates() }.getOrDefault(emptyList())
    }

    fun playerRisks(limit: Int = 20, minScore: Double = 0.0): List<PlayerRiskRow> {
        if (!isReady) return emptyList()
        return runCatching { stats!!.playerRisks(limit, minScore) }.getOrDefault(emptyList())
    }

    fun violationTrend(sinceMillis: Long): List<ViolationTrendRow> {
        if (!isReady) return emptyList()
        return runCatching { violations!!.trend(sinceMillis) }.getOrDefault(emptyList())
    }

    fun recentViolations(uuid: UUID, limit: Int = 20): List<ViolationRow> {
        if (!isReady) return emptyList()
        return runCatching { violations!!.recent(uuid, limit) }.getOrDefault(emptyList())
    }

    /**
     * 重算风险分与窗口计数。
     *
     * <p>策略在 Kotlin（[RiskScorer]），数据由仓储拉取。窗口长度固定 7 天
     * （24 小时是它的子集，一次拉取算两个数，省一半查询）。</p>
     *
     * @return 更新的玩家数；库不可用返回 0
     */
    fun refreshRisks(nowMillis: Long): Int {
        if (!isReady) return 0
        return runCatching {
            val since7d = nowMillis - 7L * 24 * 3_600_000L
            val since24h = nowMillis - 24L * 3_600_000L
            val events = violations!!.eventsByPlayer(since7d)
            if (events.isEmpty()) return 0

            val scores = HashMap<UUID, Double>(events.size)
            val counts7d = HashMap<UUID, Int>(events.size)
            val counts24h = HashMap<UUID, Int>(events.size)
            val updates = ArrayList<RiskUpdate>(events.size)
            for ((uuid, list) in events) {
                val score = RiskScorer.score(list, nowMillis)
                val recent = list.count { it.atMillis > since24h }
                scores[uuid] = score
                counts7d[uuid] = list.size
                counts24h[uuid] = recent
                updates.add(RiskUpdate(uuid, score, recent, list.size))
            }
            profiles!!.updateRisk(updates, nowMillis)
            stats!!.insertRiskSnapshots(scores, counts7d, 168, nowMillis)
            expireOverdue(nowMillis)
            updates.size
        }.getOrElse { t ->
            warnOnce("风险重算失败", t)
            0
        }
    }

    fun summary(): String {
        if (!isReady) return describe()
        return runCatching { stats!!.summary() }.getOrDefault("无数据")
    }

    // ------------------------------------------------------------------ 赏金沙箱

    /**
     * 赏金仓储的统一调用包装。
     *
     * <p>把"未就绪 → 返回缺省值、失败 → 打一条 WARN 并返回缺省值"这三件事收在一处。
     * 赏金是**游戏玩法**而不是检测：库不可用时它必须退化成"沙箱还能进、只是不记账"，
     * 而不是抛异常把交互打断。</p>
     */
    private fun <T> bounty(default: T, action: String, block: (BountyRepository) -> T): T {
        val repository = bounties
        if (repository == null || !isReady) return default
        return runCatching { block(repository) }
            .onFailure { warnOnce(action, it) }
            .getOrDefault(default)
    }

    fun bountyWallet(uuid: UUID): BountyWalletRow? =
        bounty(null, "读取赏金钱包失败") { it.findWallet(uuid) }

    /** 发放代币；@return 发放后的余额（库不可用时返回 0，调用方据此提示"未记账"）。 */
    fun bountyAddTokens(uuid: UUID, name: String, amount: Long): Long =
        bounty(0L, "发放赏金代币失败") { it.addTokens(uuid, name, amount, System.currentTimeMillis()) }

    fun bountyTop(limit: Int): List<BountyRankRow> =
        bounty(emptyList(), "查询赏金排行榜失败") { it.topWallets(limit) }

    fun bountyInsertCase(
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
        status: String
    ): Long = bounty(-1L, "写入赏金案例失败") {
        it.insertCase(
            uuid, name, task, verdict, confidence, tokens, flags, maxVl, anomalyScore,
            baselineReady, samples, reason, summary, evidencePath, status, System.currentTimeMillis()
        )
    }

    fun bountyCase(id: Long): BountyCaseRow? =
        bounty(null, "读取赏金案例失败") { it.findCase(id) }

    fun bountyCases(status: String, limit: Int = 20): List<BountyCaseRow> =
        bounty(emptyList(), "查询赏金案例失败") { it.listCases(status, limit) }

    fun bountyCasesOf(uuid: UUID, limit: Int = 10): List<BountyCaseRow> =
        bounty(emptyList(), "查询玩家赏金案例失败") { it.listCasesOf(uuid, limit) }

    fun bountyCountCases(status: String): Long =
        bounty(0L, "统计赏金案例失败") { it.countCases(status) }

    /** @return 实际改动的行数；0 = 案例不存在或已被审核过（幂等）。 */
    fun bountyReviewCase(id: Long, status: String, by: String): Int =
        bounty(0, "审核赏金案例失败") { it.reviewCase(id, status, by, System.currentTimeMillis()) }

    fun bountySecondsUsed(uuid: UUID, dayMillis: Long): Long =
        bounty(0L, "读取沙箱每日时长失败") { it.secondsUsed(uuid, dayMillis) }

    fun bountyAddSessionSeconds(uuid: UUID, dayMillis: Long, seconds: Long) {
        bounty(false, "累计沙箱每日时长失败") {
            it.addSessionSeconds(uuid, dayMillis, seconds, System.currentTimeMillis())
            true
        }
    }

    fun bountyPruneDailyBefore(dayMillis: Long): Int =
        bounty(0, "清理过期沙箱时长失败") { it.pruneDailyBefore(dayMillis) }

    /** 兑换；@return true = 扣款与记录都成功（同一个事务）。 */
    fun bountyPurchase(uuid: UUID, name: String, itemId: String, cost: Long, oneTime: Boolean): Boolean =
        bounty(false, "赏金商城兑换失败") {
            it.purchase(uuid, name, itemId, cost, oneTime, System.currentTimeMillis())
        }

    fun bountyHasPurchased(uuid: UUID, itemId: String): Boolean =
        bounty(false, "查询兑换记录失败") { it.hasPurchased(uuid, itemId) }

    fun bountyLoadBaselines(): List<BountyBaselineRow> =
        bounty(emptyList(), "读取人类基线失败") { it.loadBaselines() }

    fun bountySaveBaselines(rows: List<BountyBaselineRow>) {
        bounty(false, "写入人类基线失败") {
            it.saveBaselines(rows, System.currentTimeMillis())
            true
        }
    }

    // ------------------------------------------------------------------ 排障

    /** 一行状态（启动日志 / `/ac` 输出）。 */
    fun describe(): String = when (state) {
        State.READY -> "就绪（" + settings.describe() + "） " + (pool?.stats() ?: "") +
            " 待写=" + (recorder?.pendingCount() ?: 0)
        State.DISABLED -> "已禁用（database.enabled=false）"
        State.CONNECTING -> "连接中（" + settings.describe() + "）"
        State.FAILED -> "不可用（" + settings.describe() + "）：" + (lastError ?: "未知错误") +
            "，每 " + settings.reconnectDelaySeconds + " 秒重试"
    }

    fun recorderStats(): String = recorder?.describe() ?: "未启用"

    /** 距上次统计桶过期还有多久（上层据此决定何时刷写）。 */
    fun shouldRotateBucket(nowMillis: Long): Boolean {
        val current = recorder ?: return false
        val bucket = current.bucketMillis(nowMillis)
        if (bucket == currentBucket) return false
        currentBucket = bucket
        return true
    }

    private fun warnOnce(action: String, t: Throwable) {
        // 只打消息、不打堆栈：这些失败往往成片出现，堆栈会埋掉真正的线索。
        CoreLog.warn(action + "（" + settings.describe() + "）：" + t.javaClass.simpleName + " " + (t.message ?: ""))
    }

    companion object {

        /**
         * 注册 JDBC 驱动。
         *
         * <p>用**类引用**（`new org.postgresql.Driver()`）而不是 `Class.forName("...")`：
         * shade 不会改写字符串字面量，写在字符串里的类名打包后必然找不到。
         * 见类注释里那条旧代码踩过的坑。</p>
         */
        @Volatile
        private var driverRegistered = false

        @Synchronized
        fun registerDriver(backend: DatabaseSettings.Backend) {
            if (driverRegistered) return
            try {
                val driver: Driver = when (backend) {
                    DatabaseSettings.Backend.H2 -> org.h2.Driver()
                    DatabaseSettings.Backend.POSTGRESQL -> org.postgresql.Driver()
                }
                DriverManager.registerDriver(driver)
                driverRegistered = true
            } catch (t: Throwable) {
                CoreLog.warn("JDBC 驱动注册失败: " + t.message)
            }
        }
    }
}
