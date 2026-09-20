package com.anticheat.core.check.type

import com.anticheat.core.util.update.SwingUpdate

/**
 * 挥手监听（`ANIMATION` 包）。运行在 **Netty 网络线程**。
 *
 * <p>挥手同时由「左键点击」「挖掘方块」「放置方块」触发。
 * 只有把它与挖掘/放置状态交叉比对之后，才能当成"点击"使用——
 * 直接用挥手间隔做自动点击检测会把正常挖矿的玩家判成作弊。</p>
 */
interface SwingListener {

    fun onSwing(update: SwingUpdate)
}
