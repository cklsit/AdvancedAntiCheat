package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.CheckClientManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            sender.sendMessage(Component.text("§c您没有权限执行此命令！", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(Component.text("§c用法: §e/checkdone <玩家>", NamedTextColor.RED));
            sender.sendMessage(Component.text("§7示例: §e/checkdone Steve", NamedTextColor.GRAY));
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            sender.sendMessage(Component.text("§c未找到玩家: §e" + targetName, NamedTextColor.RED));
            return true;
        }

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (!checkManager.isBeingChecked(target.getUniqueId())) {
            sender.sendMessage(Component.text("§c这名玩家没有被冻结！", NamedTextColor.RED));
            return true;
        }

        checkManager.endCheckPass(target);

        sender.sendMessage(Component.text("§a已解除玩家 §e" + targetName + " §a的检查状态", NamedTextColor.GREEN));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("anticheat.notify") && !p.equals(sender)) {
                p.sendMessage(Component.text("§6[管理员] §e" + sender.getName() + " §6已解除 §e" + targetName + " §6的检查状态", NamedTextColor.GOLD));
            }
        }

        return true;
    }
}
