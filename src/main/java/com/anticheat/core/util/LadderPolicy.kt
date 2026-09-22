package com.anticheat.core.util

import com.anticheat.core.db.LadderStep
import java.util.Locale
import java.util.UUID

/**
 * 惩罚阶梯与白名单的**纯解析 / 选择逻辑**。
 *
 * <p>刻意不依赖任何平台类型（连 Bukkit 的 `ConfigurationSection` 都不碰）：
 * config 里读出来本来就是 `List<Map<*, *>>` / `List<*>` 这种原始结构，
 * 直接吃原始结构才能用普通单测覆盖。</p>
 *
 * <p>为什么值得单独抽一层：这两类错误**都是静默生效**的——
 * `"7d"` 被当成 7 毫秒、分档比较写成 `>` 而不是 `>=`、白名单名字大小写不匹配，
 * 都不会抛异常，只会在线上表现为"封禁秒解"或"白名单没生效"。</p>
 */
object LadderPolicy {

    /** 永久（只在 ban 动作里有意义：不写过期时间）。 */
    const val PERMANENT_MILLIS = 0L

    /**
     * 无法解析的时长。
     *
     * <p>与"永久"刻意区分开：写错的配置必须能被调用方识别并告警，
     * 静默当成永久或 0 秒都是把管理员的笔误变成线上行为。</p>
     */
    const val INVALID_MILLIS = -1L

    private val UNIT_MILLIS = mapOf(
        's' to 1_000L,
        'm' to 60_000L,
        'h' to 3_600_000L,
        'd' to 86_400_000L,
        'w' to 604_800_000L
    )

    private val PERMANENT_WORDS = setOf("perm", "perma", "permanent", "off", "forever", "0")

    /**
     * 解析时长文本。
     *
     * @return [PERMANENT_MILLIS]（`perm` / `permanent` / `off` / 空 / `0`）、
     *   正毫秒数（`<数字><单位>`，单位 s/m/h/d/w，大小写不限）、
     *   或 [INVALID_MILLIS]（其它一切）。
     *
     * <p>**故意不支持裸数字**：`7` 的本意可能是 7 秒也可能是 7 天，替管理员猜会把
     * 一次笔误变成"封禁 7 秒"。要求带单位，写错就报 [INVALID_MILLIS]。</p>
     */
    @JvmStatic
    fun parseDurationMillis(text: String?): Long {
        val trimmed = text?.trim()?.lowercase(Locale.ROOT) ?: return PERMANENT_MILLIS
        if (trimmed.isEmpty() || PERMANENT_WORDS.contains(trimmed)) return PERMANENT_MILLIS
        if (trimmed.length < 2) return INVALID_MILLIS
        val factor = UNIT_MILLIS[trimmed.last()] ?: return INVALID_MILLIS
        val value = trimmed.dropLast(1).toLongOrNull() ?: return INVALID_MILLIS
        if (value <= 0L) return INVALID_MILLIS
        return value * factor
    }

    /** 时长文本的人类可读描述（日志/告警用；写错时显式暴露出来）。 */
    @JvmStatic
    fun describeDuration(text: String?): String {
        val millis = parseDurationMillis(text)
        return when {
            millis == PERMANENT_MILLIS -> "永久"
            millis == INVALID_MILLIS -> "配置写错(" + text + ")"
            else -> text!!.trim()
        }
    }

