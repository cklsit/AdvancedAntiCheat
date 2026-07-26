package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.BanManager.BanInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffectType;

public class PlayerJoinListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerJoinListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        clearCheckClientEffects(player);

        if (plugin.getBanManager().isBanned(player.getUniqueId())) {
            BanInfo banInfo = plugin.getBanManager().getBanInfo(player.getUniqueId());
            if (banInfo != null) {
                StringBuilder kickMessage = new StringBuilder();
                kickMessage.append("§c§l═══════════════════════════\n");
                kickMessage.append("§c      您已被服务器封禁!\n");
                kickMessage.append("§c═══════════════════════════\n");
                kickMessage.append("§7封禁时长: §e").append(formatEndTime(banInfo.getEndTime())).append("\n");
                kickMessage.append("§7封禁原因: §f").append(banInfo.getReason()).append("\n");
                kickMessage.append("§c═══════════════════════════\n");
                kickMessage.append("§6如有疑问请联系服务器管理员");
                player.kickPlayer(kickMessage.toString());
            }
        }
    }

    private void clearCheckClientEffects(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        
        try {
            PotionEffectType slow = PotionEffectType.getByName("SLOW");
            if (slow != null) {
                player.removePotionEffect(slow);
            }
        } catch (Exception ignored) {}
        
        try {
            PotionEffectType slowness = PotionEffectType.getByName("SLOWNESS");
            if (slowness != null) {
                player.removePotionEffect(slowness);
            }
        } catch (Exception ignored) {}

        try {
            PotionEffectType jump = PotionEffectType.getByName("JUMP");
            if (jump != null) {
                player.removePotionEffect(jump);
            }
        } catch (Exception ignored) {}
        
        try {
            PotionEffectType jumpBoost = PotionEffectType.getByName("JUMP_BOOST");
            if (jumpBoost != null) {
                player.removePotionEffect(jumpBoost);
            }
        } catch (Exception ignored) {}

        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.2f);
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    private String formatEndTime(long endTime) {
        if (endTime == -1) {
            return "永久";
        }
        long remaining = endTime - System.currentTimeMillis();
        if (remaining <= 0) {
            return "已过期";
        }

        long days = remaining / (24 * 60 * 60 * 1000);
        long hours = (remaining % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (remaining % (60 * 60 * 1000)) / (60 * 1000);

        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        } else if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        } else {
            return minutes + " 分钟";
        }
    }
}
