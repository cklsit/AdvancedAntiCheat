package com.anticheat.detection.network;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.utils.VersionUtil;
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

    /**
     * 注册品牌通道。
     *
     * <p><b>跨版本铁律</b>：通道名格式由服务端版本决定，且注册失败绝不能拖垮 onEnable。</p>
     *
     * <ul>
     *   <li>1.12 及以前：只认 {@code MC|Brand}；</li>
     *   <li>1.13 及以后：只认 {@code namespace:key} 形式，传 {@code MC|Brand} 会直接抛
     *       {@code IllegalArgumentException: Channel must contain : separator}
     *       —— 该异常从 {@code registerIncomingPluginChannel} 抛出，若向上冒泡会让
     *       {@code onEnable} 整体失败、插件被禁用（E2E 在 1.21.11 上抓到过：
     *       表现为所有 /ac 命令都回 "plugin is disabled"）。</li>
     * </ul>
     *
     * <p>所以这里按大版本选通道名，并对每个通道单独 try/catch(Throwable)：
     * 品牌通道只影响「客户端品牌采集」这一项附加能力，它失败没有任何理由让整个反作弊停摆。</p>
     */
    private void registerChannels() {
        String channel = VersionUtil.getMajorVersion() >= 13 ? CHANNEL_MODERN : CHANNEL_LEGACY;
        try {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, this);
            return;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Brand] 品牌通道 " + channel + " 注册失败（已跳过客户端品牌采集）：" + t);
        }
        // 兜底：万一版本判定与实际实现不一致，再试另一个名字；两次都失败也只是少一项能力
        String fallback = CHANNEL_LEGACY.equals(channel) ? CHANNEL_MODERN : CHANNEL_LEGACY;
        try {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, fallback, this);
        } catch (Throwable ignored) {
            // 两条通道都注册不上：品牌采集不可用，其余检测照常
        }
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
