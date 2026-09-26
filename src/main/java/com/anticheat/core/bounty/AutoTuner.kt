package com.anticheat.core.bounty

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.db.AuditRow
import com.anticheat.core.util.CoreLog
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BYPASSED 自动调参闭环。
 *
 * <h3>它做什么</h3>
 * 当赏金沙箱判定出一次**可信的**绕过（见 [admit] 的准入条件）时，
 * 自动把最相关的那个检测阈值收紧一小步，写进数据库并热生效——不需要人工改配置文件。
 *
 * <h3>它刻意不做什么（读之前先接受这几条）</h3>
 * 1. **只吃 MEDIUM 及以上的置信度**。`BYPASSED` 在基线未就绪/无异常旁证时是 LOW，
 *    那种情况下的"完成目标"本身**不携带作弊信息**（任务目标可能人肉就能完成），
 *    拿它驱动自动调参 = 把"玩家玩得好"当成"检测不够严"。
 * 2. **只调数值阈值，不碰判定逻辑**。改的是 `check_rule.thresholds` 里的一个键，
 *    检测的判据代码一行不动。
 * 3. **只降不升、一次最多 10%**。BYPASSED 的含义是"太松了"，方向固定；
 *    幅度受限是因为归因有偏差（窗口对齐、样本量），一次调到位会把偏差变成误报。
 * 4. **不越过硬下限**。每个参数的底线在检测代码里声明（如 [AimC.AUTO_TUNE_FLOOR]），
 *    取值与既有的"阈值不变量测试"同源——那条断言从文档升级成了机器护栏。
 * 5. **有观察期与自动回滚**。改动之后的一段窗口内若该检测的违规量暴涨，自动还原。
 *
 * <h3>⚠ 这是一个可被反向利用的回路</h3>
 * 作弊者可以故意触发 BYPASSED，把某个检测的阈值一路推低，直到正常玩家开始被误报。
 * 缓解手段就是上面的 1/3/4/5 四条，缺一条都不该开启这个功能。
 * 本项目 2026-09-25 刚因为"阈值落进合法行为区间"误封过玩家，
 * 所以**默认关闭**（`bounty.auto-tune.enabled: false`），并支持影子模式先空跑。
 *
 * <h3>影子模式</h3>
 * `shadow: true` 时完整执行归因与计算，但**不写库**，只把"本来会改成什么"记进
 * `audit_log` 与日志。这是推荐的上线姿势：先在零风险下观测一到两周，
 * 确认虚拟调整的频率与幅度都合理，再关掉影子模式。
 */
object AutoTuner {

    /** 审计里的操作者标识（与玩家操作、控制台操作区分开）。 */
    const val OPERATOR = "AntiCheat-AutoTune"

    /** 审计类型。 */
    const val AUDIT_TYPE = "anticheat_autotune"

    /** 回滚之后该检测的长冷却时长（小时）。 */
    const val ROLLBACK_COOLDOWN_HOURS = 168

    /** 观察期巡检间隔（tick）。5 分钟。 */
    private const val SWEEP_INTERVAL_TICKS = 20L * 300

    /** 配置（`bounty.auto-tune.*`）。 */
    class Config @JvmOverloads constructor(
        val enabled: Boolean = false,
        val shadow: Boolean = true,
        val minAttribution: Double = 0.70,
        val maxStep: Double = 0.10,
        val safetyMargin: Double = 1.10,
        val cooldownHours: Int = 24,
        val maxPerDay: Int = 3,
        val observeHours: Int = 6,
        val rollbackViolationFactor: Double = 3.0,
        val allow: Set<String> = setOf("AimC", "AutoClickerD", "SpeedB")
    )

    /** 准入结论。 */
    class Admission(val allowed: Boolean, val reason: String)

    /** 一次已生效调整的观察期状态（内存，重启即失效 → 转为人工复核）。 */
    private class Observation(
        val checkName: String,
        val paramKey: String,
        val oldValue: Double,
        val caseId: Long,
        val appliedAt: Long,
        /** 调整之前同一时长窗口内的违规条数，作为"是否暴涨"的基线。 */
        val baselineViolations: Long
    )

    @Volatile
    private var config = Config()

    @Volatile
    private var plugin: Plugin? = null

    /** 检测名 → 上次调整时间。 */
    private val lastTuneAt = ConcurrentHashMap<String, Long>()

