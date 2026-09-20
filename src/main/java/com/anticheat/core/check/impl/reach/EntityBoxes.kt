package com.anticheat.core.check.impl.reach

import com.anticheat.core.platform.api.entity.ServerEntitySnapshot
import kotlin.math.max

/**
 * 实体的**命中盒**尺寸表。
 *
 * <p>为什么不问服务端要：Bukkit 的 `Entity#getBoundingBox()` 在 1.8.8 **不存在**
 * （1.9 才加入），而我们必须在两个版本上跑同一套逻辑。命中盒尺寸本身也是
 * 游戏规则而不是平台能力，放在核心层更容易解释与测试。</p>
 *
 * <h3>采用"不小于默认盒"的偏置（这是本类唯一的规则）</h3>
 * 盒子的水平/垂直范围是**从距离里减掉**的：盒子越大 → 算出的距离越小 → 越宽容；
 * 盒子偏小会让距离虚大 → **直接造成误报**。所以对**非玩家实体**，
 * 最终生效的盒子永远是"真实尺寸"与"默认盒"的**逐轴最大值**：
 *
 * ```
 * halfWidth = max(真实半宽, 0.8)
 * height    = max(真实高度, 2.2)
 * ```
 *
 * <p>这样做的收益是**结构性地消除了一个整类的误报**：不需要逐个核对
 * "宽而扁的蜘蛛"（真实 1.4×0.9）会不会因为盒子比默认小而被误判——
 * 代码不可能给出比默认更小的盒子。代价是打矮小生物（鸡、蝙蝠）时会漏判，
 * 那属于可接受的方向。</p>
 *
 * <h3>玩家为什么是例外</h3>
 * 玩家的命中盒是**确切已知**的 0.6 × 1.8，不是估计值；而且 PvP 是本检测的
 * 主要场景——如果给玩家也用放大到 0.8 × 2.2 的盒子，等效额外容差就有
 * 约 0.6 格，叠加显式容差后阈值会宽到什么都抓不到。因此玩家用精确盒，
 * 安全边际交给 `ReachA.tolerance` 显式控制（比藏在盒子尺寸里更容易解释与调参）。</p>
 *
 * <p>舍入方向：姿态会让盒子变小（潜行 1.5 / 游泳与鞘翅 0.6），
 * 这里**一律按站立 1.8 计**——那是更大的盒子，方向安全。</p>
 */
object EntityBoxes {

    /** 命中盒：水平半宽 + 高度，点为**脚底中心**。 */
    class Box(val halfWidth: Double, val height: Double) {

        /** 逐轴取最大，用于保证"不小于默认盒"。 */
        internal fun atLeast(other: Box): Box =
            Box(max(halfWidth, other.halfWidth), max(height, other.height))

        override fun toString(): String = "Box(${halfWidth}x$height)"
    }

    /** 默认盒：明显偏大，保证未列出的实体不会因盒子偏小而被误判。 */
    private val DEFAULT = Box(0.8, 2.2)

    /** 玩家精确盒（原版 0.6 × 1.8）。 */
    private val PLAYER = Box(0.3, 1.8)

    /**
     * 仅收录**在某一个轴上比默认盒更大**的实体。
     *
     * <p>比默认盒小的实体不需要出现——[of] 会自动用默认盒兜住，
     * 写进来反而是负收益（会把盒子变小）。</p>
     */
    private val TALLER_OR_WIDER: Map<String, Box> = mapOf(
        // 高：默认盒的 2.2 不够
        "IRON_GOLEM" to Box(0.7, 2.7),
        "ENDERMAN" to Box(0.3, 2.9),
        "WARDEN" to Box(0.45, 2.9),
        "WITHER_SKELETON" to Box(0.4, 2.4),
        "WITHER" to Box(0.45, 3.5),
        "ENDER_DRAGON" to Box(8.0, 8.0),
        // 宽：默认盒的 0.8 不够
        "RAVAGER" to Box(1.0, 2.2),
        "END_CRYSTAL" to Box(1.0, 2.0),
        "GHAST" to Box(2.0, 4.0)
    )

    /**
     * 取某个实体的命中盒（已套用"不小于默认盒"的偏置）。
     *
     * @param snapshot 平台层的实体快照；[ServerEntitySnapshot.isPlayer] 优先于类型名，
     *   因为某些服务端/代理会把玩家类型名改写
     */
    @JvmStatic
    fun of(snapshot: ServerEntitySnapshot): Box = ofType(snapshot.typeName, snapshot.isPlayer)

    @JvmStatic
    fun ofType(typeName: String, isPlayer: Boolean): Box {
        if (isPlayer) return PLAYER
        val real = TALLER_OR_WIDER[typeName] ?: return DEFAULT
        return real.atLeast(DEFAULT)
    }

    /** 供测试与排障读取默认盒。 */
    @JvmStatic
    fun defaultBox(): Box = DEFAULT

    /** 供测试与排障读取玩家盒。 */
    @JvmStatic
    fun playerBox(): Box = PLAYER

    /** 供测试枚举"显式收录"的类型（用于回归守卫）。 */
    @JvmStatic
    fun explicitlyListedTypes(): Set<String> = TALLER_OR_WIDER.keys

    /** 仅用于测试：某个显式条目未经偏置的原始尺寸。 */
    @JvmStatic
    fun rawBoxOf(typeName: String): Box? = TALLER_OR_WIDER[typeName]
}