    /**
     * 解析 config 的 `core.punishment.ladder`。
     *
     * <p>`step` 由顺序自动编号（管理员不该手写编号，写错了也没人发现），
     * 解析后按 `min-vl` 升序：分档逻辑只关心"VL 落哪一档"，
     * 与管理员在文件里的书写顺序无关。</p>
     *
     * <p>缺 `min-vl` 的条目直接丢弃（没有分档依据），`action` 缺省为 `alert`。</p>
     */
    @JvmStatic
    fun parseSteps(raw: List<Map<*, *>>?): List<LadderStep> {
        if (raw.isNullOrEmpty()) return emptyList()
        val parsed = ArrayList<LadderStep>(raw.size)
        for (entry in raw) {
            val minVl = (entry["min-vl"] as? Number)?.toDouble() ?: continue
            val action = (entry["action"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: "alert"
            val duration = (entry["duration"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val reason = (entry["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            parsed.add(LadderStep(0, minVl, action, duration, reason))
        }
        parsed.sortBy { it.minVl }
        return parsed.mapIndexed { index, step ->
            LadderStep(index + 1, step.minVl, step.action, step.duration, step.reason)
        }
    }

    /**
     * 选出这次该用哪一档：**第几次被抓决定上限，VL 决定够不够格**。
     *
     * <h3>为什么不能按"VL 落在哪个区间"分档</h3>
     * VL 是**会话内**的量：玩家被踢下线后重连，检测实例重建、VL 归零。
     * 若按 VL 分档，只要"踢"这一档比"封"低，被踢的人重连后 VL 归零
     * → **永远到不了封禁档**，阶梯成了摆设（踢—重连—再踢的死循环）。
     * 所以升档依据必须是**跨会话的计数**，由 `PlayerData.punishmentCount` 给出。
     *
     * <p>`min-vl` 退化为**该档的证据门槛**：越重的处罚要求越高的 VL。
     * 第 N 次被抓时只在**前 N 档**里挑"VL 够格"的最高一档——
     * 所以第 3 次被抓但 VL 只有 9 时会退回第 1 档，而不是凭空给重罚。</p>
     *
     * @param offenseIndex 这是该玩家第几次被抓（从 1 开始）；超过档数时封顶在最后一档
     * @return null = 本次不够格处罚（VL 未达任何一档的门槛）
     */
    @JvmStatic
    fun selectStep(steps: List<LadderStep>?, vl: Double, offenseIndex: Int): LadderStep? {
        if (steps.isNullOrEmpty()) return null
        val ordered = steps.sortedBy { it.step }
        val capped = offenseIndex.coerceIn(1, ordered.size)
        var best: LadderStep? = null
        for (step in ordered.take(capped)) {
            if (vl >= step.minVl && (best == null || step.step > best.step)) {
                best = step
            }
        }
        return best
    }

    /** 展开 `%player% / %check% / %vl% / %duration%`。 */
    @JvmStatic
    fun expandTemplate(
        template: String,
        playerName: String,
        checkName: String,
        vl: Double,
        duration: String?
    ): String = template
        .replace("%player%", playerName)
        .replace("%check%", checkName)
        .replace("%vl%", String.format(Locale.ROOT, "%.2f", vl))
        .replace("%duration%", describeDuration(duration))

    /** 阶梯的一句话描述（写进启动/维护日志：让"库里到底几档、各档什么动作"可见）。 */
    @JvmStatic
    fun describe(steps: List<LadderStep>?): String {
        if (steps.isNullOrEmpty()) return "无（超阈值即按 punishment.action 处理）"
        val builder = StringBuilder()
        for (step in steps.sortedBy { it.step }) {
            if (builder.isNotEmpty()) builder.append(" > ")
            builder.append("第").append(step.step).append("次(")
                .append(String.format(Locale.ROOT, "%.1f", step.minVl)).append(")→").append(step.action)
            if (step.action == "ban") {
                builder.append("(").append(describeDuration(step.duration)).append(")")
            }
        }
        return builder.toString()
    }
}

/**
 * config 里声明的一条白名单（由字符串或映射解析而来）。
 *
 * [uuid] 与 [name] 至少有一个非空——解析时两者都取不到就整条丢弃。
 */
class WhitelistSeed(
    val uuid: UUID?,
    val name: String?,
    val reason: String?,
    /** null = 永久。 */
    val expiresAt: Long?
)

/** config `core.whitelist` 的纯解析。 */
object WhitelistPolicy {

    /**
     * 解析 `core.whitelist`。
     *
     * <p>两种写法都支持：</p>
     * - 字符串：`"Notch"` 或 `"069a79f4-44e9-4726-a5be-fca90e38aaf5"`（自动识别）；
     * - 映射：`{ uuid/name, reason, expires-in }`，`expires-in` 用与惩罚时长同一套写法。
     *
     * <p>`expires-in` 写错（[LadderPolicy.INVALID_MILLIS]）时按**永久**处理：
     * 白名单的方向是"放行"，把它变成"立刻过期"会让管理员以为白名单失效，更难排查；
     * 写错这件事由 `describeDuration` 在日志里显式暴露。</p>
     */
    @JvmStatic
    fun parse(raw: List<*>?, nowMillis: Long): List<WhitelistSeed> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = ArrayList<WhitelistSeed>(raw.size)
        for (item in raw) {
            when (item) {
                is String -> {
                    val text = item.trim()
                    if (text.isEmpty()) continue
                    val uuid = uuidOrNull(text)
                    out.add(WhitelistSeed(uuid, if (uuid == null) text else null, "config.yml", null))
                }
                is Map<*, *> -> {
                    val name = (item["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val uuidText = (item["uuid"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val uuid = uuidText?.let { uuidOrNull(it) }
                    if (name == null && uuid == null) continue
                    val reason = (item["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "config.yml"
                    val expiresText = (item["expires-in"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val expiresAt = expiresText?.let { LadderPolicy.parseDurationMillis(it) }
                        ?.takeIf { it > 0L }
                        ?.let { nowMillis + it }
                    out.add(WhitelistSeed(uuid, name, reason, expiresAt))
                }
                else -> continue
            }
        }
        return out
    }

    private fun uuidOrNull(text: String): UUID? = try {
        UUID.fromString(text)
    } catch (t: Throwable) {
        null
    }
}
