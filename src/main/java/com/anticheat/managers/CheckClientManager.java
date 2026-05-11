package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.compat.CompatManager;
import com.anticheat.compat.ChatCompat;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheckClientManager {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, CheckInfo> checkingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Location> frozenLocations = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> originalGameModes = new ConcurrentHashMap<>();
    private final File checkDataFile;
    private final ChatCompat chatCompat;

    public CheckClientManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.checkDataFile = new File(plugin.getDataFolder(), "checkdata.dat");
        this.chatCompat = CompatManager.getChatCompat();
        loadCheckData();
    }

    public boolean isBeingChecked(UUID playerUUID) {
        return checkingPlayers.containsKey(playerUUID);
    }

    public boolean startCheck(Player target, Player admin, String qqNumber) {
        UUID targetUUID = target.getUniqueId();

        if (isBeingChecked(targetUUID)) {
            return false;
        }

        frozenLocations.put(targetUUID, target.getLocation().clone());
        originalGameModes.put(targetUUID, target.getGameMode());

        CheckInfo info = new CheckInfo(
            targetUUID,
            target.getName(),
            admin.getUniqueId(),
            admin.getName(),
            qqNumber,
            System.currentTimeMillis(),
            System.currentTimeMillis() + getCheckTimeout() * 60 * 1000L
        );
        checkingPlayers.put(targetUUID, info);

        applyRestrictions(target);
        applyBlindnessEffect(target);
        showTitle(target);
        showChatMessage(target, admin.getName(), qqNumber);

        startTimeoutTask(targetUUID);

        return true;
    }

    public void endCheckPass(Player player) {
        UUID playerUUID = player.getUniqueId();

        if (!isBeingChecked(playerUUID)) {
            return;
        }

        removeRestrictions(player);
        chatCompat.sendMessage(player, "§a玩家已被解除检查状态!");

        checkingPlayers.remove(playerUUID);
        frozenLocations.remove(playerUUID);
        originalGameModes.remove(playerUUID);

        saveCheckData();
    }

    public void endCheckFail(Player player) {
        UUID playerUUID = player.getUniqueId();

        if (!isBeingChecked(playerUUID)) {
            return;
        }

        removeRestrictions(player);

        String ip = player.getAddress().getAddress().getHostAddress();
        String playerName = player.getName();

        plugin.getBanManager().banPlayer(
            playerUUID,
            playerName,
            "permanent",
            "客户端检查未通过 - IP: " + ip
        );

        plugin.getLogger().info("玩家 " + playerName + " (IP: " + ip + ") 因客户端检查未通过被永久封禁");

        checkingPlayers.remove(playerUUID);
        frozenLocations.remove(playerUUID);
        originalGameModes.remove(playerUUID);

        saveCheckData();
    }

    public void banOnQuit(UUID playerUUID, String playerName, String ip) {
        plugin.getBanManager().banPlayer(
            playerUUID,
            playerName,
            "permanent",
            "查端过程中退出服务器 - IP: " + ip
        );

        plugin.getLogger().info("玩家 " + playerName + " (IP: " + ip + ") 在查端过程中退出，已被永久封禁");

        checkingPlayers.remove(playerUUID);
        frozenLocations.remove(playerUUID);
        originalGameModes.remove(playerUUID);
        saveCheckData();
    }

    public void forceUnfreeze(Player player) {
        UUID playerUUID = player.getUniqueId();
        if (checkingPlayers.containsKey(playerUUID)) {
            removeRestrictions(player);
            checkingPlayers.remove(playerUUID);
            frozenLocations.remove(playerUUID);
            originalGameModes.remove(playerUUID);
            saveCheckData();
        }
    }

    private void applyRestrictions(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    private void removeRestrictions(Player player) {
        UUID playerUUID = player.getUniqueId();

        if (originalGameModes.containsKey(playerUUID)) {
            player.setGameMode(originalGameModes.get(playerUUID));
        }

        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.2f);

        player.removePotionEffect(PotionEffectType.BLINDNESS);

        if (frozenLocations.containsKey(playerUUID)) {
            player.teleport(frozenLocations.get(playerUUID));
        }

        frozenLocations.remove(playerUUID);
        originalGameModes.remove(playerUUID);
    }

    private void applyBlindnessEffect(Player player) {
        if (VersionUtil.isHighVersion()) {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS,
                Integer.MAX_VALUE,
                255,
                false,
                false,
                false
            ));
        } else {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS,
                Integer.MAX_VALUE,
                255
            ));
        }
    }

    private void showTitle(Player player) {
        String title = plugin.getCheckClientConfigManager().getTitle();
        String subtitle = plugin.getCheckClientConfigManager().getSubtitle();
        chatCompat.sendTitle(player, title, subtitle, 10, 100, 20);
    }

    private void showChatMessage(Player player, String adminName, String qqNumber) {
        String vaultGroup = getVaultGroup(player);
        int timeout = getCheckTimeout();
        
        List<String> messages = plugin.getCheckClientConfigManager().formatMessages(vaultGroup, adminName, qqNumber, timeout);
        for (String message : messages) {
            player.sendMessage(message);
        }
    }

    private String getVaultGroup(Player player) {
        return "§a默认";
    }

    private int getCheckTimeout() {
        return plugin.getCheckClientConfigManager().getTimeoutMinutes();
    }

    private void startTimeoutTask(UUID playerUUID) {
        CheckInfo info = checkingPlayers.get(playerUUID);
        if (info == null) return;

        long delay = info.expiryTime - System.currentTimeMillis();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!checkingPlayers.containsKey(playerUUID)) {
                    return;
                }

                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    player.sendMessage("§c查段时间已到！您因未完成客户端检查被永久封禁！");
                    endCheckFail(player);
                }
            }
        }.runTaskLater(plugin, delay / 50);
    }

    private void loadCheckData() {
        if (!checkDataFile.exists() || checkDataFile.length() == 0) {
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new java.io.FileInputStream(checkDataFile))) {
            @SuppressWarnings("unchecked")
            Map<UUID, CheckInfo> loaded = (Map<UUID, CheckInfo>) ois.readObject();

            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, CheckInfo> entry : loaded.entrySet()) {
                if (entry.getValue().expiryTime > now) {
                    checkingPlayers.put(entry.getKey(), entry.getValue());
                }
            }

            plugin.getLogger().info("已加载 " + checkingPlayers.size() + " 个未完成的客户端检查");
        } catch (Exception e) {
            plugin.getLogger().warning("加载检查数据失败: " + e.getMessage());
        }
    }

    public void saveCheckData() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new java.io.FileOutputStream(checkDataFile))) {
            oos.writeObject(new HashMap<>(checkingPlayers));
        } catch (IOException e) {
            plugin.getLogger().severe("保存检查数据失败: " + e.getMessage());
        }
    }

    public void onPlayerQuit(Player player) {
        UUID playerUUID = player.getUniqueId();
        if (checkingPlayers.containsKey(playerUUID)) {
            removeRestrictions(player);
            checkingPlayers.remove(playerUUID);
            frozenLocations.remove(playerUUID);
            originalGameModes.remove(playerUUID);
            saveCheckData();
        }
    }

    public static class CheckInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        public final UUID playerUUID;
        public final String playerName;
        public final UUID adminUUID;
        public final String adminName;
        public final String qqNumber;
        public final long startTime;
        public final long expiryTime;

        public CheckInfo(UUID playerUUID, String playerName, UUID adminUUID, String adminName,
                         String qqNumber, long startTime, long expiryTime) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.adminUUID = adminUUID;
            this.adminName = adminName;
            this.qqNumber = qqNumber;
            this.startTime = startTime;
            this.expiryTime = expiryTime;
        }
    }
}
