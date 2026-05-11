package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.CheckClientManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CheckClientCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public CheckClientCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.checkclient")) {
            sender.sendMessage("§c您没有权限执行此命令！");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§c此命令只能由玩家执行！");
            return true;
        }
        Player admin = (Player) sender;

        if (args.length < 2) {
            sender.sendMessage("§c用法: §e/checkclient <玩家> <QQ号>");
            sender.sendMessage("§7示例: §e/checkclient Steve 123456789");
            return true;
        }

        String targetName = args[0];
        String qqNumber = args[1];

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage("§c未找到玩家: §e" + targetName);
            return true;
        }

        CheckClientManager checkManager = plugin.getCheckClientManager();

        if (checkManager.isBeingChecked(target.getUniqueId())) {
            sender.sendMessage("§c玩家 §e" + targetName + " §c正在要求进行客户端检查。");
            return true;
        }

        boolean success = checkManager.startCheck(target, admin, qqNumber);

        if (success) {
            sender.sendMessage("§a已开始对玩家 §e" + targetName + " §a进行客户端检查");
            sender.sendMessage("§7QQ号码: §e" + qqNumber);
            sender.sendMessage("§7查段时间限: §e" + plugin.getConfig().getInt("check-client.timeout-minutes", 60) + " 分钟");

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("anticheat.notify") && !p.equals(admin)) {
                    p.sendMessage("§6[管理员] §e" + admin.getName() + " §6正在对 §e" + targetName + " §6进行客户端检查");
                }
            }
        } else {
            sender.sendMessage("§c无法开始检查！玩家可能已经在检查中。");
        }

        return true;
    }
}
