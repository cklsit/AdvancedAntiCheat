package com.anticheat.compat;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 高版本（1.19+）聊天/标题兼容实现。
 *
 * <p><b>刻意不使用 net.kyori.adventure</b>：核心层的 PacketEvents 会把 adventure
 * 连同 {@code net.kyori} 一起重定位进 {@code com.anticheat.libs.kyori}（1.8 服务端没有
 * 这个库，PE 的日志层需要自带一份）。插件自身代码一旦在编译期引用 adventure，
 * 这部分引用会被 shade 一起改写指向影子类，运行时把影子 {@code Component}
 * 传给 Paper 的 {@code Player#sendMessage} 会直接 NoSuchMethodError。</p>
 *
 * <p>改用 **BungeeCord chat**：{@code net.md-5:bungeecord-chat} 是 provided 依赖，
 * 不进 jar，由服务端在 1.8.8 与 1.21 上分别提供，两侧 API 完全一致——
 * 既满足可点击文本（{@code ClickEvent}），又天然跨版本。</p>
 */
public class ChatCompat1_19 implements ChatCompat {

    /** 与原先 adventure 实现的观感对齐：标题红、副标题黄。 */
    private static final String COLOR_TITLE = "\u00a7c";
    private static final String COLOR_SUBTITLE = "\u00a7e";

    @Override
    public void sendMessage(Player player, String message) {
        player.sendMessage(message);
    }

    @Override
    public void sendMessageWithButton(Player player, String message, String buttonText, String command) {
        try {
            TextComponent text = new TextComponent(TextComponent.fromLegacyText(message));

            TextComponent button = new TextComponent(TextComponent.fromLegacyText(buttonText));
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
            button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("点击执行: " + command).create()));

            text.addExtra(button);
            player.spigot().sendMessage((BaseComponent) text);
        } catch (Throwable t) {
            // 退化为纯文本，保证功能可用（点击按钮是增强，不是必需）
            player.sendMessage(message + " " + buttonText);
        }
    }

    @Override
    public void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        try {
            // 5 参重载在 1.8.8 不存在，但本实现只在 1.19+ 上被实例化
            // （CompatManager 以 VersionUtil.isHighVersion() 选路），
            // 且包在 Throwable 里——双版本审计的第 2 类判据正是这样放行的。
            player.sendTitle(COLOR_TITLE + title, COLOR_SUBTITLE + subtitle, fadeIn, stay, fadeOut);
        } catch (Throwable t) {
            player.sendMessage(COLOR_TITLE + title);
            player.sendMessage(COLOR_SUBTITLE + subtitle);
        }
    }

    @Override
    public void kickPlayer(Player player, String message) {
        player.kickPlayer(message);
    }

    @Override
    public void broadcastMessage(String message, String permission) {
        if (permission == null || permission.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(message);
            }
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(permission)) {
                    player.sendMessage(message);
                }
            }
        }
    }
}