    /** 检测名 → 观察期状态。 */
    private val observations = ConcurrentHashMap<String, Observation>()

    private val dailyCount = AtomicInteger(0)

    @Volatile
    private var dailyBucket: Long = 0L

    // ------------------------------------------------------------------ 配置

    /**
     * 重读 `bounty.auto-tune.*`。
     *
     * <p>**必须同时挂到 `/ac reload` 上**（见 `BountyManager.reload`）。
     * 只在插件启用时读一次是不够的：管理员改完 config.yml 执行 `/ac reload`，
     * 会以为配置生效了，而实际跑的还是启动时的旧值——这种"静默不生效"
     * 比报错难查得多（2026-09-26 就踩过一次：改完配置 + reload，
     * 三次绕过一条审计都没写，因为内存里 enabled 还是 false）。</p>
     */
    @JvmStatic
    fun loadConfig(plugin: Plugin) {
        val section = plugin.config
        val allow = section.getStringList("bounty.auto-tune.allow")
        config = Config(
            enabled = section.getBoolean("bounty.auto-tune.enabled", false),
            shadow = section.getBoolean("bounty.auto-tune.shadow", true),
            minAttribution = section.getDouble("bounty.auto-tune.min-attribution", 0.70),
            maxStep = section.getDouble("bounty.auto-tune.max-step", 0.10),
            safetyMargin = section.getDouble("bounty.auto-tune.safety-margin", 1.10),
            cooldownHours = section.getInt("bounty.auto-tune.cooldown-hours", 24),
            maxPerDay = section.getInt("bounty.auto-tune.max-per-day", 3),
            observeHours = section.getInt("bounty.auto-tune.observe-hours", 6),
            rollbackViolationFactor = section.getDouble("bounty.auto-tune.rollback-violation-factor", 3.0),
            allow = if (allow.isNullOrEmpty()) setOf("AimC", "AutoClickerD", "SpeedB") else allow.toSet()
        )
    }

