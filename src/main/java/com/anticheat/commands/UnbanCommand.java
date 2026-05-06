package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class UnbanCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public UnbanCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.unban")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("noPermission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§c用法: §e/unban <玩家>");
            return true;
        }

        String targetName = args[0];

        if (!plugin.getBanManager().isBanned(targetName)) {
            sender.sendMessage(String.format(plugin.getConfigManager().getMessage("notBanned"), targetName));
            return true;
        }

        plugin.getBanManager().unbanPlayer(targetName);

        return true;
    }
}