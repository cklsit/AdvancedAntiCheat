package com.anticheat.core.platform.bukkit

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import kotlin.math.floor

/**
 * 移动类检测的**上下文探测**：方块、药水效果、飞行许可。
 *
 * <p>为什么单独一个类：这四项全都要读平台状态，而移动类判据必须靠它们让路。
 * 少了任何一项，对应的检测就会在合法场景里成批误报——
 * 例如没有"允许飞行"这一项，大厅服里每个开了 `/fly` 的玩家都会被飞行检测判成作弊。</p>
 *
 * <p>跨版本铁律同 [BukkitEntityCompat]：只用 1.8.8 与 1.21 都存在的方法名。
 * 高版本才有的东西（细雪、脚手架、缓降效果）用**材质名字符串**与
 * `PotionEffectType.getByName` 兜住——名字在低版本上解析不到就等于"该版本没有这种东西"，
 * 语义正确且不会抛 `NoSuchMethodError`。</p>
 *
 * <p>取值的偏向是**宁可多让路**：这四个标志只被检测用来"跳过判定"，
 * 因此任何一个算错的方向都是漏判，而不是误封。</p>
 */
internal object BukkitMovementCompat {

    /**
     * 会改写垂直运动的方块材质名。
     *
     * <p>老名字与新名字都列进来（1.8 的蜘蛛网叫 `WEB`，1.13+ 叫 `COBWEB`）：
     * 不存在的名字永远不会匹配上，所以这份表可以直接按并集写，不必分版本。</p>
     *
     * <p>为什么是这些：它们要么让你**匀速上升**（梯子、藤蔓、竹子、脚手架攀爬），
     * 要么让你的下落**远慢于重力**（蜘蛛网、细雪、甜浆果丛）。
     * 这两种运动在数值上都与"悬停式飞行"无法区分，只能靠方块上下文让路。</p>
     */
    private val MOVEMENT_ALTERING_MATERIALS: Set<String> = setOf(
        "LADDER",
        "VINE",
        "WEB", "COBWEB",
        "BAMBOO",
        "SCAFFOLDING",
        "POWDER_SNOW",
        "SWEET_BERRY_BUSH",
        "WEEPING_VINES", "WEEPING_VINES_PLANT",
        "TWISTING_VINES", "TWISTING_VINES_PLANT",
        "CAVE_VINES", "CAVE_VINES_PLANT"
    )

    /**
     * 会改写移动的药水效果。
     *
     * <p>除 `SPEED` 外全部走 `getByName`：**跳跃提升的常量名在版本之间改过**
     * （1.8~1.20.4 是 `JUMP`，1.20.5+ 是 `JUMP_BOOST`），而本项目的编译类路径上
     * 同时挂着 paper-api（新）与 spigot-api 1.8.8（旧），直接写常量名会有一边解析不到。
     * 用字符串查表则两边都能命中，查不到就是 null，被 `listOfNotNull` 滤掉——
     * 语义正好等于"这个版本上没有这种效果"。</p>
     *
     * <p>`LEVITATION`（1.9+）、`SLOW_FALLING`（1.13+）、`DOLPHINS_GRACE`（1.13+）同理。
     * 缓降的每 tick 重力只有 0.01，与作弊悬停的数值特征几乎一样，是必须让路的一项。</p>
     *
     * <p>代价：喝了迅捷药水的玩家会整体免疫速度检测。这是刻意选的——
     * 迅捷 II 能把合法地面速度抬到与部分 Speed 模式重合，不给它让路就是误封。</p>
     */
    private val MOVEMENT_EFFECTS: List<PotionEffectType> = listOfNotNull(
        PotionEffectType.SPEED,
        byName("JUMP"),
        byName("JUMP_BOOST"),
        byName("LEVITATION"),
        byName("SLOW_FALLING"),
        byName("DOLPHINS_GRACE")
    )

    private fun byName(name: String): PotionEffectType? =
        runCatching { PotionEffectType.getByName(name) }.getOrNull()

    /**
     * 服务端是否**允许**这个玩家飞行。
     *
     * <p>这一项同时覆盖三种情况：创造模式、旁观模式、以及被插件 `/fly` 授予飞行。
     * 用 `getAllowFlight()` 而不是去比游戏模式，正是因为第三种情况——
     * 大厅 / 建筑服普遍在生存模式下给玩家开飞行，只看游戏模式会整批误封。</p>
     */
    fun isFlightAllowed(player: Player): Boolean =
        runCatching { player.allowFlight }.getOrDefault(false)

