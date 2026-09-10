package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.gui.ProfileGUI;
import com.anticheat.managers.ReportManager;
import com.anticheat.web.util.PasswordHasher;
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
            // 同步 AuthManager 配置（reloadConfig 内部已触发，再保险一次）
            if (plugin.getAuthManager() != null) {
                plugin.getAuthManager().reload();
            }
            sender.sendMessage("§a[AntiCheat] 配置和消息文件已重新加载！");
        } else if (subCommand.equals("stats")) {
            showStats(sender);
        } else if (subCommand.equals("reports")) {
            showReports(sender);
        } else if (subCommand.equals("help")) {
            showHelp(sender);
        } else if (subCommand.equals("profile")) {
            handleProfile(sender, args);
        } else if (subCommand.equals("genpwd")) {
            handleGenpwd(sender, args);
        } else {
            sender.sendMessage("§c未知子命令！使用 /ac help 查看帮助");
        }

        return true;
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
        sender.sendMessage(" §a" + pad("/ac genpwd <密码>", 34) + "§8» §7生成 Web 面板密码哈希");
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

    /**
     * /ac genpwd <明文密码>：输出 bcrypt 哈希，方便用户填入 config.yml 的 web.auth.accounts[].password-hash。
     */
    private void handleGenpwd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ac genpwd <明文密码>");
            return;
        }
        String plain = args[1];
        String hash = PasswordHasher.hash(plain);
        if (hash == null) {
            sender.sendMessage("§c生成哈希失败，请检查控制台日志");
            return;
        }
        sender.sendMessage("§a[AntiCheat] Web 面板密码哈希已生成：");
        sender.sendMessage("§7明文: §f" + plain);
        sender.sendMessage("§7hash: §6" + hash);
        sender.sendMessage("§7将上述 hash 填入 config.yml 的 web.auth.accounts[].password-hash 字段，然后执行 /ac reload");
    }
}