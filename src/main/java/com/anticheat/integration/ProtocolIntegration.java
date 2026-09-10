package com.anticheat.integration;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * 协议层集成抽象（避免核心模块直接引用 ProtocolLib 类，保证无 ProtocolLib 时不触发
 * {@link NoClassDefFoundError}）。由 {@code ProtocolLibHook} 实现；未安装 ProtocolLib 时该引用为 null。
 */
public interface ProtocolIntegration {

    /**
     * 向指定玩家发送「协议级假方块」（Block Change 包），仅改变该玩家客户端渲染，
     * 不修改服务端真实方块。用于渲染距离验证（矿透）等蜜罐场景。
     *
     * @param player 目标玩家
     * @param loc    方块位置
     * @param type   客户端渲染的方块材质
     */
    void sendFakeBlock(Player player, Location loc, Material type);

    /**
     * 协议层是否可用（ProtocolLib 是否已安装并完成初始化）。
     */
    boolean isAvailable();
}
