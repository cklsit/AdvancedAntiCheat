package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.bounty.BountyBoard;
import com.anticheat.bounty.BountyManager;
import com.anticheat.bounty.BountyShop;
import com.anticheat.bounty.BountyTaskType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * `/bounty` 命令。
 *
 * <p>子命令分两档权限：普通玩家（enter/leave/board/shop/start/status/rank/cases/report）
 * 与管理员（pending/accept/reject/invite）。管理员动作里最重要的是
 * **accept/reject**：它们是文档第六节"特征库污染防护"的人工闸门——
 * 沙箱数据（哪怕是"绕过成功"）也**不会自动**流入生产规则，必须经人审核。</p>
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "enter", "leave", "board", "shop", "start", "status", "rank", "cases", "report",
            "pending", "accept", "reject", "invite", "help");

    private static final List<String> ADMIN_SUB_COMMANDS =
            Arrays.asList("pending", "accept", "reject", "invite");

    private final AdvancedAntiCheat plugin;

    public BountyCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    private BountyManager manager() {
        return plugin.getBountyManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // ---- 管理员子命令 ----
        if (ADMIN_SUB_COMMANDS.contains(sub)) {
            if (!sender.hasPermission("anticheat.bounty.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限使用该命令");
                return true;
            }
            switch (sub) {
                case "pending":
                    if (requirePlayer(sender) == null) return true;
                    manager().showPendingCases(requirePlayer(sender), 15);
                    return true;
                case "accept":
                case "reject": {
                    Player admin = requirePlayer(sender);
                    if (admin == null) return true;
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /" + label + " " + sub + " <案例ID>");
                        return true;
                    }
                    long id;
                    try {
                        id = Long.parseLong(args[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(ChatColor.RED + "案例 ID 必须是数字");
                        return true;
                    }
                    manager().reviewCase(admin, id, "accept".equals(sub));
                    return true;
                }
                case "invite": {
                    Player admin = requirePlayer(sender);
                    if (admin == null) return true;
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /" + label + " invite <玩家>");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null || !target.isOnline()) {
                        sender.sendMessage(ChatColor.RED + "玩家不在线");
                        return true;
                    }
                    manager().invitePlayer(admin, target);
                    return true;
                }
                default:
                    break;
            }
        }

        // ---- 玩家子命令 ----
        Player player = requirePlayer(sender);
        if (player == null) return true;

        switch (sub) {
            case "enter":
                manager().enterBounty(player);
                break;
            case "leave":
                manager().leaveBounty(player);
                break;
            case "board":
                if (!manager().isInBounty(player)) {
                    player.sendMessage(ChatColor.RED + "请先用 /" + label + " enter 进入沙箱");
                    return true;
                }
                BountyBoard.open(plugin, manager(), player);
                break;
            case "shop":
                BountyShop.open(plugin, manager(), player);
                break;
            case "start": {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "用法: /" + label + " start <任务>");
                    player.sendMessage(ChatColor.GRAY + "可用任务: " + BountyTaskType.optionsText());
                    return true;
                }
                BountyTaskType task = BountyTaskType.byId(args[1]);
                if (task == null) {
                    player.sendMessage(ChatColor.RED + "无效的任务类型: " + args[1]);
                    player.sendMessage(ChatColor.GRAY + "可用任务: " + BountyTaskType.optionsText());
                    return true;
                }
                manager().startTask(player, task);
                break;
            }
            case "status":
                manager().showStatus(player);
                break;
            case "rank":
            case "lb":
            case "leaderboard":
                manager().showLeaderboard(player);
                break;
            case "cases":
                manager().showMyCases(player);
                break;
            case "report": {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "用法: /" + label + " report <描述>");
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    sb.append(args[i]).append(' ');
                }
                manager().reportFinding(player, sb.toString().trim());
                break;
            }
            case "help":
            default:
                sendHelp(sender, label);
                break;
        }
        return true;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return (Player) sender;
        sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行");
        return null;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "========== 漏洞赏金命令 ==========");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " enter" + ChatColor.WHITE + " - 进入沙箱（每天有额度）");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " board" + ChatColor.WHITE + " - 打开任务板");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " start <任务>" + ChatColor.WHITE + " - 直接开始任务");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " shop" + ChatColor.WHITE + " - 赏金商城（称号/特效）");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " rank" + ChatColor.WHITE + " - 赏金猎人排行");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " cases" + ChatColor.WHITE + " - 我的提交记录");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " status" + ChatColor.WHITE + " - 沙箱状态与额度");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " report <描述>" + ChatColor.WHITE + " - 主动报告发现");
        sender.sendMessage(ChatColor.GREEN + "/" + label + " leave" + ChatColor.WHITE + " - 退出沙箱");
        if (sender.hasPermission("anticheat.bounty.admin")) {
            sender.sendMessage(ChatColor.AQUA + "--- 管理员 ---");
            sender.sendMessage(ChatColor.GREEN + "/" + label + " pending" + ChatColor.WHITE + " - 待复核案例");
            sender.sendMessage(ChatColor.GREEN + "/" + label + " accept|reject <ID>" + ChatColor.WHITE + " - 审核案例");
            sender.sendMessage(ChatColor.GREEN + "/" + label + " invite <玩家>" + ChatColor.WHITE + " - 邀请玩家");
        }
        sender.sendMessage(ChatColor.GOLD + "=================================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUB_COMMANDS) {
                if (!ADMIN_SUB_COMMANDS.contains(sub) || sender.hasPermission("anticheat.bounty.admin")) {
                    if (sub.startsWith(prefix)) out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && "start".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String id : BountyManager.taskIds()) {
                if (id.startsWith(prefix)) out.add(id);
            }
            return out;
        }
        if (args.length == 2 && ("accept".equalsIgnoreCase(args[0]) || "reject".equalsIgnoreCase(args[0])
                || "invite".equalsIgnoreCase(args[0]))) {
            return out;
        }
        return out;
    }
}
