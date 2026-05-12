package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CaptchaCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public CaptchaCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.captcha")) {
            sender.sendMessage("§c没有权限使用此命令");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e用法:");
            sender.sendMessage("§7/captcha <玩家> - 对指定玩家发起验证码测试");
            sender.sendMessage("§7/captcha toggle - 开启/关闭新玩家验证码");
            sender.sendMessage("§7/captcha timelimit <秒数> - 设置验证码时间限制");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("toggle")) {
            boolean enabled = !plugin.getCaptchaManager().isNewPlayerCaptchaEnabled();
            plugin.getCaptchaManager().setNewPlayerCaptchaEnabled(enabled);
            sender.sendMessage(enabled ? "§a新玩家验证码已开启" : "§c新玩家验证码已关闭");
            return true;
        }

        if (subCommand.equals("timelimit")) {
            if (args.length < 2) {
                sender.sendMessage("§c请指定时间限制（秒）");
                return true;
            }

            try {
                int time = Integer.parseInt(args[1]);
                if (time < 10 || time > 300) {
                    sender.sendMessage("§c时间限制必须在 10-300 秒之间");
                    return true;
                }

                plugin.getCaptchaManager().setTimeLimit(time);
                sender.sendMessage("§a验证码时间限制已设置为 " + time + " 秒");
            } catch (NumberFormatException e) {
                sender.sendMessage("§c无效的数字");
            }
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§c找不到玩家: " + args[0]);
            return true;
        }

        if (plugin.getCaptchaManager().isInCaptcha(target)) {
            sender.sendMessage("§c该玩家已经在进行验证码验证");
            return true;
        }

        plugin.getCaptchaManager().startCaptcha(target, CaptchaManager.CaptchaSession.Initiator.ADMIN);
        sender.sendMessage("§a已对玩家 " + target.getName() + " 发起验证码测试");

        return true;
    }
}
