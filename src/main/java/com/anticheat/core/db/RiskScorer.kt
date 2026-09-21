package com.anticheat.core.db

import kotlin.math.exp
import kotlin.math.ln

/**
 * 玩家风险评分（纯逻辑，可离线单测）。
 *
 * <h3>为什么自己做而不是写一条 SQL</h3>
 * 评分是**策略**，会被反复调整（权重、半衰期、饱和曲线）。写成 SQL 之后再想改，
 * 就只能改迁移里的字符串——既没有单测，也没法离线复算历史数据。
 * 放在这里则可以用同一份实现对"当前在线玩家的增量更新"和"离线批量重算"，
 * 两条路径的分数口径不会漂。
 *
 * <h3>公式</h3>
 * ```
 * 单次违规的权重 = 0.5 × severity × (1 + min(vlDelta, 4) / 4)          // 0.5 ~ 2.5
 * 时间衰减       = 0.5 ^ (距今年龄小时 / 半衰期小时)                     // 半衰期 6 小时
 * 原始值 raw     = Σ 权重 × 衰减
 * 最终分数       = 100 × (1 - e^(-raw / 6))                            // 饱和，永不到 100
 * ```
 * 三点取舍：
 * 1. **用饱和度而不是线性**：违规次数可以很大，线性会让"一次误判 + 一万条日志"
 *    直接顶满，分数就失去分辨力；
 * 2. **半衰期 6 小时**：一个玩家今天不再出问题，一天后分数应基本归零
 *    （0.5^4 ≈ 6%），否则"历史脏"会永远跟着他；
 * 3. **severity 参与权重**：协议级违规（原版不可能产生）比统计推断更该被重罚。
 */
object RiskScorer {

    const val MAX_SCORE = 100.0

    /** 衰减半衰期（小时）。 */
    const val HALF_LIFE_HOURS = 6.0

    /** 饱和系数：raw 达到该值时分数约 63。 */
    const val SATURATION = 6.0

    /** severity 的上限（4 档）。 */
    const val MAX_SEVERITY = 4

    /**
     * 由"本次增加的违规分"与是否实验性检测推出严重度（1~4）。
     *
     * <p>实验性检测（`@CheckData(experimental = true)`）**封顶到 2**：
     * 它们默认不参与判定，真被打开时也属于"还在观察"，不应该把玩家风险拉到高位。</p>
     */
    @JvmStatic
    fun severityOf(vlDelta: Double, experimental: Boolean): Int {
        val base = when {
            vlDelta >= 2.0 -> 4
            vlDelta >= 1.0 -> 3
            vlDelta >= 0.4 -> 2
            else -> 1
        }
        return if (experimental) minOf(base, 2) else base
    }

    /** 单次违规的权重（0.5 ~ 2.5）。 */
    @JvmStatic
    fun weightOf(event: RiskEvent): Double {
        val severity = event.severity.coerceIn(1, MAX_SEVERITY)
        val volume = 1.0 + (event.vlDelta.coerceIn(0.0, 4.0) / 4.0)
        return 0.5 * severity * volume
    }

    /**
     * 计算风险分数。
     *
     * @param events 时间窗口内的违规事件（窗口外的请先过滤掉）
     * @param nowMillis 当前时间
     * @return 0 ~ 100
     */
    @JvmStatic
    fun score(events: List<RiskEvent>, nowMillis: Long): Double {
        if (events.isEmpty()) return 0.0
        var raw = 0.0
        for (event in events) {
            val ageHours = ((nowMillis - event.atMillis).coerceAtLeast(0L)).toDouble() / 3_600_000.0
            val decay = exp(-ageHours * ln(2.0) / HALF_LIFE_HOURS)
            raw += weightOf(event) * decay
        }
        return (MAX_SCORE * (1.0 - exp(-raw / SATURATION))).coerceIn(0.0, MAX_SCORE)
    }

    /** 分数分档（用于日志/看板文案）。 */
    @JvmStatic
    fun band(score: Double): String = when {
        score < 10.0 -> "低"
        score < 30.0 -> "中"
        score < 60.0 -> "高"
        else -> "极高"
    }

    /** 一行描述，写进告警/日志。 */
    @JvmStatic
    fun describe(score: Double): String = String.format("%.1f(%s)", score, band(score))
}

/**
 * 用户名历史的合并逻辑（纯逻辑，可离线单测）。
 *
 * <p>规则只有三条，但每条错了都会让"改过名的玩家"关联不上历史行为：</p>
 * 1. 历史里只放**旧名**（当前名由 `name` 列单独持有）；
 * 2. 新出现的名字放在最前（最近用过 → 最新在前）；
 * 3. 去重，但保留第一次出现的位置（避免 `A→B→A` 之后历史变成 `[A, B, A]`）。
 */
object NameHistory {

    /** 历史里最多保留多少个旧名，防止改名狂魔把数组撑爆。 */
    const val MAX_ENTRIES = 16

    @JvmStatic
    fun merge(history: List<String>, currentName: String?, newName: String): List<String> {
        val merged = LinkedHashSet<String>(history.size + 2)
        // 先放"上一个名字"（如果与当前名字不同，它就已经变成历史了）
        if (!currentName.isNullOrEmpty() && currentName != newName) {
            merged.add(currentName)
        }
        for (entry in history) {
            if (entry.isNotEmpty() && entry != newName) {
                merged.add(entry)
            }
        }
        return merged.take(MAX_ENTRIES)
    }
}
