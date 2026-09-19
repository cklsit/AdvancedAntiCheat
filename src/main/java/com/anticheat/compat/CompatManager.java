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

    /**
     * 服务端是否具备 adventure 文本 API。
     *
     * <p>判据用**服务端版本**而不是 {@code Class.forName("net.kyori.adventure.text.Component")}：
     * 核心层依赖的 PacketEvents 会自带一份 net.kyori（1.8 服务端没有这个库），
     * shade 之后插件自己的类加载器里一定能找到该类，探测会恒为 true，
     * 从而在 1.8 上给出错误结论。</p>
     */
    public static boolean hasAdventureAPI() {
        return VersionUtil.getMajorVersion() >= 16;
    }
}
