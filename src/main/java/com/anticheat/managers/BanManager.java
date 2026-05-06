package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BanManager {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, BanInfo> bans = new HashMap<>();
    private final File bansFile;

    public BanManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.bansFile = new File(plugin.getDataFolder(), "bans.dat");
        loadBans();
    }

    public boolean isBanned(UUID uuid) {
        BanInfo info = bans.get(uuid);
        if (info == null) return false;
        if (info.getEndTime() == -1) return true;
        return System.currentTimeMillis() < info.getEndTime();
    }

    public boolean isBanned(String name) {
        for (Map.Entry<UUID, BanInfo> entry : bans.entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(name)) {
                return isBanned(entry.getKey());
            }
        }
        return false;
    }

    public void banPlayer(UUID uuid, String name, String duration, String reason) {
        long endTime = parseDuration(duration);
        BanInfo banInfo = new BanInfo(name, endTime, reason);
        bans.put(uuid, banInfo);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            kickPlayer(player, duration, reason);
        }

        saveBans();
        String msg = String.format(plugin.getConfigManager().getMessage("banSuccess"), name, formatDuration(duration));
        Bukkit.broadcast(msg, "anticheat.notify");
    }

    public void unbanPlayer(UUID uuid) {
        BanInfo info = bans.remove(uuid);
        if (info != null) {
            saveBans();
            String msg = String.format(plugin.getConfigManager().getMessage("unbanSuccess"), info.getName());
            Bukkit.broadcast(msg, "anticheat.notify");
        }
    }

    public void unbanPlayer(String name) {
        UUID uuidToRemove = null;
        for (Map.Entry<UUID, BanInfo> entry : bans.entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(name)) {
                uuidToRemove = entry.getKey();
                break;
            }
        }
        if (uuidToRemove != null) {
            unbanPlayer(uuidToRemove);
        }
    }

    public BanInfo getBanInfo(UUID uuid) {
        return bans.get(uuid);
    }

    private void kickPlayer(Player player, String duration, String reason) {
        StringBuilder kickMessage = new StringBuilder();
        kickMessage.append("§c§l═══════════════════════════\n");
        kickMessage.append("§c      您已被服务器封禁!\n");
        kickMessage.append("§c═══════════════════════════\n");
        kickMessage.append("§7封禁时长: §e").append(formatDuration(duration)).append("\n");
        kickMessage.append("§7封禁原因: §f").append(reason).append("\n");
        kickMessage.append("§c═══════════════════════════\n");
        kickMessage.append("§6如有疑问请联系服务器管理员");
        player.kickPlayer(kickMessage.toString());
    }

    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return -1;
        }

        long time = 0;
        duration = duration.toLowerCase();

        if (duration.contains("d")) {
            time += Long.parseLong(duration.replaceAll("[^0-9]", "")) * 24 * 60 * 60 * 1000;
        } else if (duration.contains("h")) {
            time += Long.parseLong(duration.replaceAll("[^0-9]", "")) * 60 * 60 * 1000;
        } else if (duration.contains("m")) {
            time += Long.parseLong(duration.replaceAll("[^0-9]", "")) * 60 * 1000;
        } else if (duration.contains("s")) {
            time += Long.parseLong(duration.replaceAll("[^0-9]", "")) * 1000;
        } else {
            try {
                time = Long.parseLong(duration) * 24 * 60 * 60 * 1000;
            } catch (NumberFormatException e) {
                time = -1;
            }
        }

        return time > 0 ? System.currentTimeMillis() + time : -1;
    }

    private String formatDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return "永久";
        }
        duration = duration.toLowerCase();
        if (duration.contains("d")) return duration.replace("d", " 天");
        if (duration.contains("h")) return duration.replace("h", " 小时");
        if (duration.contains("m")) return duration.replace("m", " 分钟");
        if (duration.contains("s")) return duration.replace("s", " 秒");
        return duration + " 天";
    }

    private void loadBans() {
        if (!bansFile.exists()) {
            try {
                bansFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("无法创建封禁文件: " + e.getMessage());
            }
            return;
        }

        if (bansFile.length() == 0) {
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(bansFile))) {
            @SuppressWarnings("unchecked")
            Map<UUID, BanInfo> loaded = (Map<UUID, BanInfo>) ois.readObject();
            bans.putAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().severe("加载封禁文件失败: " + e.getMessage());
        }
    }

    public void saveBans() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(bansFile))) {
            oos.writeObject(bans);
        } catch (Exception e) {
            plugin.getLogger().severe("保存封禁文件失败: " + e.getMessage());
        }
    }

    public static class BanInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final long endTime;
        private final String reason;

        public BanInfo(String name, long endTime, String reason) {
            this.name = name;
            this.endTime = endTime;
            this.reason = reason;
        }

        public String getName() {
            return name;
        }

        public long getEndTime() {
            return endTime;
        }

        public String getReason() {
            return reason;
        }
    }
}