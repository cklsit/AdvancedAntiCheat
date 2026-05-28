package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.gui.ProfileGUI;
import com.anticheat.managers.ReportManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

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

        String subCommand = args[0].toLowerCase();
        if (subCommand.equals("reload")) {
            plugin.reloadConfig();
            plugin.getConfigManager().reloadMessagesConfig();
            sender.sendMessage("§a[AntiCheat] 配置和消息文件已重新加载！");
        } else if (subCommand.equals("stats")) {
            showStats(sender);
        } else if (subCommand.equals("reports")) {
            showReports(sender);
        } else if (subCommand.equals("help")) {
            showHelp(sender);
        } else if (subCommand.equals("profile")) {
            handleProfile(sender, args);
        } else {
            sender.sendMessage("§c未知子命令！使用 /ac help 查看帮助");
        }

        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§c┌─────────────────────────────────────┐");
        sender.sendMessage("§c│          §6AdvancedAntiCheat          §c│");
        sender.sendMessage("§c└─────────────────────────────────────┘");
        sender.sendMessage("");
        sender.sendMessage(" §a/ac reload          §7- 重新加载配置文件");
        sender.sendMessage(" §a/ac stats           §7- 查看检测统计信息");
        sender.sendMessage(" §a/ac reports         §7- 查看待处理举报列表");
        sender.sendMessage(" §a/ac profile <玩家>  §7- 查看玩家档案");
        sender.sendMessage(" §a/ac help            §7- 显示此帮助信息");
        sender.sendMessage("");
        sender.sendMessage(" §6玩家命令:");
        sender.sendMessage("   §a/report <玩家> <原因>");
        sender.sendMessage("   §7   举报作弊玩家");
        sender.sendMessage("");
        sender.sendMessage(" §6管理员命令:");
        sender.sendMessage("   §a/goto <玩家>");
        sender.sendMessage("   §7   传送至指定玩家");
        sender.sendMessage("   §a/ban <玩家> [时间] [原因]");
        sender.sendMessage("   §7   封禁玩家");
        sender.sendMessage("   §a/unban <玩家>");
        sender.sendMessage("   §7   解封玩家");
        sender.sendMessage("   §a/checkclient <玩家> <QQ号>");
        sender.sendMessage("   §7   对玩家发起客户端检查");
        sender.sendMessage("   §a/checkdone <玩家>");
        sender.sendMessage("   §7   完成玩家的客户端检查");
        sender.sendMessage("   §a/captcha <玩家|toggle|timelimit>");
        sender.sendMessage("   §7   验证码测试命令");
        sender.sendMessage("");
        sender.sendMessage(" §6漏洞赏金命令:");
        sender.sendMessage("   §a/bounty enter");
        sender.sendMessage("   §7   进入漏洞赏金沙箱");
        sender.sendMessage("   §a/bounty leave");
        sender.sendMessage("   §7   离开漏洞赏金沙箱");
        sender.sendMessage("   §a/bounty start <任务>");
        sender.sendMessage("   §7   开始赏金任务");
        sender.sendMessage("   §a/bounty report <描述>");
        sender.sendMessage("   §7   报告发现的漏洞");
        sender.sendMessage("   §a/bounty lb");
        sender.sendMessage("   §7   查看赏金排行榜");
        sender.sendMessage("");
        sender.sendMessage("§c└─────────────────────────────────────┘");
        sender.sendMessage("");
    }

    private void showStats(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§c┌─────────────────────────────────────┐");
        sender.sendMessage("§c│            §6检测统计               §c│");
        sender.sendMessage("§c└─────────────────────────────────────┘");
        sender.sendMessage("");
        sender.sendMessage(" §7飞行检测: " + (plugin.getConfigManager().isDetectionEnabled("fly") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7速度检测: " + (plugin.getConfigManager().isDetectionEnabled("speed") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7透视检测: " + (plugin.getConfigManager().isDetectionEnabled("esp") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7杀戮光环: " + (plugin.getConfigManager().isDetectionEnabled("killaura") ? "§a启用" : "§c禁用"));
        sender.sendMessage(" §7攻击距离: " + (plugin.getConfigManager().isDetectionEnabled("reach") ? "§a启用" : "§c禁用"));
        sender.sendMessage("");
        sender.sendMessage("§c└─────────────────────────────────────┘");
        sender.sendMessage("");
    }

    private void showReports(CommandSender sender) {
        List<ReportManager.Report> reports = plugin.getReportManager().getReports();
        sender.sendMessage("");
        sender.sendMessage("§c┌─────────────────────────────────────┐");
        sender.sendMessage("§c│          §6待处理举报               §c│");
        sender.sendMessage("§c└─────────────────────────────────────┘");
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
        sender.sendMessage("§c└─────────────────────────────────────┘");
        sender.sendMessage("");
    }

    private void handleProfile(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用此命令！");
            return;
        }

        Player viewer = (Player) sender;
        if (args.length < 2) {
            viewer.sendMessage("§c用法: /ac profile <玩家>");
            return;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            viewer.sendMessage("§c玩家 " + targetName + " 不在线！");
            return;
        }

        ProfileGUI gui = new ProfileGUI(plugin, plugin.getProfileGUIListener());
        gui.openProfileGUI(viewer, target);
    }
}