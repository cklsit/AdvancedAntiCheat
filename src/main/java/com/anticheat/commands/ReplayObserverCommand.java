package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 观察者跟随/解绑命令。
 *
 * 一个类同时处理：
 *   /aac_replay_follow   <观察者名> <目标玩家名或UUID>
 *   /aac_replay_unfollow <观察者名>
 *
 * 权限：
 *   - 控制台发送者：总是允许
 *   - 玩家发送者：需要 anticheat.replay.control
 */
public class ReplayObserverCommand implements CommandExecutor {

    private static final String PERM_CONTROL = "anticheat.replay.control";
    private static final String CMD_FOLLOW = "aac_replay_follow";

    private final AdvancedAntiCheat plugin;

    public ReplayObserverCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 权限：仅控制台或持有 anticheat.replay.control 的玩家可操作
        if (!(sender instanceof ConsoleCommandSender)) {
            if (!sender.hasPermission(PERM_CONTROL)) {
                sender.sendMessage(ChatColor.RED + "你没有权限：" + PERM_CONTROL);
                return true;
            }
        }

        if (plugin.getObserverFollowManager() == null) {
            sender.sendMessage(ChatColor.RED + "[Replay] ObserverFollowManager 未初始化，请检查日志");
            return true;
        }

        boolean isFollow = CMD_FOLLOW.equalsIgnoreCase(command.getName());

        if (isFollow) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /aac_replay_follow <观察者名> <目标玩家名或UUID>");
                return true;
            }
            String obsName = args[0];
            String target = args[1];

            UUID targetUUID = parseUUIDOrName(target);
            if (targetUUID == null) {
                sender.sendMessage(ChatColor.RED + "[Replay] 玩家不在线或无效 UUID: " + target);
                return true;
            }

            plugin.getObserverFollowManager().startFollow(obsName, targetUUID);
            sender.sendMessage(ChatColor.GREEN + "[Replay] " + obsName + " → 开始跟随 " + target);
        } else {
            // unfollow
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /aac_replay_unfollow <观察者名>");
                return true;
            }
            plugin.getObserverFollowManager().stopFollow(args[0]);
            sender.sendMessage(ChatColor.YELLOW + "[Replay] " + args[0] + " → 已停止跟随");
        }
        return true;
    }

    /**
     * 解析目标：先按 UUID.fromString；失败再按玩家名取在线玩家 UUID；都失败返回 null。
     */
    private UUID parseUUIDOrName(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            // 非 UUID，按玩家名解析
        }
        Player target = Bukkit.getPlayerExact(s);
        if (target != null && target.isOnline()) {
            return target.getUniqueId();
        }
        return null;
    }
}
