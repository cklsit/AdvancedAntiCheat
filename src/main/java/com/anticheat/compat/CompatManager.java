package com.anticheat.compat;

import com.anticheat.utils.VersionUtil;

public class CompatManager {

    private static ChatCompat chatCompat;

    public static ChatCompat getChatCompat() {
        if (chatCompat == null) {
            if (VersionUtil.isHighVersion()) {
                try {
                    Class<?> clazz = Class.forName("com.anticheat.compat.ChatCompat1_19");
                    chatCompat = (ChatCompat) clazz.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    chatCompat = new ChatCompat1_8();
                }
            } else {
                chatCompat = new ChatCompat1_8();
            }
        }
        return chatCompat;
    }

    public static boolean hasVelocityEvent() {
        try {
            Class.forName("org.bukkit.event.player.PlayerVelocityEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean hasAsyncChatEvent() {
        try {
            Class.forName("org.bukkit.event.player.AsyncPlayerChatEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean hasAdventureAPI() {
        try {
            Class.forName("net.kyori.adventure.text.Component");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
