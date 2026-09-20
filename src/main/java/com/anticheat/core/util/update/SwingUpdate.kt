package com.anticheat.core.util.update

import com.github.retrooper.packetevents.protocol.player.InteractionHand

/**
 * 一次挥手（`ANIMATION` 包）。
 *
 * <p>为什么自动点击类检测看挥手而不是看攻击包：</p>
 * - 攻击包只在**真的打到实体**时才有意义，空挥不出包，采样会断断续续；
 * - 挥手包是每次左键都发，采样连续，间隔统计才有意义。
 *
 * <p>但挥手不等于点击：**挖掘方块与放置方块同样会挥手**。所有基于挥手间隔的
 * 检测都必须先用 `player.inDiggingNoiseWindow()` 去噪，否则挖矿会把
 * 玩家自己的挥臂节奏算成"自动点击"。</p>
 *
 * <p>[sinceLastSwingMillis] 是**上一次挥手到本次**的时间差，由包层在更新状态
 * **之前**算好带进来。它必须放在这里而不是让检测自己去读 `player.lastSwingMillis`：
 * 包层为保证状态一致性会先写时间戳再派发，那样检测读到的间隔恒为 0。</p>
 *
 * @param sinceLastSwingMillis 从未挥过手时为 `Long.MAX_VALUE`（检测应据此中断采样）
 */
class SwingUpdate(
    val hand: InteractionHand,
    val sinceLastSwingMillis: Long
) {

    /** 这次挥手是否与前一次构成一个"可用的点击间隔"。 */
    val hasInterval: Boolean
        get() = sinceLastSwingMillis != Long.MAX_VALUE && sinceLastSwingMillis > 0L

    override fun toString(): String =
        "SwingUpdate(hand=" + hand + ", sinceLast=" + sinceLastSwingMillis + "ms)"
}
