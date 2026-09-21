package com.anticheat.core.manager

import com.anticheat.core.manager.init.DatabaseInit
import com.anticheat.core.manager.init.EntityIndexInit
import com.anticheat.core.manager.init.Initable
import com.anticheat.core.manager.init.LoadableInitable
import com.anticheat.core.manager.init.PacketEventsInit
import com.anticheat.core.manager.init.PacketManager
import com.anticheat.core.manager.init.StartableInitable
import com.anticheat.core.manager.init.StoppableInitable
import com.anticheat.core.manager.init.TerminatePacketEvents
import com.anticheat.core.manager.init.TickRunner
import com.anticheat.core.util.CoreLog

/**
 * 三段式生命周期编排。对齐 Grim 的 `InitManager`。
 *
 * <p>两个刻意的设计：</p>
 * 1. **单点失败不阻断整体**：每个 initializer 独立 try/catch。反作弊的核心能力
 *    （包层）挂掉时，仍应让告警/统计等其它部分尽量起来，并把失败原因写进日志，
 *    而不是整个插件被禁用。
 * 2. **stop 逆序执行**：后启动的先关闭。TickRunner 必须早于 PacketEvents 终止，
 *    否则会在已拆卸的通道上继续读数。
 */
class InitManager(extraInitables: List<Initable> = emptyList()) {

    private val loadInitables = ArrayList<LoadableInitable>()
    private val startInitables = ArrayList<StartableInitable>()
    private val stopInitables = ArrayList<StoppableInitable>()

    var loaded: Boolean = false
        private set

    var started: Boolean = false
        private set

    var stopped: Boolean = false
        private set

    init {
        distribute(PacketEventsInit())
        distribute(PacketManager())
        // 数据库：start 阶段建池/迁移，stop 阶段（逆序时最先）刷残留数据再关池。
        // 放在 TickRunner 之前只是为了让"启动日志顺序"好看，功能上无依赖
        distribute(DatabaseInit())
        // 必须早于 TickRunner：检测在 onServerTick 里就要查实体位置
        distribute(EntityIndexInit())
        distribute(TickRunner())
        distribute(TerminatePacketEvents())
        for (extra in extraInitables) distribute(extra)
    }

    private fun distribute(initable: Initable) {
        if (initable is LoadableInitable) loadInitables.add(initable)
        if (initable is StartableInitable) startInitables.add(initable)
        if (initable is StoppableInitable) stopInitables.add(initable)
    }

    fun load() {
        for (initable in loadInitables) {
            try {
                initable.load()
            } catch (t: Throwable) {
                CoreLog.error("load 阶段失败: " + initable.javaClass.simpleName + " - " + t.message, t)
            }
        }
        loaded = true
    }

    fun start() {
        for (initable in startInitables) {
            try {
                initable.start()
            } catch (t: Throwable) {
                CoreLog.error("start 阶段失败: " + initable.javaClass.simpleName + " - " + t.message, t)
            }
        }
        started = true
    }

    fun stop() {
        for (initable in stopInitables.asReversed()) {
            try {
                initable.stop()
            } catch (t: Throwable) {
                CoreLog.error("stop 阶段失败: " + initable.javaClass.simpleName + " - " + t.message, t)
            }
        }
        stopped = true
    }
}