    /**
     * 脚部或眼睛所在方块是否是液体。
     *
     * <p>两处都要看：齐腰深的水里脚部方块是水，而站在池边探身时只有眼睛那格在水下；
     * 只看脚部会漏掉"在水面上一格悬停"这种合法姿态（游泳上浮的过渡帧）。</p>
     */
    fun isInLiquid(feet: Block?, eye: Block?): Boolean =
        isLiquidBlock(feet) || isLiquidBlock(eye)

    /** 脚部或眼睛所在方块是否会改写垂直运动（见 [MOVEMENT_ALTERING_MATERIALS]）。 */
    fun isMovementAlteredByBlock(feet: Block?, eye: Block?): Boolean =
        altersMovement(feet) || altersMovement(eye)

    /**
     * 是否带着会改写移动的药水效果。
     *
     * <p>只取**一次** `getActivePotionEffects()` 再线性比对：
     * CraftBukkit 的 `hasPotionEffect` 内部也是遍历 `getActivePotionEffects()`，
     * 而后者每次调用都新建一个集合。这个探测是**每玩家每 tick** 一次，
     * 逐个效果去问等于把同一份集合重复构造五遍。
     * 表最多 5 项、玩家身上通常 0~2 个效果，线性比对足够，不必上 Set。</p>
     */
    fun hasMovementEffect(player: Player): Boolean {
        val active = runCatching { player.activePotionEffects }.getOrNull() ?: return false
        if (active.isEmpty()) return false
        for (effect in active) {
            val type = runCatching { effect.type }.getOrNull() ?: continue
            for (watched in MOVEMENT_EFFECTS) {
                if (watched == type) return true
            }
        }
        return false
    }

    private fun isLiquidBlock(block: Block?): Boolean =
        block != null && runCatching { block.isLiquid }.getOrDefault(false)

    private fun altersMovement(block: Block?): Boolean {
        if (block == null) return false
        // 材质名比较刻意用 String：`Material.COBWEB` 这类常量在 1.8 上不存在，
        // 直接引用会让类初始化时就抛 NoSuchFieldError，runCatching 也来不及兜。
        val name = runCatching { block.type.name }.getOrNull() ?: return false
        return name in MOVEMENT_ALTERING_MATERIALS
    }

    /**
     * 玩家脚下 [depth] 格内是否有可站立的固体方块
     * （见 [com.anticheat.core.platform.api.player.PlatformPlayer.hasGroundSupport]）。
     *
     * <p>两处"取不到信息就当合法"的让路是刻意的：</p>
     * - **区块未加载**：不仅无从判断，而且**绝不能顺手把区块加载进来**——
     *   这个探测每 tick 每玩家一次，触发区块加载等于给客户端开了一条按需拉世界的通道；
     * - **方块 / 材质读取失败**：同样往合法方向让路。
     *
     * <p>探测范围从 `floor(y) - 1` 开始：脚正好落在整数格上时（y = 64.0），
     * 承托它的是方块 63（顶面在 64.0）；脚在方块内部时（y = 64.5），
     * 承托它的同样是 63。两种情况起点一致，不必分支。</p>
     */
    fun hasGroundSupport(player: Player, depth: Double): Boolean {
        if (!runCatching { player.isOnline }.getOrDefault(false)) return true
        val location = runCatching { player.location }.getOrNull() ?: return true
        val world = runCatching { location.world }.getOrNull() ?: return true
        val blockX = location.blockX
        val blockZ = location.blockZ
        if (!runCatching { world.isChunkLoaded(blockX shr 4, blockZ shr 4) }.getOrDefault(false)) return true

        val y = location.y
        val top = floor(y).toInt() - 1
        val bottom = floor(y - depth).toInt()
        // 探测格数封顶：depth 是可配置的，写成一个巨大的值不该把主线程变成逐格扫描
        val limit = (top - bottom).coerceAtMost(MAX_SUPPORT_PROBE_BLOCKS)
        for (offset in 0..limit) {
            val block = runCatching { world.getBlockAt(blockX, top - offset, blockZ) }.getOrNull() ?: continue
            val material = runCatching { block.type }.getOrNull() ?: continue
            if (runCatching { material.isSolid }.getOrDefault(false)) return true
        }
        return false
    }

    /** 支撑探测的最大格数。 */
    private const val MAX_SUPPORT_PROBE_BLOCKS = 8
}
