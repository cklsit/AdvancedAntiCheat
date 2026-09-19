package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.gui.ConfigGUI;
import com.anticheat.gui.ProfileGUI;
import com.anticheat.managers.ReportManager;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AntiCheatCommand implements TabExecutor {

    private final AdvancedAntiCheat plugin;

    public AntiCheatCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("commands.no-permission"));
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
        } else if (subCommand.equals("config")) {
            handleConfig(sender);
        } else {
            sender.sendMessage("§c未知子命令！使用 /ac help 查看帮助");
        }

        return true;
    }

    // ================================================================
    // Tab 补全
    // ================================================================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(Arrays.asList(
                    "reload", "stats", "reports", "profile", "config", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            List<String> names = new ArrayList<>();
            for (Player online : VersionUtil.safeGetOnlinePlayers()) {
                if (online != null) {
                    names.add(online.getName());
                }
            }
            return filterPrefix(names, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("replay")) {
            return filterPrefix(Arrays.asList("status", "setup", "disable", "enable", "down", "docker"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase();
        List<String> matched = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                matched.add(option);
            }
        }
        return matched;
    }

    // ================================================================
    // /ac config —— 六层游戏内管理界面
    // ================================================================
    private void handleConfig(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用该界面，控制台请使用 /ac stats 或 Web 面板。");
            return;
        }
        if (!sender.hasPermission("anticheat.config")) {
            sender.sendMessage("§c你没有权限打开管理界面（anticheat.config）。");
            return;
        }
        if (!plugin.getConfig().getBoolean("gui.enabled", true)) {
            sender.sendMessage("§c管理界面已在 config.yml 中关闭（gui.enabled: false）。");
            return;
        }
        Player viewer = (Player) sender;
        try {
            new ConfigGUI(plugin).openMain(viewer);
        } catch (Throwable t) {
            viewer.sendMessage("§c打开管理界面失败，请查看控制台日志。");
            plugin.getLogger().warning("[GUI] 打开 /ac config 失败: " + t);
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§8╔══════════════════════════════════════════════════╗");
        sender.sendMessage("§8║          §6§lAdvancedAntiCheat §7v2.1.0          §8║");
        sender.sendMessage("§8║           §7指令帮助 · Commands Help            §8║");
        sender.sendMessage("§8╚══════════════════════════════════════════════════╝");
        sender.sendMessage("");

        sender.sendMessage(" §6§l[反作弊管理] §8(/ac)");
        sender.sendMessage(" §a" + pad("/ac reload", 34) + "§8» §7重新加载配置文件");
        sender.sendMessage(" §a" + pad("/ac stats", 34) + "§8» §7查看检测统计信息");
        sender.sendMessage(" §a" + pad("/ac reports", 34) + "§8» §7查看待处理举报列表");
        sender.sendMessage(" §a" + pad("/ac profile <玩家>", 34) + "§8» §7查看玩家行为档案");
        sender.sendMessage(" §a" + pad("/ac config", 34) + "§8» §7打开游戏内管理界面（玩家/封禁/白名单）");
        sender.sendMessage(" §a" + pad("/ac help", 34) + "§8» §7显示此帮助信息");
        sender.sendMessage("");

        sender.sendMessage(" §6§l[玩家命令]");
        sender.sendMessage(" §a" + pad("/report <玩家> <原因>", 34) + "§8» §7举报作弊玩家");
        sender.sendMessage("");

        sender.sendMessage(" §6§l[管理员命令]");
        sender.sendMessage(" §a" + pad("/goto <玩家>", 34) + "§8» §7传送至玩家（支持跨服）");
        sender.sendMessage(" §a" + pad("/ban <玩家> [时间] [原因]", 34) + "§8» §7封禁玩家（默认永久）");
        sender.sendMessage(" §a" + pad("/unban <玩家>", 34) + "§8» §7解封玩家");
        sender.sendMessage(" §a" + pad("/checkclient <玩家> <QQ号>", 34) + "§8» §7对玩家发起客户端检查");
        sender.sendMessage(" §a" + pad("/checkdone <玩家>", 34) + "§8» §7结束玩家的客户端检查");
        sender.sendMessage(" §a" + pad("/captcha <玩家|toggle|timelimit>", 34) + "§8» §7验证码测试命令");
        sender.sendMessage("");

        sender.sendMessage(" §6§l[漏洞赏金] §8(/bounty)");
        sender.sendMessage(" §a" + pad("/bounty enter", 34) + "§8» §7进入漏洞赏金沙箱");
        sender.sendMessage(" §a" + pad("/bounty leave", 34) + "§8» §7离开漏洞赏金沙箱");
        sender.sendMessage(" §a" + pad("/bounty invite <玩家>", 34) + "§8» §7邀请玩家加入沙箱");
        sender.sendMessage(" §a" + pad("/bounty start <任务>", 34) + "§8» §7开始赏金任务");
        sender.sendMessage(" §a" + pad("/bounty report <描述>", 34) + "§8» §7报告发现的漏洞");
        sender.sendMessage(" §a" + pad("/bounty lb", 34) + "§8» §7查看赏金排行榜");
        sender.sendMessage(" §a" + pad("/bounty complete", 34) + "§8» §7完成当前赏金任务");
        sender.sendMessage("");

        sender.sendMessage("§8════════════════════════════════════════════════════");
        sender.sendMessage(" §7参数说明: §8<> §7必填  §8[] §7可选  §8| §7多选");
        sender.sendMessage("§8════════════════════════════════════════════════════");
        sender.sendMessage("");
    }

    /**
     * 按显示宽度填充空格（中文字符按 2 宽度计算），用于命令对齐排版。
     */
    private String pad(String text, int width) {
        int displayWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            displayWidth += (text.charAt(i) > 127) ? 2 : 1;
        }
        if (displayWidth >= width) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = displayWidth; i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
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