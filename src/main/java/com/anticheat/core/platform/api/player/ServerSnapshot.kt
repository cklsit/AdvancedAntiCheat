package com.anticheat.core.platform.api.player

/**
 * 服务端权威状态快照。
 *
 * <p>刻意用裸值而不是平台的 Location/World 类型：核心层不该认识 Bukkit 的类。
 * 之所以需要它，是因为客户端上报的位置包在 1.8 里**不含世界名**，
 * 而 setback 与「客户端到底有没有撒谎」都必须基于服务端权威值。</p>
 *
 * <p>后面几个字段是为了消除假阳性才加的，都很关键：</p>
 * - [eyeX]/[eyeY]/[eyeZ]：**服务端算出的眼睛位置**。伸手距离（reach）与视线类判据
 *   必须从眼睛出发，而眼球高度随姿态变化（站立 1.62 / 潜行 1.54 / 爬行 0.4 / 鞘翅 0.4），
 *   自己去猜高度会直接造成误报。这里直接取服务端的 `getEyeLocation()`，一次算准。
 * - [vehicleEntityId]：骑乘中的实体 id（[NO_VEHICLE] 表示没有）。移动包节奏由**载具**
 *   驱动而非玩家客户端时钟，计时器类判据必须对骑乘玩家让路。
 * - [gliding]：是否在滑翔。鞘翅状态下的移动包节奏与眼球高度都与常规不同。
 *
 * <p>移动类检测（`check/impl/movement`）还依赖下面四个"为什么这个位移是合法的"上下文。
 * 它们**只会让检测让路、绝不会让检测触发**，因此取值的偏向是"宁可多让路"：
 * 任何一项算错的方向都是漏判而不是误封。为什么必须在平台层算而不能在检测里猜——
 * 这四项都要读方块 / 药水 / 游戏模式，全是平台能力；检测里去猜等价于把误报写死。</p>
 *
 * - [flightAllowed]：**服务端允许这个玩家飞行**。创造模式、旁观模式，以及
 *   被插件 `/fly` 授予飞行权限的玩家都为 true。这是飞行类检测的头号误报来源——
 *   大厅服普遍给玩家开飞行，只看"在空中不下落"会把整个大厅的人判成作弊。
 * - [inLiquid]：脚部或眼睛所在方块是水 / 岩浆。游泳、上浮、水中下沉都不遵循重力模型。
 * - [movementAlteredByBlock]：脚部方块会改写垂直运动（梯子 / 藤蔓 / 蜘蛛网 /
 *   脚手架 / 细雪 / 甜浆果丛 / 竹子）。这些方块里"匀速上升或匀速缓慢下落"是原版行为。
 * - [movementEffectActive]：身上带着会改写移动的药水效果（漂浮 / 缓降 / 跳跃提升 /
 *   迅捷 / 海豚的恩惠）。缓降的每 tick 重力只有 0.01，与作弊的悬停在数值上无法区分。
 */
class ServerSnapshot(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
    val eyeX: Double,
    val eyeY: Double,
    val eyeZ: Double,
    val vehicleEntityId: Int,
    val gliding: Boolean,
    val flightAllowed: Boolean,
    val inLiquid: Boolean,
    val movementAlteredByBlock: Boolean,
    val movementEffectActive: Boolean
) {

    /** 是否骑乘在某个实体上。 */
    val inVehicle: Boolean get() = vehicleEntityId > NO_VEHICLE

    companion object {
        /** 无载具时的 [vehicleEntityId]。 */
        const val NO_VEHICLE = -1
    }
}
