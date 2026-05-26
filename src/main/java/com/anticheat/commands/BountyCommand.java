package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.bounty.BountyTaskType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BountyCommand implements CommandExecutor {
    private final AdvancedAntiCheat plugin;

    public BountyCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "enter":
                plugin.getBountyManager().enterBounty(player);
                break;
            case "leave":
                plugin.getBountyManager().leaveBounty(player);
                break;
            case "invite":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "用法: /bounty invite <玩家>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage(ChatColor.RED + "玩家不在线");
                    return true;
                }
                plugin.getBountyManager().invitePlayer(player, target);
                break;
            case "report":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "用法: /bounty report <描述>");
                    return true;
                }
                StringBuilder description = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    description.append(args[i]).append(" ");
                }
                plugin.getBountyManager().reportFinding(player, description.toString().trim());
                break;
            case "lb":
            case "leaderboard":
                plugin.getBountyManager().showLeaderboard(player);
                break;
            case "start":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "用法: /bounty start <任务类型>");
                    player.sendMessage(ChatColor.YELLOW + "可用任务: MOVE_BASIC, MOVE_ADVANCED, COMBAT_BASIC, COMBAT_ADVANCED, INVENTORY_CHALLENGE, FREE_TEST");
                    return true;
                }
                try {
                    BountyTaskType taskType = BountyTaskType.valueOf(args[1].toUpperCase());
                    if (plugin.getBountyManager().isInBounty(player)) {
                        plugin.getBountyManager().getSession(player).startTask(taskType);
                    } else {
                        player.sendMessage(ChatColor.RED + "你不在漏洞赏金沙箱中");
                    }
                } catch (IllegalArgumentException e) {
                    player.sendMessage(ChatColor.RED + "无效的任务类型");
                }
                break;
            
            default:
                sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "========== 漏洞赏金命令 ==========");
        player.sendMessage(ChatColor.GREEN + "/bounty enter" + ChatColor.WHITE + " - 进入漏洞赏金沙箱");
        player.sendMessage(ChatColor.GREEN + "/bounty leave" + ChatColor.WHITE + " - 离开漏洞赏金沙箱");
        player.sendMessage(ChatColor.GREEN + "/bounty invite <玩家>" + ChatColor.WHITE + " - 邀请玩家进入沙箱");
        player.sendMessage(ChatColor.GREEN + "/bounty report <描述>" + ChatColor.WHITE + " - 报告发现的问题");
        player.sendMessage(ChatColor.GREEN + "/bounty lb" + ChatColor.WHITE + " - 查看排行榜");
        player.sendMessage(ChatColor.GREEN + "/bounty start <任务>" + ChatColor.WHITE + " - 开始任务");
        player.sendMessage(ChatColor.GOLD + "=================================");
    }
}
