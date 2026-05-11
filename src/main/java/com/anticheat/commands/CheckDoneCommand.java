package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.CheckClientManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CheckDoneCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public CheckDoneCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.checkclient")) {
            sender.sendMessage("§c您没有权限执行此命令！");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§c用法: §e/checkdone <玩家>");
            sender.sendMessage("§7示例: §e/checkdone Steve");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            sender.sendMessage("§c未找到玩家: §e" + targetName);
            return true;
        }

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(target.getUniqueId())) {
            sender.sendMessage("§c这名玩家没有被冻结！");
            return true;
        }

        checkManager.endCheckPass(target);

        sender.sendMessage("§a已解除玩家 §e" + targetName + " §a的检查状态");

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("anticheat.notify") && !p.equals(sender)) {
                p.sendMessage("§6[管理员] §e" + sender.getName() + " §6已解除 §e" + targetName + " §6的检查状态");
            }
        }

        return true;
    }
}
