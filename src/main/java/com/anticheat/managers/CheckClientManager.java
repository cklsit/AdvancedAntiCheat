package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheckClientManager {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, CheckInfo> checkingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Location> frozenLocations = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> originalGameModes = new ConcurrentHashMap<>();
    private final File checkDataFile;

    public CheckClientManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.checkDataFile = new File(plugin.getDataFolder(), "checkdata.dat");
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
        player.sendMessage(Component.text("玩家已被解除检查状态!", NamedTextColor.GREEN));

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

    public void forceUnfreeze(Player player) {
        UUID playerUUID = player.getUniqueId();
        if (checkingPlayers.containsKey(playerUUID)) {
            removeRestrictions(player);
            player.sendMessage(Component.text("检查已被管理员强制解除!", NamedTextColor.YELLOW));
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
        player.addPotionEffect(new PotionEffect(
            PotionEffectType.BLINDNESS,
            Integer.MAX_VALUE,
            255,
            false,
            false,
            false
        ));
    }

    private void showTitle(Player player) {
        Title title = Title.title(
            Component.text("您正在被管理员查端!", NamedTextColor.RED),
            Component.text("请看聊天框继续下一步", NamedTextColor.YELLOW),
            Title.Times.times(
                Duration.ofMillis(500),
                Duration.ofMillis(5000),
                Duration.ofMillis(1000)
            )
        );

        player.showTitle(title);
    }

    private void showChatMessage(Player player, String adminName, String qqNumber) {
        String vaultGroup = getVaultGroup(player);

        player.sendMessage(Component.text("§8§m------------------------------------------------"));
        player.sendMessage(Component.text("§f您已被 §b" + vaultGroup + " §f成员 §c§l冻结所有操作.", NamedTextColor.WHITE));
        player.sendMessage(Component.text("§f请在 §b5 §f分钟内添加 §c" + adminName + " §f的 §bQQ §f好友 §a" + qqNumber + " §f进行客户端核实。", NamedTextColor.WHITE));
        player.sendMessage(Component.text("§f请不要退出此房间或关闭游戏,否则您的账号将会被封禁！", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("§8§m------------------------------------------------"));
    }

    private String getVaultGroup(Player player) {
        return "§a默认";
    }

    private int getCheckTimeout() {
        return plugin.getConfig().getInt("check-client.timeout-minutes", 60);
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
                    player.sendMessage(Component.text("§c查段时间已到！您因未完成客户端检查被永久封禁！", NamedTextColor.RED));
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

    public void onPlayerMove(Player player, Location from, Location to) {
        UUID playerUUID = player.getUniqueId();
        if (checkingPlayers.containsKey(playerUUID)) {
            Location frozenLoc = frozenLocations.get(playerUUID);
            if (frozenLoc != null && (to.getX() != frozenLoc.getX() || to.getZ() != frozenLoc.getZ())) {
                player.teleport(from);
            }
        }
    }

    public void onPlayerTeleport(Player player, Location from, Location to) {
        UUID playerUUID = player.getUniqueId();
        if (checkingPlayers.containsKey(playerUUID)) {
            player.teleport(from);
        }
    }

    public void onPlayerInteract(Player player) {
        UUID playerUUID = player.getUniqueId();
        if (checkingPlayers.containsKey(playerUUID)) {
            player.sendMessage(Component.text("§c您正在被管理员查端，无法进行此操作！", NamedTextColor.RED));
        }
    }

    public void onPlayerAttack(Player attacker, Player victim) {
        UUID attackerUUID = attacker.getUniqueId();
        if (checkingPlayers.containsKey(attackerUUID)) {
            attacker.sendMessage(Component.text("§c您正在被管理员查端，无法进行攻击！", NamedTextColor.RED));
            attacker.setVelocity(new Vector(0, 0, 0));
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
