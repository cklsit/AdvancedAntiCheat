package com.anticheat.detection.network;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * BrandChannelListener —— 品牌通道监听器。
 *
 * <p>客户端在握手完成后会通过 <b>MC|Brand</b>（1.8–1.12）或 <b>minecraft:brand</b>（1.13+）
 * 插件消息通道上报其客户端品牌。本监听器读取该品牌，并将品牌与协议版本登记到
 * {@link ProtocolValidator}，同时写入玩家画像（用于设备指纹关联）。</p>
 *
 * <p>协议版本解析优先走 ViaVersion（若已安装），其次 Paper 原生
 * {@code Player#getProtocolVersion()}，都不可用才回退为服务端协议版本，
 * 以避免因缺少版本信息导致误判「版本欺骗」。</p>
 */
public class BrandChannelListener implements PluginMessageListener {

    private static final String CHANNEL_LEGACY = "MC|Brand";
    private static final String CHANNEL_MODERN = "minecraft:brand";

    private final AdvancedAntiCheat plugin;
    private final ProtocolValidator protocolValidator;

    public BrandChannelListener(AdvancedAntiCheat plugin, ProtocolValidator protocolValidator) {
        this.plugin = plugin;
        this.protocolValidator = protocolValidator;
        registerChannels();
    }

    private void registerChannels() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_LEGACY, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_MODERN, this);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (message.length == 0) return;
        String brand;
        try {
            brand = new String(message, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return;
        }
        if (brand.isEmpty()) return;

        int protocol = resolveProtocolVersion(player);
        if (protocol <= 0) protocol = protocolValidator.getServerProtocolVersion();

        protocolValidator.registerClientInfo(player, brand, protocol);
        try {
            plugin.getProfileManager().updateClientBrand(player, brand);
        } catch (Throwable ignored) {
        }
    }

    /** 解析玩家真实协议版本：优先 ViaVersion，其次 Paper 原生 getProtocolVersion()，都不可用返回 -1。 */
    private int resolveProtocolVersion(Player player) {
        // 路径 1：ViaVersion（反射）
        try {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            Object version = api.getClass().getMethod("getPlayerVersion", java.util.UUID.class)
                .invoke(api, player.getUniqueId());
            if (version instanceof Number) {
                return ((Number) version).intValue();
            }
        } catch (Throwable ignored) {
        }

        // 路径 2：Paper/Spigot 原生 Player#getProtocolVersion()（1.8+，部分实现无此方法）
        try {
            Object v = player.getClass().getMethod("getProtocolVersion").invoke(player);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {
        }

        return -1;
    }
}
