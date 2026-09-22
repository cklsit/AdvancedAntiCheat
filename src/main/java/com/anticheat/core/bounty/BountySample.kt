package com.anticheat.core.bounty

/**
 * 赏金沙箱里的一次行为采样。
 *
 * <p>刻意做成**扁平的、不含任何平台类型的值对象**：采样由 Bukkit 侧在主线程填充，
 * 而基线建模与判定是纯计算——把它做成纯值对象，判定逻辑才能脱离服务端离线单测
 * （见 `BountyLogicTest`）。这与 `ViolationData` / `TimerBalance` 的做法一致。</p>
 *
 * @param seq 采样序号，**单调递增、每 tick 加一**（不要用墙钟毫秒）。
 *   理由：所有指标都是"逐步差分"，而 TPS 波动会让同样 1 tick 的间隔在墙钟上忽长忽短，
 *   于是"服务器卡顿"会被算成"玩家剧烈抖动"——那是给作弊者送清白。
 * @param x 服务端权威坐标（不要用客户端上报值：那恰好是我们要检验的东西）
 * @param attacked 本 tick 是否发生过攻击/挥臂
 */
class BountySample(
    val seq: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
    val attacked: Boolean
)
