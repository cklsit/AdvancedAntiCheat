package com.anticheat.integration;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.network.ProtocolValidator;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * ProtocolLibHook —— ProtocolLib 软依赖集成钩子。
 *
 * <p>仅在 ProtocolLib 已安装时实例化（由 {@code AdvancedDetectionManager} 用
 * {@code Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")} 守卫）。提供两类能力：</p>
 * <ul>
 *   <li><b>底层包校验 + 微时序</b>：监听所有 Client Play 包，把原始 NMS 包交给
 *       {@link ProtocolValidator#validatePacketStructure} 做字段级校验（非法数据包结构），
 *       并记录包时间戳（{@code recordPacketTiming}）驱动假延迟/时钟漂移检测。</li>
 *   <li><b>协议级假方块</b>：发送 Block Change 包实现客户端单侧渲染假方块（渲染距离验证）。</li>
 * </ul>
 */
public class ProtocolLibHook implements ProtocolIntegration {

    private final AdvancedAntiCheat plugin;
    private final ProtocolValidator validator;
    private final ProtocolManager manager;

    public ProtocolLibHook(AdvancedAntiCheat plugin, ProtocolValidator validator) {
        this.plugin = plugin;
        this.validator = validator;
        this.manager = ProtocolLibrary.getProtocolManager();
        registerPacketListeners();
    }

    private void registerPacketListeners() {
        // 监听所有 Client Play 包：非法数据包结构 + 微时序（假延迟/时钟漂移）
        manager.addPacketListener(new PacketAdapter(
            plugin, ListenerPriority.NORMAL, PacketType.Play.Client.getInstance()) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                Player player = event.getPlayer();
                if (player == null || !player.isOnline()) return;
                if (player.hasPermission("anticheat.bypass")) return;

                try {
                    validator.validatePacketStructure(player, event.getPacket().getHandle());
                    validator.recordPacketTiming(player, System.currentTimeMillis());
                } catch (Throwable ignored) {
                    // 单个包校验失败不拖垮包监听器
                }
            }
        });
    }

    @Override
    public void sendFakeBlock(Player player, Location loc, Material type) {
        if (player == null || loc == null || type == null) return;
        if (player.hasPermission("anticheat.bypass")) return;
        try {
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
            packet.getBlockPositionModifier().write(0,
                new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            packet.getBlockData().write(0, WrappedBlockData.createData(type));
            manager.sendServerPacket(player, packet);
        } catch (Throwable ignored) {
            // 版本/材质不兼容时静默失败，调用方回退真实方块
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /** 关闭钩子：移除本插件注册的所有包监听器（重载时避免重复注册）。 */
    public void close() {
        try {
            manager.removePacketListeners(plugin);
        } catch (Throwable ignored) {
        }
    }
}
