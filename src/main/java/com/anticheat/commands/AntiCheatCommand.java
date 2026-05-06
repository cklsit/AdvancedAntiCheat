package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ReportManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class AntiCheatCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public AntiCheatCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("noPermission"));
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§a[AntiCheat] 配置已重新加载！");
            }
            case "stats" -> showStats(sender);
            case "reports" -> showReports(sender);
            case "help" -> showHelp(sender);
            default -> sender.sendMessage("§c未知子命令！使用 /ac help 查看帮助");
        }

        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§c§l═══════════ §6AdvancedAntiCheat §c§l═══════════");
        sender.sendMessage("");
        sender.sendMessage(" §a/ac reload §7- 重新加载配置文件");
        sender.sendMessage(" §a/ac stats §7- 查看检测统计信息");
        sender.sendMessage(" §a/ac reports §7- 查看待处理举报列表");
        sender.sendMessage(" §a/ac help §7- 显示此帮助信息");
        sender.sendMessage("");
        sender.sendMessage(" §6玩家命令:");
        sender.sendMessage("   §a/report <玩家> <原因> §7- 举报作弊玩家");
        sender.sendMessage("");
        sender.sendMessage(" §6管理员命令:");
        sender.sendMessage("   §a/goto <玩家> §7- 传送至指定玩家");
        sender.sendMessage("   §a/ban <玩家> [时间] [原因] §7- 封禁玩家");
        sender.sendMessage("   §a/unban <玩家> §7- 解封玩家");
        sender.sendMessage("");
        sender.sendMessage("§c§l═══════════════════════════════════");
        sender.sendMessage("");
    }

    private void showStats(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§c§l═══════════ §6检测统计 §c§l═══════════");
        sender.sendMessage("");
        sender.sendMessage(" §7飞行检测: " + (plugin.getConfigManager().isDetectionEnabled("fly") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7速度检测: " + (plugin.getConfigManager().isDetectionEnabled("speed") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7透视检测: " + (plugin.getConfigManager().isDetectionEnabled("esp") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7杀戮光环检测: " + (plugin.getConfigManager().isDetectionEnabled("killaura") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7攻击距离检测: " + (plugin.getConfigManager().isDetectionEnabled("reach") ? "§a启用" : "§c禁用"));
        sender.sendMessage("");
        sender.sendMessage("§c§l═══════════════════════════════════");
        sender.sendMessage("");
    }

    private void showReports(CommandSender sender) {
        var reports = plugin.getReportManager().getReports();
        sender.sendMessage("");
        sender.sendMessage("§c§l═══════════ §6待处理举报 §c§l═══════════");
        sender.sendMessage("");

        if (reports.isEmpty()) {
            sender.sendMessage(" §a暂无待处理举报");
        } else {
            int i = 1;
            for (ReportManager.Report report : reports) {
                sender.sendMessage(" §6" + i + ". §e" + report.getReporterName() + " §7举报 §e" + report.getTargetName());
                sender.sendMessage("    §7原因: §f" + report.getReason());
                i++;
            }
        }

        sender.sendMessage("");
        sender.sendMessage("§c§l═══════════════════════════════════");
        sender.sendMessage("");
    }
}