package com.anticheat.detection;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord.Severity;
import com.anticheat.detection.ViolationRecord.ViolationType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ViolationManager {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, Map<ViolationType, ViolationCount>> playerViolations;
    private final Map<UUID, List<ViolationRecord>> violationHistory;
    private final File violationDataFile;

    private static final long KICK_COOLDOWN = 5000;
    private static final long WARN_COOLDOWN = 10000;

    private final Map<UUID, Long> lastKickTime;
    private final Map<UUID, Long> lastWarnTime;

    public ViolationManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.playerViolations = new ConcurrentHashMap<>();
        this.violationHistory = new ConcurrentHashMap<>();
        this.violationDataFile = new File(plugin.getDataFolder(), "violations.dat");
        this.lastKickTime = new ConcurrentHashMap<>();
        this.lastWarnTime = new ConcurrentHashMap<>();
        loadViolationData();
    }

    public void recordViolation(Player player, ViolationType type, String details, double violationLevel) {
        recordViolation(player, type, type.getDefaultSeverity(), details, violationLevel);
    }

    public void recordViolation(Player player, ViolationType type, Severity severity, String details, double violationLevel) {
        UUID uuid = player.getUniqueId();

        ViolationRecord record = new ViolationRecord(uuid, player.getName(), type, severity, details, violationLevel);

        violationHistory.computeIfAbsent(uuid, k -> new ArrayList<>()).add(record);

        playerViolations.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, k -> new ViolationCount())
                .increment();

        PunishmentResult punishment = calculatePunishment(type, severity, getViolationCount(uuid, type));

        executePunishment(player, punishment, type);

        saveViolationData();

        notifyAdmins(player, type, punishment);
    }

    public void recordViolation(Player player, ViolationType type) {
        recordViolation(player, type, "", 1.0);
    }

    private PunishmentResult calculatePunishment(ViolationType type, Severity severity, int violationCount) {
        switch (severity) {
            case CRITICAL:
                return calculateCriticalPunishment(type, violationCount);
            case HIGH:
                return calculateHighPunishment(type, violationCount);
            case MEDIUM:
                return calculateMediumPunishment(type, violationCount);
            case LOW:
                return calculateLowPunishment(type, violationCount);
            case MINOR:
                return calculateMinorPunishment(type, violationCount);
            default:
                return new PunishmentResult(PunishmentType.WARN, "未知违规", 0);
        }
    }

    private PunishmentResult calculateCriticalPunishment(ViolationType type, int violationCount) {
        switch (type) {
            case FLY:
            case SPEED:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "30d", 30 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case INVENTORY_DUPE:
                return new PunishmentResult(PunishmentType.BAN_PERM_WITH_ROLLBACK, "永久封禁+数据回滚", -1);

            case X_RAY:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "14d", 14 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case AUTO_MINER:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            default:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);
        }
    }

    private PunishmentResult calculateHighPunishment(ViolationType type, int violationCount) {
        switch (type) {
            case TIMER:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.KICK, "Timer作弊", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case WATER_WALK:
            case HIGH_JUMP:
            case NO_FALL:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case KILLAURA:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case CHEST_ESP:
            case PLAYER_RADAR:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case NO_SLOW_MINING:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "3d", 3 * 24 * 60);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "14d", 14 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            default:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "3d", 3 * 24 * 60);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);
        }
    }

    private PunishmentResult calculateMediumPunishment(ViolationType type, int violationCount) {
        switch (type) {
            case SPIDER:
            case SCAFFOLD:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.KICK, "脚手架/攀爬", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "3d", 3 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "14d", 14 * 24 * 60);

            case NO_KNOCKBACK:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.KICK, "无击退", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case AUTO_TOTEM:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            case TRACER:
            case FAST_BREAK:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "permanent", -1);

            default:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.KICK, "检测到作弊", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);
        }
    }

    private PunishmentResult calculateLowPunishment(ViolationType type, int violationCount) {
        switch (type) {
            case AUTO_HIT:
            case CPS_ANOMALY:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.WARN, "观察期", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "7d", 7 * 24 * 60);

            case AUTO_FISH:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.WARN, "30分钟封禁", 30);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
                return new PunishmentResult(PunishmentType.BAN, "3d", 3 * 24 * 60);

            default:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.WARN, "警告", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "1h", 60);
                return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);
        }
    }

    private PunishmentResult calculateMinorPunishment(ViolationType type, int violationCount) {
        switch (type) {
            case AUTO_STACK:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.NOTIFY, "提示信息", 0);
                if (violationCount == 2) return new PunishmentResult(PunishmentType.BAN, "1h", 60);
                return new PunishmentResult(PunishmentType.BAN, "1d", 24 * 60);

            default:
                if (violationCount == 1) return new PunishmentResult(PunishmentType.NOTIFY, "提示信息", 0);
                return new PunishmentResult(PunishmentType.BAN, "1h", 60);
        }
    }

    private void executePunishment(Player player, PunishmentResult punishment, ViolationType type) {
        switch (punishment.type) {
            case KICK:
                kickPlayer(player, punishment.description);
                break;
            case BAN:
                if (punishment.durationMinutes > 0) {
                    plugin.getBanManager().banPlayer(
                            player.getUniqueId(),
                            player.getName(),
                            punishment.durationMinutes + "m",
                            punishment.description
                    );
                } else if (punishment.durationMinutes == -1) {
                    plugin.getBanManager().banPlayer(
                            player.getUniqueId(),
                            player.getName(),
                            "permanent",
                            punishment.description
                    );
                }
                break;
            case BAN_PERM_WITH_ROLLBACK:
                plugin.getBanManager().banPlayer(
                        player.getUniqueId(),
                        player.getName(),
                        "permanent",
                        punishment.description + " - 数据回滚已记录"
                );
                break;
            case WARN:
                warnPlayer(player, punishment.description);
                break;
            case NOTIFY:
                notifyPlayer(player, punishment.description);
                break;
        }
    }

    private void kickPlayer(Player player, String reason) {
        long now = System.currentTimeMillis();
        Long lastKick = lastKickTime.get(player.getUniqueId());
        if (lastKick != null && now - lastKick < KICK_COOLDOWN) {
            return;
        }
        lastKickTime.put(player.getUniqueId(), now);

        new BukkitRunnable() {
            @Override
            public void run() {
                player.kickPlayer("§c§l[AdvancedAntiCheat]\n§f" + reason + "\n\n§7如有异议请联系管理员");
            }
        }.runTask(plugin);
    }

    private void warnPlayer(Player player, String reason) {
        long now = System.currentTimeMillis();
        Long lastWarn = lastWarnTime.get(player.getUniqueId());
        if (lastWarn != null && now - lastWarn < WARN_COOLDOWN) {
            return;
        }
        lastWarnTime.put(player.getUniqueId(), now);

        player.sendMessage("§e§l[!] §f" + reason);
        player.sendMessage("§e§l[!] §f这是警告，再次违规将导致更严重的处罚");
    }

    private void notifyPlayer(Player player, String message) {
        player.sendMessage("§6§l[!] §f" + message);
    }

    private void notifyAdmins(Player player, ViolationType type, PunishmentResult punishment) {
        String message = String.format("§c§l[AntiCheat] §f玩家 §e%s §f因 §c%s §f违规 (§e%s§f) 受到处罚: §c%s",
                player.getName(),
                type.getDisplayName(),
                type.getDefaultSeverity().getDisplayName(),
                punishment.description);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("anticheat.notify")) {
                admin.sendMessage(message);
            }
        }

        plugin.getLogger().info(message.replace("§c§l[AntiCheat] ", "").replace("§f", ""));
    }

    public int getViolationCount(UUID uuid, ViolationType type) {
        Map<ViolationType, ViolationCount> violations = playerViolations.get(uuid);
        if (violations == null) return 0;
        ViolationCount count = violations.get(type);
        return count != null ? count.getCount() : 0;
    }

    public Map<ViolationType, Integer> getAllViolations(UUID uuid) {
        Map<ViolationType, ViolationCount> violations = playerViolations.get(uuid);
        if (violations == null) return Collections.emptyMap();

        Map<ViolationType, Integer> result = new HashMap<>();
        for (Map.Entry<ViolationType, ViolationCount> entry : violations.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getCount());
        }
        return result;
    }

    public List<ViolationRecord> getViolationHistory(UUID uuid) {
        return violationHistory.getOrDefault(uuid, Collections.emptyList());
    }

    public void clearViolations(UUID uuid) {
        playerViolations.remove(uuid);
        violationHistory.remove(uuid);
        saveViolationData();
    }

    public void clearViolationType(UUID uuid, ViolationType type) {
        Map<ViolationType, ViolationCount> violations = playerViolations.get(uuid);
        if (violations != null) {
            violations.remove(type);
        }
        saveViolationData();
    }

    private void saveViolationData() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(violationDataFile))) {
                    Map<UUID, Map<ViolationType, Integer>> saveData = new HashMap<>();
                    for (Map.Entry<UUID, Map<ViolationType, ViolationCount>> entry : playerViolations.entrySet()) {
                        Map<ViolationType, Integer> typeCounts = new HashMap<>();
                        for (Map.Entry<ViolationType, ViolationCount> typeEntry : entry.getValue().entrySet()) {
                            typeCounts.put(typeEntry.getKey(), typeEntry.getValue().getCount());
                        }
                        saveData.put(entry.getKey(), typeCounts);
                    }
                    oos.writeObject(saveData);
                } catch (IOException e) {
                    plugin.getLogger().severe("保存违规数据失败: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void loadViolationData() {
        if (!violationDataFile.exists() || violationDataFile.length() == 0) {
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(violationDataFile))) {
            @SuppressWarnings("unchecked")
            Map<UUID, Map<ViolationType, Integer>> loadedData = (Map<UUID, Map<ViolationType, Integer>>) ois.readObject();

            for (Map.Entry<UUID, Map<ViolationType, Integer>> entry : loadedData.entrySet()) {
                Map<ViolationType, ViolationCount> typeCounts = new ConcurrentHashMap<>();
                for (Map.Entry<ViolationType, Integer> typeEntry : entry.getValue().entrySet()) {
                    typeCounts.put(typeEntry.getKey(), new ViolationCount(typeEntry.getValue()));
                }
                playerViolations.put(entry.getKey(), typeCounts);
            }

            plugin.getLogger().info("已加载 " + playerViolations.size() + " 个玩家的违规记录");
        } catch (Exception e) {
            plugin.getLogger().warning("加载违规数据失败: " + e.getMessage());
        }
    }

    public enum PunishmentType {
        KICK,
        BAN,
        BAN_PERM_WITH_ROLLBACK,
        WARN,
        NOTIFY
    }

    public static class PunishmentResult {
        public final PunishmentType type;
        public final String description;
        public final int durationMinutes;

        public PunishmentResult(PunishmentType type, String description, int durationMinutes) {
            this.type = type;
            this.description = description;
            this.durationMinutes = durationMinutes;
        }
    }

    public static class ViolationCount implements Serializable {
        private static final long serialVersionUID = 1L;
        private int count;

        public ViolationCount() {
            this.count = 0;
        }

        public ViolationCount(int count) {
            this.count = count;
        }

        public void increment() {
            count++;
        }

        public int getCount() {
            return count;
        }

        public void reset() {
            count = 0;
        }
    }
}
