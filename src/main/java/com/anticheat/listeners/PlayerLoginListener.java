package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.BanManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.UUID;

public class PlayerLoginListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerLoginListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        BanManager banManager = plugin.getBanManager();

        if (banManager.isBanned(uuid)) {
            BanManager.BanInfo banInfo = banManager.getBanInfo(uuid);
            if (banInfo != null) {
                String reason = banInfo.getReason();
                long endTime = banInfo.getEndTime();

                String kickMessage = buildKickMessage(banInfo, endTime);

                event.setResult(PlayerLoginEvent.Result.KICK_BANNED);
                event.setKickMessage(kickMessage);

                plugin.getLogger().info("拦截被封禁玩家 " + player.getName() + " 的登录尝试");
            }
        }
    }

    private String buildKickMessage(BanManager.BanInfo banInfo, long endTime) {
        StringBuilder message = new StringBuilder();
        message.append("§c§l═══════════════════════════════════════\n");
        message.append("§c              §l⚠ 已被服务器封禁 ⚠\n");
        message.append("§c§l═══════════════════════════════════════\n");
        message.append("\n");
        message.append("§7封禁原因: §f").append(banInfo.getReason()).append("\n");
        message.append("\n");

        if (endTime == -1) {
            message.append("§7封禁时长: §c永久封禁\n");
        } else {
            long remaining = endTime - System.currentTimeMillis();
            String remainingStr = formatRemainingTime(remaining);
            message.append("§7封禁时长: §e").append(remainingStr).append("\n");
        }

        message.append("\n");
        message.append("§c§l═══════════════════════════════════════\n");
        message.append("§6如有疑问请联系服务器管理员\n");
        message.append("§c§l═══════════════════════════════════════");
        return message.toString();
    }

    private String formatRemainingTime(long milliseconds) {
        if (milliseconds <= 0) {
            return "已到期";
        }

        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + " 天 " + (hours % 24) + " 小时";
        } else if (hours > 0) {
            return hours + " 小时 " + (minutes % 60) + " 分钟";
        } else if (minutes > 0) {
            return minutes + " 分钟 " + (seconds % 60) + " 秒";
        } else {
            return seconds + " 秒";
        }
    }
}