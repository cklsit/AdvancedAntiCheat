package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.BanManager;
import com.anticheat.managers.ConfigManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.HashMap;
import java.util.Map;
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
                String kickMessage = buildKickMessage(banInfo);

                event.setResult(PlayerLoginEvent.Result.KICK_BANNED);
                event.setKickMessage(kickMessage);

                plugin.getLogger().info("拦截被封禁玩家 " + player.getName() + " 的登录尝试");
            }
        }
    }

    private String buildKickMessage(BanManager.BanInfo banInfo) {
        ConfigManager configManager = plugin.getConfigManager();
        String reason = banInfo.getReason();
        long endTime = banInfo.getEndTime();
        String banTimeText;

        if (endTime == -1) {
            banTimeText = configManager.getPermanentBanText();
        } else {
            long remaining = endTime - System.currentTimeMillis();
            banTimeText = configManager.formatTime(remaining, new HashMap<>());
        }

        return configManager.formatBanScreen(reason, banTimeText);
    }
}