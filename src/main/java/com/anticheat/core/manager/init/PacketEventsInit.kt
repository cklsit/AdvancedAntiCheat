package com.anticheat.core.manager.init

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.util.CoreLog
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder

/**
 * PacketEvents 通道注入。对齐 Grim 的 `PacketEventsInit`。
 *
 * <p>这是整个核心层唯一真正「动服务端网络栈」的地方：PE 会把自己挂进 Netty 管道，
 * 因此它必须最早加载、最后终止。</p>
 *
 * <p>几处配置是刻意的：</p>
 * - `kickOnPacketException(false)`：PE 内部解码异常时**不要踢玩家**。1.8 老服务端上
 *   某些自定义包/模组包会触发解码异常，踢人会造成大面积误伤；
 * - `kickIfTerminated(false)`：PE 被 terminate 后不要让后续包触发踢人；
 * - `reEncodeByDefault(false)`：本骨架不修改包内容，跳过重编码省一次序列化；
 * - `bStats(false)` / `checkForUpdates(false)`：本项目要求零外部依赖与零外联。</p>
 */
class PacketEventsInit : LoadableInitable, StartableInitable {

    override fun load() {
        val settings = PacketEventsSettings()
            .checkForUpdates(false)
            .bStats(false)
            .reEncodeByDefault(false)
            .kickOnPacketException(false)
            .kickIfTerminated(false)

        val api = SpigotPacketEventsBuilder.build(AntiCheatCore.plugin, settings)
        PacketEvents.setAPI(api)
        api.load()
        AntiCheatCore.packetEvents = api
        CoreLog.info("PacketEvents 已装载，等待接管网络通道")
    }

    override fun start() {
        val api = AntiCheatCore.packetEvents
            ?: throw IllegalStateException("PacketEvents 未装载，无法初始化")
        api.init()
        CoreLog.info("PacketEvents 已初始化（协议版本 " + api.version + "）")
    }
}