    /** 启动观察期巡检。由插件在启用时调用。 */
    @JvmStatic
    fun start(plugin: Plugin) {
        this.plugin = plugin
        loadConfig(plugin)
        // 重启后观察期状态丢失：把上次由自动调参留下的痕迹指出来，让管理员人工过一眼
        warnAboutPendingTunes()
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, Runnable { sweep() }, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS
        )
        CoreLog.info(
            "自动调参：" + if (config.enabled) {
                if (config.shadow) "已启用（影子模式，只记录不写入）" else "已启用（会实际写库并热生效）"
            } else {
                "未启用"
            }
        )
    }

    fun currentConfig(): Config = config

    /**
     * 把人类基线的就绪情况告诉自动调参，让"开关是开的、但永远不会通过准入"
     * 这件事**明确出现在日志里**。
     *
     * <p>准入里有一道「基线未就绪 → 拒绝」。基线是攒出来的（非重叠窗口，
     * 需若干个指标各达到 `min-samples` 观测量），在小服/新服上可能要很久。
     * 期间自动调参就是完全静默的空转：管理员把开关打开、配置改对、反复跑绕过，
     * 却什么都看不到，也无从判断卡在哪——2026-09-26 就是这么被问到的。</p>
     *
     * <p>所以这条日志的作用不是"报告状态"，而是**把一次静默失败变成一次显式失败**。
     * 同类问题的修法是加断言；这里没法断言（依赖运行时数据），退而求其次：
     * 在唯一的两个加载入口（插件启用 / `/ac reload`）各说一次。</p>
     */
    @JvmStatic
    fun noteBaselineReadiness(ready: Boolean, detail: String) {
        val cfg = config
        if (!cfg.enabled) return
        if (ready) {
            CoreLog.info("自动调参：人类基线已就绪（" + detail + "），准入的基线检查这道门是开着的")
            return
        }
        CoreLog.warn(
            "自动调参：人类基线未就绪（" + detail + "）——" +
                "准入会把**每一个**案例都判为「基线未就绪」而拒绝，因此当前不会产生任何调整。" +
                "这不是开关没生效，是判定所需的旁证还没攒够；" +
                "在基线达标之前，任何绕过都不会触发自动修复。"
        )
    }

    // ------------------------------------------------------------------ 准入（纯逻辑）

    /**
     * 准入判断。**纯逻辑**，与 IO 无关，因此可以被单测完整覆盖。
     *
     * <p>顺序即优先级：前三条是"这次绕过根本不可信"，后三条是"就算可信现在也不该动"。</p>
     */
    @JvmStatic
    fun admit(
        verdict: BountyVerdict,
        confidence: BountyConfidence,
        baselineReady: Boolean,
        bestRatio: Double,
        hasCandidate: Boolean,
        cooldownActive: Boolean,
        quotaExhausted: Boolean,
        cfg: Config
    ): Admission {
        if (!verdict.isFinding) {
            return Admission(false, "结论不是发现类（" + verdict.name + "）")
        }
        if (confidence == BountyConfidence.LOW) {
            return Admission(
                false,
                "置信度 LOW：此时『完成目标』本身不携带作弊信息（任务目标可能人肉也能完成），只能人工复核"
            )
        }
        if (!baselineReady) {
            return Admission(false, "人类基线未就绪：异常分不可信，整个判定处于降级状态")
        }
        if (!hasCandidate) {
            return Admission(false, "归因无候选：没有可重放的判据接近命中")
        }
        if (bestRatio < cfg.minAttribution) {
            return Admission(
                false,
                "最高接近度 " + percent(bestRatio) + " 低于门槛 " + percent(cfg.minAttribution)
            )
        }
        if (cooldownActive) {
            return Admission(false, "该检测仍在冷却期内")
        }
        if (quotaExhausted) {
            return Admission(false, "已达当日调整配额（" + cfg.maxPerDay + " 次）")
        }
        return Admission(true, "通过")
    }

    // ------------------------------------------------------------------ 主入口

    /**
     * 案例结算后的入口。由 `BountyManager.persistTaskResult` 在**异步线程**调用。
     *
     * @param samples 任务期间的逐 tick 采样（调用方负责裁掉任务开始之前的）
     */
    @Suppress("LongParameterList")
    @JvmStatic
    fun onCaseSettled(
        caseId: Long,
        playerName: String,
        verdict: BountyVerdict,
        confidence: BountyConfidence,
        baselineReady: Boolean,
        samples: List<BountySample>
    ) {
        val cfg = config
        if (!cfg.enabled) return
        if (!verdict.isFinding) return

        val thresholds = readThresholds(cfg)
        val candidates = Attribution.candidates(samples, thresholds)
        if (candidates.isEmpty()) {
            recordAudit(caseId, playerName, "skip", cfg, "归因无候选（样本不足或阈值缺失）", null, candidates)
            return
        }

        val tunable = candidates.firstOrNull {
            !it.alreadyCaught && it.checkName in cfg.allow && it.ratio >= cfg.minAttribution
        }

        val admission = admit(
            verdict = verdict,
            confidence = confidence,
            baselineReady = baselineReady,
            bestRatio = tunable?.ratio ?: 0.0,
            hasCandidate = tunable != null,
            cooldownActive = tunable != null && isCoolingDown(tunable.checkName, cfg),
            quotaExhausted = isQuotaExhausted(cfg),
            cfg = cfg
        )

        if (!admission.allowed) {
            recordAudit(caseId, playerName, "skip", cfg, admission.reason, null, candidates)
            return
        }

        val target = tunable!!
        val floor = Attribution.floorOf(target.checkName)
        if (floor == null) {
            recordAudit(caseId, playerName, "skip", cfg, target.checkName + " 未声明自动调参下限", null, candidates)
            return
        }

        val proposal = Attribution.propose(target, floor, cfg.maxStep, cfg.safetyMargin)
        if (proposal == null) {
            recordAudit(
                caseId, playerName, "skip", cfg,
                target.checkName + " 无需收紧（当前阈值已能覆盖该证据，或已达硬下限）", null, candidates
            )
            return
        }

        if (cfg.shadow) {
            recordAudit(caseId, playerName, "shadow", cfg, "影子模式：不写入", proposal, candidates)
            return
        }

        apply(caseId, playerName, proposal, cfg, candidates)
    }

    // ------------------------------------------------------------------ 生效

    private fun apply(
        caseId: Long,
        playerName: String,
        proposal: Attribution.Proposal,
        cfg: Config,
        candidates: List<Attribution.Candidate>
    ) {
        val database = AntiCheatCore.database
        if (database == null || !database.isReady) {
            recordAudit(caseId, playerName, "skip", cfg, "数据库未就绪", proposal, candidates)
            return
        }

        // 只覆盖这一个键，其余阈值沿用配置层当前值：
        // 直接写一个新 map 会把其它键从库里抹掉，那次改动的副作用远大于"调一个数"。
        val configManager = AntiCheatCore.configManager
        val thresholds = LinkedHashMap(configManager.thresholdsOf(proposal.checkName))
        thresholds[proposal.paramKey] = proposal.newValue

        val affected = database.setCheckThresholds(proposal.checkName, thresholds, OPERATOR)
        if (affected <= 0) {
            recordAudit(
                caseId, playerName, "skip", cfg,
                "写库失败：check_rule 里没有 " + proposal.checkName + " 这一行", proposal, candidates
            )
            return
        }

        val now = System.currentTimeMillis()
        val observeMillis = cfg.observeHours * 3_600_000L
        val baseline = database.topViolatingChecksSince(now - observeMillis, 50)
            .firstOrNull { it.first == proposal.checkName }?.second ?: 0L

        observations[proposal.checkName] = Observation(
            checkName = proposal.checkName,
            paramKey = proposal.paramKey,
            oldValue = proposal.oldValue,
            caseId = caseId,
            appliedAt = now,
            baselineViolations = baseline
        )
        lastTuneAt[proposal.checkName] = now
        bumpQuota(now)

        reloadChecks()
        recordAudit(caseId, playerName, "applied", cfg, "已写入并热生效", proposal, candidates)
        CoreLog.info("[自动调参] " + proposal + "（案例 #" + caseId + "，观察 " + cfg.observeHours + " 小时）")
    }

    /** 热生效：重读库里规则并下发到每个在线玩家的检测实例。必须在主线程。 */
    private fun reloadChecks() {
        val owner = plugin ?: return
        Bukkit.getScheduler().runTask(owner, Runnable { runCatching { AntiCheatCore.reload() } })
    }

    // ------------------------------------------------------------------ 观察与回滚

    /**
     * 观察期巡检：到期的调整结算一次，违规量暴涨就自动回滚。
     *
     * <p>放在**异步**线程（读库 + 写审计），只有真正回滚时才回主线程做热重载。</p>
     */
    fun sweep() {
        val cfg = config
        if (!cfg.enabled || observations.isEmpty()) return

        val now = System.currentTimeMillis()
        val observeMillis = cfg.observeHours * 3_600_000L
        val iterator = observations.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val observation = entry.value
            if (now - observation.appliedAt < observeMillis) continue
            iterator.remove()

            val database = AntiCheatCore.database ?: continue
            val current = database.topViolatingChecksSince(observation.appliedAt, 50)
                .firstOrNull { it.first == observation.checkName }?.second ?: 0L
            val baseline = observation.baselineViolations.coerceAtLeast(1L)
            val factor = current.toDouble() / baseline.toDouble()

            if (factor > cfg.rollbackViolationFactor) {
                rollback(observation, cfg, "观察期内违规 " + current + " 条，为基线 " + baseline +
                    " 条的 " + format(factor) + " 倍（阈值 " + format(cfg.rollbackViolationFactor) + "）")
            } else {
                recordObservationAudit(
                    observation, "observe-ok",
                    "观察期内违规 " + current + " 条，基线 " + baseline + " 条（" + format(factor) + " 倍），未触发回滚"
                )
            }
        }
    }

    private fun rollback(observation: Observation, cfg: Config, reason: String) {
        val database = AntiCheatCore.database
        if (database == null || !database.isReady) return

        val thresholds = LinkedHashMap(AntiCheatCore.configManager.thresholdsOf(observation.checkName))
        thresholds[observation.paramKey] = observation.oldValue

        val affected = database.setCheckThresholds(observation.checkName, thresholds, OPERATOR + "-rollback")
        if (affected <= 0) return

        lastTuneAt[observation.checkName] = System.currentTimeMillis() +
            ROLLBACK_COOLDOWN_HOURS * 3_600_000L - cfg.cooldownHours * 3_600_000L

        reloadChecks()
        recordObservationAudit(observation, "rolled-back", reason)
        CoreLog.warn(
            "[自动调参] 已回滚 " + observation.checkName + "." + observation.paramKey +
                " 到 " + format(observation.oldValue) + "：" + reason +
                "（该检测进入 " + ROLLBACK_COOLDOWN_HOURS + " 小时长冷却，建议人工复核）"
        )
    }

    /**
     * 重启后把"上次由自动调参留下的痕迹"指出来。
     *
     * <p>观察期状态在内存里，重启就没了——于是"调坏了但还没到回滚时刻"这一次会静默消失。
     * 与其假装无事发生，不如在启动时明确提示人工看一眼。</p>
     */
    private fun warnAboutPendingTunes() {
        val database = AntiCheatCore.database ?: return
        if (!database.isReady) return
        val rows = runCatching { database.loadCheckRules() }.getOrDefault(emptyMap())
        val pending = rows.values.filter { it.updatedBy?.startsWith(OPERATOR) == true }
        if (pending.isEmpty()) return
        CoreLog.warn(
            "[自动调参] 数据库里有 " + pending.size + " 个检测的阈值是自动调参写的（" +
                pending.joinToString(", ") { it.checkName } +
                "），但观察期状态随重启丢失了——请人工确认这些值是否合理。"
        )
    }

    // ------------------------------------------------------------------ 内部工具

    /** 读三个可重放判据当前生效的阈值（库优先，其次 config.yml，最后默认值）。 */
    private fun readThresholds(cfg: Config): Map<String, Double> {
        val manager = AntiCheatCore.configManager
        val out = HashMap<String, Double>()
        if ("AimC" in cfg.allow) {
            out["AimC"] = manager.optionDouble("AimC", Attribution.PARAM_AIMC, 60.0)
        }
        if ("AutoClickerD" in cfg.allow) {
            out["AutoClickerD"] = manager.optionDouble("AutoClickerD", Attribution.PARAM_AUTOCLICKER_D, 20.0)
        }
        if ("SpeedB" in cfg.allow) {
            out["SpeedB"] = manager.optionDouble("SpeedB", Attribution.PARAM_SPEEDB, 1.5)
        }
        return out
    }

    private fun isCoolingDown(checkName: String, cfg: Config): Boolean {
        val last = lastTuneAt[checkName] ?: return false
        return System.currentTimeMillis() - last < cfg.cooldownHours * 3_600_000L
    }

    private fun isQuotaExhausted(cfg: Config): Boolean {
        val today = System.currentTimeMillis() / 86_400_000L
        if (today != dailyBucket) {
            dailyBucket = today
            dailyCount.set(0)
        }
        return dailyCount.get() >= cfg.maxPerDay
    }

    private fun bumpQuota(now: Long) {
        val today = now / 86_400_000L
        if (today != dailyBucket) {
            dailyBucket = today
            dailyCount.set(0)
        }
        dailyCount.incrementAndGet()
    }

    private fun recordAudit(
        caseId: Long,
        playerName: String,
        result: String,
        cfg: Config,
        reason: String,
        proposal: Attribution.Proposal?,
        candidates: List<Attribution.Candidate>
    ) {
        val detail = buildString {
            append("case=#").append(caseId).append(" player=").append(playerName)
            append(" shadow=").append(cfg.shadow)
            if (candidates.isNotEmpty()) {
                append(" | candidates=")
                append(candidates.joinToString("; ") { it.toString() })
            }
            if (proposal != null) {
                append(" | proposal=").append(proposal)
            }
            append(" | ").append(reason)
        }
        writeAudit(proposal?.checkName ?: "-", result, detail)
    }

    private fun recordObservationAudit(observation: Observation, result: String, detail: String) {
        writeAudit(
            observation.checkName, result,
            "case=#" + observation.caseId + " param=" + observation.paramKey +
                " old=" + format(observation.oldValue) + " | " + detail
        )
    }

    private fun writeAudit(target: String, result: String, detail: String) {
        val database = AntiCheatCore.database ?: return
        val row = AuditRow(
            id = null,
            timestamp = System.currentTimeMillis(),
            operator = OPERATOR,
            operatorRole = 0,
            type = AUDIT_TYPE,
            target = target,
            ip = null,
            result = result,
            detail = detail.take(2000)
        )
        runCatching { database.saveAudit(row) }
    }

    /** 排障用：当前有几条观察在跑、今天调了几次。 */
    @JvmStatic
    fun describe(): String =
        "影子=" + config.shadow + " 启用=" + config.enabled +
            " 观察中=" + observations.size + " 今日已调=" + dailyCount.get() + "/" + config.maxPerDay

    private fun percent(value: Double): String =
        if (value.isFinite()) String.format("%.0f%%", value * 100.0) else "NaN"

    private fun format(value: Double): String =
        if (value.isFinite()) String.format("%.3f", value) else "NaN"
}
