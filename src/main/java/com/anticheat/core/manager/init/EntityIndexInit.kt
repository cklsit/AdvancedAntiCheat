package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog

/**
 * 实体索引的启停。
 *
 * <p>实体位置查询（伸手 / 视线 / 命中率）每 tick 每目标都要做一次，
 * 而 1.8.8 没有 `getEntity(int)`——必须靠索引把全量遍历摊平成增量。
 * 索引的生命周期因此要和检测链路对齐：**先于 TickRunner 启动，后于它停止**。</p>
 *
 * <p>失败即降级：索引建不起来时平台层会退回"未命中即跳过"，
 * 检测能力变弱但不会误判，插件其余部分照常工作。</p>
 */
class EntityIndexInit : StartableInitable, StoppableInitable {

    private var started = false

    override fun start() {
        if (started) return
        started = true
        runCatching { AntiCheatCore.platformServer.beginEntityTracking() }
            .onFailure { CoreLog.warn("实体索引启动失败，伸手/视线类判据将跳过判定: " + it.message) }
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching { AntiCheatCore.platformServer.endEntityTracking() }
            .onFailure { CoreLog.debug("实体索引停止异常: " + it.message) }
    }
}
