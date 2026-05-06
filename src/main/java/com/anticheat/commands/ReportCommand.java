package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReportCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public ReportCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家执行！");
            return true;
        }

        if (!player.hasPermission("anticheat.report")) {
            player.sendMessage(plugin.getConfigManager().getMessage("noPermission"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§c用法: §e/report <玩家> <原因>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage(String.format(plugin.getConfigManager().getMessage("playerNotFound"), targetName));
            return true;
        }

        if (target.equals(player)) {
            player.sendMessage("§c您不能举报自己！");
            return true;
        }

        StringBuilder reason = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) reason.append(" ");
            reason.append(args[i]);
        }

        plugin.getReportManager().addReport(player, target, reason.toString());

        player.sendMessage("");
        player.sendMessage("§8§m────────────────────────");
        player.sendMessage("§6[§c举报系统§6]");
        player.sendMessage("§a举报已成功提交！");
        player.sendMessage("§7被举报玩家: §e" + target.getName());
        player.sendMessage("§7举报原因: §f" + reason);
        player.sendMessage("§6管理员将尽快处理您的举报");
        player.sendMessage("§8§m────────────────────────");
        player.sendMessage("");

        return true;
    }
}