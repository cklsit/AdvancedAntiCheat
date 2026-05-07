package com.anticheat.commands;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public class GotoCommand implements CommandExecutor {

    private final AdvancedAntiCheat plugin;

    public GotoCommand(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家执行！");
            return true;
        }

        if (!player.hasPermission("anticheat.goto")) {
            player.sendMessage(plugin.getConfigManager().getMessage("commands.no-permission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§c用法: §e/goto <玩家>");
            player.sendMessage("§7提示: §f支持跨服务器传送");
            return true;
        }

        String targetName = args[0];

        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            player.teleport(target.getLocation());
            player.sendMessage(plugin.getConfigManager().getMessage("commands.goto-success").replace("{player}", targetName));
            return true;
        }

        player.sendMessage("§6正在查找玩家 §e" + targetName + " §6(可能跨服务器)...");

        sendProxyGotoRequest(player, targetName);

        return true;
    }

    private void sendProxyGotoRequest(Player player, String targetName) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        try {
            dos.writeUTF("Goto");
            dos.writeUTF(player.getName());
            dos.writeUTF(targetName);
        } catch (Exception e) {
            player.sendMessage("§c跨服传送请求失败！");
            return;
        }

        player.sendPluginMessage(plugin, "BungeeCord", baos.toByteArray());

        new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getPlayer(player.getUniqueId()) != null) {
                    Player onlineTarget = Bukkit.getPlayer(targetName);
                    if (onlineTarget != null) {
                        player.teleport(onlineTarget.getLocation());
                        player.sendMessage(plugin.getConfigManager().getMessage("commands.goto-success").replace("{player}", targetName));
                    } else {
                        player.sendMessage("§c玩家 §e" + targetName + " §c当前不在线或不存在");
                    }
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    public void handleProxyResponse(Player player, byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream input = new DataInputStream(bais);
            String subChannel = input.readUTF();

            if ("Goto".equals(subChannel)) {
                String result = input.readUTF();
                String targetServer = input.readUTF();

                if ("SUCCESS".equals(result)) {
                    player.sendMessage("§a已发送跨服传送请求至 §e" + targetServer);
                } else {
                    player.sendMessage("§c跨服传送失败: §e" + result);
                }
            } else if ("PlayerList".equals(subChannel)) {
                String serverName = input.readUTF();
                String[] players = input.readUTF().split(", ");

                if (players.length > 0 && !players[0].isEmpty()) {
                    StringBuilder message = new StringBuilder();
                    message.append("§6服务器 §e").append(serverName).append(" §6的在线玩家:\n");
                    for (String p : players) {
                        if (!p.isEmpty()) {
                            message.append("§e- ").append(p).append("\n");
                        }
                    }
                    player.sendMessage(message.toString());
                } else {
                    player.sendMessage("§c服务器 §e" + serverName + " §c没有在线玩家");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("处理代理响应失败: " + e.getMessage());
        }
    }
}