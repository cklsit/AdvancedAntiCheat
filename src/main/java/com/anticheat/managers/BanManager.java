package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BanManager {

    private final AdvancedAntiCheat plugin;
    private DatabaseManager databaseManager;
    private final Map<UUID, BanInfo> bans = new HashMap<>();
    private final File bansFile;

    public BanManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.bansFile = new File(plugin.getDataFolder(), "bans.dat");
        initializeDatabase();
        loadBans();
    }

    private void initializeDatabase() {
        try {
            databaseManager = new DatabaseManager(plugin);
            plugin.getLogger().info("数据库管理器初始化完成，使用类型: " + databaseManager.getDatabaseType());
        } catch (Exception e) {
            plugin.getLogger().severe("数据库初始化失败: " + e.getMessage());
            databaseManager = null;
        }
    }

    public boolean isBanned(UUID uuid) {
        if (databaseManager != null && databaseManager.isPlayerBanned(uuid)) {
            return true;
        }
        BanInfo info = bans.get(uuid);
        if (info == null) return false;
        if (info.getEndTime() == -1) return true;
        if (System.currentTimeMillis() >= info.getEndTime()) {
            bans.remove(uuid);
            saveBans();
            return false;
        }
        return true;
    }

    public boolean isBanned(String name) {
        if (databaseManager != null) {
            UUID uuid = getPlayerUUID(name);
            if (uuid != null && databaseManager.isPlayerBanned(uuid)) {
                return true;
            }
        }
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

        String serverName = plugin.getConfig().getString("database.server-name", "Unknown");
        String bannedBy = "Console";

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            Player o = player;
            if (o.isOp()) {
                bannedBy = o.getName();
            }
            kickPlayer(player, duration, reason);
        }

        if (databaseManager != null) {
            databaseManager.banPlayer(uuid, name, reason, bannedBy, System.currentTimeMillis(), endTime, serverName);
        }

        saveBans();
        String msg = String.format(plugin.getConfigManager().getMessage("banSuccess"), name, formatDuration(duration));
        Bukkit.broadcast(msg, "anticheat.notify");
    }

    public void banPlayer(String targetName, String duration, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            banPlayer(target.getUniqueId(), target.getName(), duration, reason);
        } else {
            UUID uuid = getPlayerUUID(targetName);
            if (uuid != null) {
                banPlayer(uuid, targetName, duration, reason);
            } else {
                uuid = UUID.randomUUID();
                banPlayer(uuid, targetName, duration, reason);
            }
        }
    }

    public void unbanPlayer(UUID uuid) {
        BanInfo info = bans.remove(uuid);
        if (databaseManager != null) {
            databaseManager.unbanPlayer(uuid);
        }
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
        } else {
            if (databaseManager != null) {
                UUID uuid = getPlayerUUID(name);
                if (uuid != null) {
                    databaseManager.unbanPlayer(uuid);
                    String msg = String.format(plugin.getConfigManager().getMessage("unbanSuccess"), name);
                    Bukkit.broadcast(msg, "anticheat.notify");
                }
            }
        }
    }

    public BanInfo getBanInfo(UUID uuid) {
        if (databaseManager != null) {
            BanRecord record = databaseManager.getBanRecord(uuid);
            if (record != null) {
                return new BanInfo(record.playerName, record.expiryTime, record.reason);
            }
        }
        return bans.get(uuid);
    }

    public List<BanInfo> getAllBans() {
        List<BanInfo> allBans = new ArrayList<>(bans.values());
        if (databaseManager != null) {
            for (BanRecord record : databaseManager.getAllBans()) {
                boolean found = false;
                for (BanInfo info : allBans) {
                    if (info.getName().equalsIgnoreCase(record.playerName)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    allBans.add(new BanInfo(record.playerName, record.expiryTime, record.reason));
                }
            }
        }
        return allBans;
    }

    private void kickPlayer(Player player, String duration, String reason) {
        StringBuilder kickMessage = new StringBuilder();
        kickMessage.append("§c§l═══════════════════════════\n");
        kickMessage.append("§c        §l您已被服务器封禁!\n");
        kickMessage.append("§c═══════════════════════════\n");
        kickMessage.append("§7封禁时长: §e").append(formatDuration(duration)).append("\n");
        kickMessage.append("§7封禁原因: §f").append(reason).append("\n");
        kickMessage.append("§c═══════════════════════════\n");
        kickMessage.append("§6如有疑问请联系服务器管理员");
        player.kickPlayer(kickMessage.toString());
    }

    public long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return -1;
        }

        duration = duration.toLowerCase().trim();

        if (duration.equals("permanent") || duration.equals("perm") || duration.equals("forever")) {
            return -1;
        }

        long time = 0;

        try {
            if (duration.contains("d")) {
                String numStr = duration.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    time = Long.parseLong(numStr) * 24 * 60 * 60 * 1000;
                }
            } else if (duration.contains("h")) {
                String numStr = duration.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    time = Long.parseLong(numStr) * 60 * 60 * 1000;
                }
            } else if (duration.contains("m")) {
                String numStr = duration.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    time = Long.parseLong(numStr) * 60 * 1000;
                }
            } else if (duration.contains("s")) {
                String numStr = duration.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    time = Long.parseLong(numStr) * 1000;
                }
            } else {
                String numStr = duration.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    time = Long.parseLong(numStr) * 24 * 60 * 60 * 1000;
                } else {
                    return -1;
                }
            }
        } catch (NumberFormatException e) {
            return -1;
        }

        return time > 0 ? System.currentTimeMillis() + time : -1;
    }

    private String formatDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return "永久";
        }
        duration = duration.toLowerCase().trim();
        if (duration.equals("permanent") || duration.equals("perm") || duration.equals("forever")) {
            return "永久";
        }
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

    private UUID getPlayerUUID(String playerName) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) {
                return player.getUniqueId();
            }
        }
        return null;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
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

    public static class BanRecord {
        public final UUID playerUUID;
        public final String playerName;
        public final String reason;
        public final String bannedBy;
        public final long banTime;
        public final long expiryTime;
        public final String serverName;

        public BanRecord(UUID playerUUID, String playerName, String reason, String bannedBy, long banTime, long expiryTime, String serverName) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.reason = reason;
            this.bannedBy = bannedBy;
            this.banTime = banTime;
            this.expiryTime = expiryTime;
            this.serverName = serverName;
        }
    }
}
