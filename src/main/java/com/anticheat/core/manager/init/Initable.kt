package com.anticheat.core.manager.init

/**
 * 生命周期标记接口。对齐 Grim 的三段式：load → start → stop。
 *
 * <p>为什么拆成三段而不是一个 `enable()`：
 * `load` 阶段（插件 onLoad/onEnable 早期）只允许做「不依赖服务端就绪」的准备工作——
 * 尤其是 PacketEvents 的通道注入，必须赶在玩家连接之前完成；
 * `start` 阶段才注册监听器、起定时任务。合在一起会让注入时机随插件加载顺序漂移。</p>
 */
interface Initable

/** 需要在服务端就绪前完成（例如网络通道注入）。 */
interface LoadableInitable : Initable {
    fun load()
}

/** 服务端就绪后的启动动作（注册监听器、起调度）。 */
interface StartableInitable : Initable {
    fun start()
}

/** 卸载动作。 */
interface StoppableInitable : Initable {
    fun stop()
}
