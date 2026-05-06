package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GotoCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public GotoCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家执行！");
            return true;
        }

        if (!player.hasPermission("anticheat.goto")) {
            player.sendMessage(plugin.getConfigManager().getMessage("noPermission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§c用法: §e/goto <玩家>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage(String.format(plugin.getConfigManager().getMessage("playerNotFound"), targetName));
            return true;
        }

        player.teleport(target.getLocation());
        player.sendMessage("§a已传送到 §e" + target.getName() + " §a身边");

        return true;
    }
}