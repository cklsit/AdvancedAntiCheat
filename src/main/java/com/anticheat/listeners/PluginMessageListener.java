package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class PluginMessageListener implements PluginMessageListener {

    private final AdvancedAntiCheat plugin;

    public PluginMessageListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!channel.equals("BungeeCord")) {
            return;
        }

        try {
            Class<?> bungeeUtilClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Object craftPlayer = bungeeUtilClass.cast(player);
            Object handle = bungeeUtilClass.getMethod("getHandle").invoke(craftPlayer);
            Object playerConnection = handle.getClass().getField("b").get(handle);

            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.PacketPlayOutPluginMessage");
            Object packet = packetClass.getConstructor(String.class, byte[].class)
                    .newInstance("BungeeCord", data);

            playerConnection.getClass().getMethod("send", packetClass).invoke(playerConnection, packet);
        } catch (Exception e) {
        }
    }
}