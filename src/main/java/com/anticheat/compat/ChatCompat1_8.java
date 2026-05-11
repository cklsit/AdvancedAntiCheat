package com.anticheat.compat;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ChatCompat1_8 implements ChatCompat {

    @Override
    public void sendMessage(Player player, String message) {
        player.sendMessage(message);
    }

    @Override
    public void sendMessageWithButton(Player player, String message, String buttonText, String command) {
        try {
            net.md_5.bungee.api.chat.TextComponent text = new net.md_5.bungee.api.chat.TextComponent(message);
            
            net.md_5.bungee.api.chat.TextComponent button = new net.md_5.bungee.api.chat.TextComponent(buttonText);
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
            button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                new ComponentBuilder("点击执行: " + command).create()));
            
            player.spigot().sendMessage(text, button);
        } catch (Exception e) {
            player.sendMessage(message + " " + buttonText);
        }
    }

    @Override
    public void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer");
            Object craftPlayer = craftPlayerClass.cast(player);
            
            Method getHandleMethod = craftPlayerClass.getMethod("getHandle");
            Object entityPlayer = getHandleMethod.invoke(craftPlayer);
            
            Class<?> packetPlayOutTitleClass = Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutTitle");
            Class<?> packetClass = Class.forName("net.minecraft.server.v1_8_R3.Packet");
            
            Class<?> chatSerializerClass = Class.forName("net.minecraft.server.v1_8_R3.ChatSerializer");
            Method aMethod = chatSerializerClass.getMethod("a", String.class);
            
            Object titleComponent = aMethod.invoke(null, "{\"text\":\"" + title + "\"}");
            Object subtitleComponent = aMethod.invoke(null, "{\"text\":\"" + subtitle + "\"}");
            
            Class<?> titleActionClass = Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutTitle$EnumTitleAction");
            Object titleAction = titleActionClass.getEnumConstants()[0];
            Object subtitleAction = titleActionClass.getEnumConstants()[1];
            Object timesAction = titleActionClass.getEnumConstants()[2];
            
            Constructor<?> titlePacketConstructor = packetPlayOutTitleClass.getConstructor(titleActionClass, Class.forName("net.minecraft.server.v1_8_R3.IChatBaseComponent"));
            Constructor<?> timesPacketConstructor = packetPlayOutTitleClass.getConstructor(int.class, int.class, int.class);
            
            Object timesPacket = timesPacketConstructor.newInstance(fadeIn, stay, fadeOut);
            
            Class<?> playerConnectionClass = Class.forName("net.minecraft.server.v1_8_R3.PlayerConnection");
            Field playerConnectionField = entityPlayer.getClass().getField("playerConnection");
            Object playerConnection = playerConnectionField.get(entityPlayer);
            
            Method sendPacketMethod = playerConnectionClass.getMethod("sendPacket", packetClass);
            
            sendPacketMethod.invoke(playerConnection, timesPacket);
            sendPacketMethod.invoke(playerConnection, titlePacketConstructor.newInstance(titleAction, titleComponent));
            sendPacketMethod.invoke(playerConnection, titlePacketConstructor.newInstance(subtitleAction, subtitleComponent));
            
        } catch (Exception e) {
            player.sendMessage(title);
            player.sendMessage(subtitle);
        }
    }

    @Override
    public void kickPlayer(Player player, String message) {
        player.kickPlayer(message);
    }

    @Override
    public void broadcastMessage(String message, String permission) {
        if (permission == null || permission.isEmpty()) {
            Bukkit.broadcastMessage(message);
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(permission)) {
                    player.sendMessage(message);
                }
            }
        }
    }
}
