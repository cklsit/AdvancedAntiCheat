package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BanCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public BanCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.ban")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("commands.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§c用法: §e/ban <玩家> [时间] [原因]");
            sender.sendMessage("§7时间格式: 1m(分钟), 1h(小时), 1d(天)");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null && !Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("commands.player-not-found").replace("{player}", targetName));
            return true;
        }

        if (plugin.getBanManager().isBanned(targetName)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("commands.already-banned").replace("{player}", targetName));
            return true;
        }

        String duration = "1d";
        String reason = "违规行为";

        if (args.length >= 2) {
            if (args[1].matches("^\\d+[smhd]$")) {
                duration = args[1];
                if (args.length >= 3) {
                    StringBuilder reasonBuilder = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        if (i > 2) reasonBuilder.append(" ");
                        reasonBuilder.append(args[i]);
                    }
                    reason = reasonBuilder.toString();
                }
            } else {
                StringBuilder reasonBuilder = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (i > 1) reasonBuilder.append(" ");
                    reasonBuilder.append(args[i]);
                }
                reason = reasonBuilder.toString();
            }
        }

        if (target != null) {
            plugin.getBanManager().banPlayer(target.getUniqueId(), target.getName(), duration, reason);
        } else {
            plugin.getBanManager().banPlayer(Bukkit.getOfflinePlayer(targetName).getUniqueId(), targetName, duration, reason);
        }

        sender.sendMessage(plugin.getConfigManager().getMessage("commands.ban-success")
            .replace("{player}", targetName)
            .replace("{banTime}", duration));

        return true;
    }
}