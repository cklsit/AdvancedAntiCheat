package com.anticheat.compat;

import org.bukkit.entity.Player;

public interface ChatCompat {

    void sendMessage(Player player, String message);

    void sendMessageWithButton(Player player, String message, String buttonText, String command);

    void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut);

    void kickPlayer(Player player, String message);

    void broadcastMessage(String message, String permission);
}
