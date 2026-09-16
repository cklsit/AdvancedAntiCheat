package com.anticheat.captcha;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.tasks.CaptchaTask;
import com.anticheat.captcha.tasks.TypeA_DirectInteraction;
import com.anticheat.captcha.tasks.TypeB_MotionMimicry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CaptchaManager {

    public enum Initiator {
        ADMIN,
        NEW_PLAYER,
        AUTO_DETECTION
    }

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, CaptchaSession> activeSessions;

    /** 查端期间被清空的玩家数据快照；结束时还原，未结束时（退出/重启）落盘等下次登录还原。 */
    private final Map<UUID, CaptchaInventoryBackup> inventoryBackups;
    private final File backupFile;

    private final CaptchaWorld captchaWorld;
    private final Random random;

    private boolean newPlayerCaptchaEnabled;
    private int timeLimit;

    public CaptchaManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.activeSessions = new ConcurrentHashMap<>();
        this.inventoryBackups = new ConcurrentHashMap<>();
        this.backupFile = new File(plugin.getDataFolder(), "captcha-backups.yml");
        this.captchaWorld = new CaptchaWorld(plugin);
        this.random = new Random();
        loadConfig();
        loadInventoryBackups();
    }

    private void loadConfig() {
        newPlayerCaptchaEnabled = plugin.getConfig().getBoolean("captcha.new-player-enabled", false);
        timeLimit = plugin.getConfig().getInt("captcha.time-limit", 45);
    }

    public void startCaptcha(Player player) {
        startCaptcha(player, Initiator.AUTO_DETECTION);
    }

    /**
     * 启动验证码。具备前置互斥与启动失败兜底：
     * <ul>
     *   <li>已有 captcha session 不重复启动</li>
     *   <li>玩家不在线不启动</li>
     *   <li>玩家正在被 CheckClientManager 查端（人工）时不启动，避免两个"传送+限制+计时"状态机冲突（互相 cancel teleport / 互相 cancel 事件）</li>
     * </ul>
     */
    public void startCaptcha(Player player, Initiator initiator) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        if (activeSessions.containsKey(uuid)) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        // 互斥：查端系统 (startCheck) 会 cancel teleport、冻结玩家。两个并行会导致"验证码 teleport 被取消→session遗留→超时踢人"
        if (plugin.getCheckClientManager() != null && plugin.getCheckClientManager().isBeingChecked(uuid)) {
            player.sendMessage("§c你正在进行人工查端，无法同时进行验证码验证。");
            return;
        }
        // 若玩家已被封禁：由 PlayerJoinListener 踢人，不启动 captcha
        if (plugin.getBanManager() != null && plugin.getBanManager().isBanned(uuid)) {
            return;
        }

        Location originalLocation = player.getLocation().clone();
        Location captchaLocation = captchaWorld.getNextLocation();
        List<CaptchaTask> tasks = generateTasks();

        CaptchaSession session = new CaptchaSession(
                plugin,
                player,
                originalLocation,
                captchaLocation,
                tasks,
                timeLimit,
                initiator,
                activeSessions
        );

        activeSessions.put(uuid, session);

        try {
            session.start();
        } catch (Throwable t) {
            // 极端保护：start 抛异常时确保 activeSessions 不残留，避免下次无法启动 / 超时踢人
            plugin.getLogger().warning("[CaptchaManager] 启动验证码失败，清理 session: " + t.getMessage());
            activeSessions.remove(uuid);
            // 启动失败必须立即还包，否则玩家背包会一直空着
            restoreInventory(uuid);
        }
    }

    // ================================================================
    // 查端背包保护（快照 / 还原 / 落盘）
    // ================================================================

    /**
     * 备份并清空玩家背包、盔甲、末影箱。
     *
     * <p>清空是为了防止玩家把物品带进验证码世界用来脱离平台（平台只有基岩 + 屏障）。
     * <b>备份失败时不清空</b>——宁可让玩家带着物品做验证码，也不能冒物品丢失的风险。
     */
    public void snapshotAndClearInventory(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        // 已有未还原的快照：说明上一次快照还没还回去，绝不能再用"已被清空的背包"覆盖它
        if (inventoryBackups.containsKey(uuid)) {
            plugin.getLogger().warning("[Captcha] 玩家 " + player.getName()
                    + " 已存在未还原的背包快照，跳过重复备份（避免用空背包覆盖）。");
        } else {
            try {
                CaptchaInventoryBackup backup = CaptchaInventoryBackup.capture(player);
                inventoryBackups.put(uuid, backup);
                saveInventoryBackups();
                plugin.getLogger().info("[Captcha] 已备份玩家 " + player.getName()
                        + " 的背包（" + backup.summary() + "）");
            } catch (Throwable t) {
                plugin.getLogger().severe("[Captcha] 备份玩家 " + player.getName()
                        + " 背包失败，取消清空操作以保护物品: " + t.getMessage());
                return;
            }
        }

        try {
            player.getInventory().clear();
            player.getEnderChest().clear();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Captcha] 清空玩家 " + player.getName() + " 背包失败: " + t.getMessage());
        }
    }

    /**
     * 还原玩家数据。玩家不在线时**保留快照**，由下次登录（{@link #onPlayerJoin}）还原；
     * 还原失败同样保留快照，避免物品直接蒸发。
     */
    public void restoreInventory(UUID uuid) {
        if (uuid == null) return;
        CaptchaInventoryBackup backup = inventoryBackups.get(uuid);
        if (backup == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return; // 保留快照，登录时还原
        }

        try {
            backup.restore(player);
        } catch (Throwable t) {
            plugin.getLogger().severe("[Captcha] 还原玩家 " + player.getName()
                    + " 背包失败（快照已保留，下次登录重试）: " + t.getMessage());
            return;
        }

        inventoryBackups.remove(uuid);
        saveInventoryBackups();
        plugin.getLogger().info("[Captcha] 已还原玩家 " + player.getName() + " 的背包（" + backup.summary() + "）");
    }

    /** 玩家登录时补还背包（覆盖：查端中退出、查端中服务器重启、还原失败等场景）。 */
    public void onPlayerJoin(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (!inventoryBackups.containsKey(uuid)) return;

        plugin.getLogger().warning("[Captcha] 检测到玩家 " + player.getName()
                + " 有未还原的查端背包快照，正在补还。");
        restoreInventory(uuid);
    }

    public boolean hasPendingBackup(UUID uuid) {
        return uuid != null && inventoryBackups.containsKey(uuid);
    }

    private void loadInventoryBackups() {
        if (!backupFile.exists() || backupFile.length() == 0) {
            return;
        }
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(backupFile);
            ConfigurationSection section = cfg.getConfigurationSection("pending");
            if (section == null) {
                return;
            }
            int loaded = 0;
            for (String key : section.getKeys(false)) {
                String raw = section.getString(key);
                if (raw == null || raw.isEmpty()) continue;
                try {
                    UUID uuid = UUID.fromString(key);
                    inventoryBackups.put(uuid, CaptchaInventoryBackup.deserialize(raw));
                    loaded++;
                } catch (Throwable e) {
                    plugin.getLogger().warning("[Captcha] 解析快照失败（" + key + "）: " + e.getMessage());
                }
            }
            if (loaded > 0) {
                plugin.getLogger().warning("[Captcha] 已加载 " + loaded
                        + " 份未还原的查端背包快照，将在玩家登录时自动补还。");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Captcha] 加载查端背包快照失败: " + t.getMessage());
        }
    }

    private void saveInventoryBackups() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, CaptchaInventoryBackup> e : inventoryBackups.entrySet()) {
                try {
                    cfg.set("pending." + e.getKey().toString(), e.getValue().serialize());
                } catch (Throwable t) {
                    plugin.getLogger().warning("[Captcha] 序列化快照失败（" + e.getKey() + "）: " + t.getMessage());
                }
            }
            File parent = backupFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            cfg.save(backupFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Captcha] 保存查端背包快照失败: " + t.getMessage());
        }
    }

    /**
     * 从已启用的检测项里随机抽 1 项作为本次验证码任务。
     *
     * <p>检测项池由 {@code captcha.tasks.*.enabled} 控制：
     * <ul>
     *   <li>{@code direct-interaction}：定向交互（注视指定颜色的羊后潜行）</li>
     *   <li>{@code motion-mimicry}：动作模仿（盔甲架演示随机动作序列，玩家重复，DTW 判定）</li>
     * </ul>
     * 池为空时回退到定向交互 —— 验证码必须能启动，否则玩家会被超时逻辑白白踢掉/封禁。
     */
    private List<CaptchaTask> generateTasks() {
        List<Class<? extends CaptchaTask>> pool = new ArrayList<>();
        if (plugin.getConfig().getBoolean("captcha.tasks.direct-interaction.enabled", true)) {
            pool.add(TypeA_DirectInteraction.class);
        }
        if (plugin.getConfig().getBoolean("captcha.tasks.motion-mimicry.enabled", true)) {
            pool.add(TypeB_MotionMimicry.class);
        }
        if (pool.isEmpty()) {
            pool.add(TypeA_DirectInteraction.class);
        }

        List<CaptchaTask> tasks = new ArrayList<>();
        Class<? extends CaptchaTask> type = pool.get(random.nextInt(pool.size()));
        try {
            tasks.add(type.getConstructor(AdvancedAntiCheat.class).newInstance(plugin));
        } catch (Exception e) {
            plugin.getLogger().severe("创建验证码任务失败: " + e.getMessage());
        }

        return tasks;
    }

    public void completeTask(Player player) {
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.completeCurrentTask();
        }
    }

    public void failCaptcha(Player player) {
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.fail();
        }
    }

    public boolean isInCaptcha(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public void removeSession(UUID uuid) {
        CaptchaSession session = activeSessions.get(uuid);
        if (session != null) {
            // 走正规终止流程：停定时器 / 还背包 / 重置世界。
            // 原实现直接 map.remove 会留下野定时器——到达时限后会 fail() 把玩家踢掉或封禁。
            session.abortSilent();
            return;
        }

        activeSessions.remove(uuid);
        if (activeSessions.isEmpty()) {
            captchaWorld.resetWorld();
        }
    }

    public void onPlayerQuit(Player player) {
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.fail();
        }
    }

    public boolean isNewPlayerCaptchaEnabled() {
        return newPlayerCaptchaEnabled;
    }

    public void setNewPlayerCaptchaEnabled(boolean enabled) {
        newPlayerCaptchaEnabled = enabled;
        plugin.getConfig().set("captcha.new-player-enabled", enabled);
        plugin.saveConfig();
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(int seconds) {
        timeLimit = seconds;
        plugin.getConfig().set("captcha.time-limit", seconds);
        plugin.saveConfig();
    }

    public CaptchaWorld getCaptchaWorld() {
        return captchaWorld;
    }

    public CaptchaSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public static class CaptchaSession {

        private final AdvancedAntiCheat plugin;
        private final Player player;
        private final UUID playerUuid;
        private final Location originalLocation;
        private final Location captchaLocation;
        private final List<CaptchaTask> tasks;
        private final int timeLimit;
        private final Initiator initiator;
        private final Map<UUID, CaptchaSession> activeSessions;

        private int currentTaskIndex;
        private long startTime;
        private boolean completed;
        private boolean failed;
        /** 被守卫机制/外部打断：不惩罚也不传送，只是安静清理。 */
        private boolean cancelled;
        private BukkitRunnable timerTask;
        private BukkitRunnable warningTask;
        private CaptchaTask currentTask;

        public CaptchaSession(AdvancedAntiCheat plugin, Player player, Location originalLocation,
                             Location captchaLocation, List<CaptchaTask> tasks, int timeLimit,
                             Initiator initiator, Map<UUID, CaptchaSession> activeSessions) {
            this.plugin = plugin;
            this.player = player;
            this.playerUuid = player.getUniqueId();
            this.originalLocation = originalLocation;
            this.captchaLocation = captchaLocation;
            this.tasks = tasks;
            this.timeLimit = timeLimit;
            this.initiator = initiator;
            this.activeSessions = activeSessions;
            this.currentTaskIndex = 0;
            this.completed = false;
            this.failed = false;
            this.cancelled = false;
            this.currentTask = null;
        }

        public void start() {
            // 备份 + 清空背包（成对操作，见 CaptchaManager.snapshotAndClearInventory）。
            // 必须在 preparePlayer / teleport 之前完成，保证清空前一定已有快照。
            plugin.getCaptchaManager().snapshotAndClearInventory(player);

            plugin.getCaptchaManager().getCaptchaWorld().preparePlayer(player);

            player.teleport(captchaLocation);

            // 启动消息按 initiator 区分：NEW_PLAYER 不提示"作弊行为"
            if (initiator == Initiator.NEW_PLAYER) {
                player.sendMessage("§e[!] §f新玩家验证：请在 " + timeLimit + " 秒内完成下方任务");
            } else {
                player.sendMessage("§c§l[!] §f由于你的行为触犯了反作弊系统，正在进行验证");
            }

            startTimer();
            startCurrentTask();

            // ============================================================
            // Teleport 成功守卫（核心修复：修复"瞬间传回来 session 残留→超时踢人"）
            //
            // 背景：其它插件（Essentials、AuthMe、多世界默认 spawn 传送、查端 cancel teleport）
            //       可能在 Join 阶段 / start() 同步调用后，把玩家从验证码世界抢回主世界。
            //       session 的 timerTask 不知道这件事，会正常计时然后 fail() 踢人。
            // 机制：2 tick 后检查玩家实际位置是否仍在验证码区域（同世界且距离 < 16 格）。
            //       如果不在，安静 cancel（不 ban / 不 kick / 不再 teleport，只清状态）。
            // ============================================================
            final UUID capturedUuid = playerUuid;
            final Location capturedCaptchaLoc = captchaLocation;
            new BukkitRunnable() {
                @Override
                public void run() {
                    // 已经自然结束的（complete/fail）不再介入
                    if (completed || failed || cancelled) return;
                    Player current = Bukkit.getPlayer(capturedUuid);
                    if (current == null || !current.isOnline()) {
                        // 玩家已下线：onPlayerQuit 会处理 fail，这里不重复
                        return;
                    }
                    Location now = current.getLocation();
                    boolean sameWorld = now.getWorld() != null
                            && capturedCaptchaLoc.getWorld() != null
                            && now.getWorld().getName().equals(capturedCaptchaLoc.getWorld().getName());
                    boolean nearCaptchaLocation = sameWorld && now.distance(capturedCaptchaLoc) < 16.0;
                    if (!nearCaptchaLocation) {
                        plugin.getLogger().info("[Captcha] 玩家 " + current.getName()
                                + " 被外部插件从验证码区域传送回主世界，安静取消验证码 (initiator=" + initiator + ")");
                        abortSilent();
                    }
                }
            }.runTaskLater(plugin, 2L);
        }

        private void startTimer() {
            startTime = System.currentTimeMillis();

            final long startTimeFinal = startTime;
            final int timeLimitFinal = timeLimit;

            timerTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (completed || failed || cancelled) {
                        this.cancel();
                        return;
                    }

                    long elapsed = (System.currentTimeMillis() - startTimeFinal) / 1000;
                    long remaining = timeLimitFinal - elapsed;

                    if (remaining <= 0) {
                        fail();
                        return;
                    }

                    // 玩家如果此刻不在线，保留任务等 onPlayerQuit fail，这里不额外处理以免干扰
                    try {
                        float progress = (float) remaining / timeLimitFinal;
                        player.setExp(progress);
                        player.setLevel((int) remaining);
                    } catch (Throwable ignored) {
                        // 玩家可能瞬间下线，ignore
                    }

                    if (remaining == 10) {
                        startWarning();
                    }
                }
            };

            timerTask.runTaskTimer(plugin, 0, 20);
        }

        private void startWarning() {
            warningTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (completed || failed || cancelled) {
                        this.cancel();
                        return;
                    }
                    try {
                        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                    } catch (Throwable ignored) {
                    }
                }
            };

            warningTask.runTaskTimer(plugin, 0, 40);
        }

        private void startCurrentTask() {
            if (currentTaskIndex >= tasks.size()) {
                complete();
                return;
            }

            currentTask = tasks.get(currentTaskIndex);
            currentTask.start(player, captchaLocation);
            player.sendMessage("§e[任务 " + (currentTaskIndex + 1) + "/" + tasks.size() + "] " + currentTask.getTaskDescription());
        }

        public void completeCurrentTask() {
            if (completed || failed || cancelled) return;

            if (currentTask != null) {
                try {
                    currentTask.cleanup(player);
                } catch (Throwable ignored) {
                }
            }

            currentTaskIndex++;

            if (currentTaskIndex >= tasks.size()) {
                complete();
            } else {
                startCurrentTask();
            }
        }

        /**
         * 清理 Session 资源（timer / warning / tasks / world / exp / activeSessions）。
         * 由 complete() / fail() / abortSilent() 共用，避免三处重复代码导致 "恢复不完整，状态残留"。
         *
         * @param teleportBack 是否把玩家传 originalLocation（complete 成功时 true；abort 被外部传回主世界 false；fail 惩罚分支 false）
         */
        private void cleanup(boolean teleportBack) {
            if (timerTask != null) try { timerTask.cancel(); } catch (Throwable ignored) { timerTask = null; }
            if (warningTask != null) try { warningTask.cancel(); } catch (Throwable ignored) { warningTask = null; }

            cleanupTasks();
            try {
                plugin.getCaptchaManager().getCaptchaWorld().cleanup(captchaLocation);
            } catch (Throwable ignored) {
            }

            // 恢复玩家状态（EXP / Level / WalkSpeed），只针对在线玩家，避免 NPE
            Player current = Bukkit.getPlayer(playerUuid);
            if (current != null && current.isOnline()) {
                try {
                    current.setExp(0);
                    current.setLevel(0);
                    current.setWalkSpeed(0.2f);
                    current.setFlySpeed(0.2f);
                } catch (Throwable ignored) {
                }
                if (teleportBack) {
                    try {
                        if (!current.isDead()) {
                            current.teleport(originalLocation);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 还背包：complete / fail / abort 三条路径都必须还，否则就是"完成查端后背包全空"的老 bug。
            // 玩家离线时快照会保留，由下次登录补还。
            try {
                plugin.getCaptchaManager().restoreInventory(playerUuid);
            } catch (Throwable t) {
                plugin.getLogger().warning("[Captcha] 还原背包异常（快照保留）: " + t.getMessage());
            }

            activeSessions.remove(playerUuid);

            // 全部 session 结束重置世界（保持与 CaptchaManager.removeSession 一致的世界回收）
            if (activeSessions.isEmpty()) {
                try {
                    plugin.getCaptchaManager().getCaptchaWorld().resetWorld();
                } catch (Throwable ignored) {
                }
            }
        }

        private void complete() {
            if (completed || failed || cancelled) return;
            completed = true;
            cleanup(true);
            Player current = Bukkit.getPlayer(playerUuid);
            if (current != null && current.isOnline()) {
                current.sendMessage("§a§l[!] §f验证完毕");
            }
            // AI 实验室弱标签：自动检测触发的 Captcha 通过 → 负样本（疑似误报）
            if (initiator == Initiator.AUTO_DETECTION) {
                try {
                    com.anticheat.ai.AILabManager aiLab = plugin.getAILabManager();
                    if (aiLab != null) {
                        aiLab.reportFeedback(playerUuid, com.anticheat.ai.feedback.LabeledSample.SRC_CAPTCHA_PASS,
                                false, "behavior");
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        public void fail() {
            if (completed || failed || cancelled) return;
            failed = true;
            // fail 分支惩罚在 cleanup 之后执行：确保状态清完再 ban/kick，避免被 teleport 到验证码残留世界
            cleanup(false);
            // AI 实验室弱标签：Captcha 审判失败 → 高置信正样本（作弊）
            if (initiator != Initiator.NEW_PLAYER) {
                try {
                    com.anticheat.ai.AILabManager aiLab = plugin.getAILabManager();
                    if (aiLab != null) {
                        aiLab.reportFeedback(playerUuid, com.anticheat.ai.feedback.LabeledSample.SRC_CAPTCHA_FAIL,
                                true, "combat");
                    }
                } catch (Throwable ignored) {
                }
            }
            Player current = Bukkit.getPlayer(playerUuid);
            if (current == null || !current.isOnline()) return;

            if (initiator != Initiator.NEW_PLAYER) {
                plugin.getBanManager().banPlayer(
                        current.getUniqueId(),
                        current.getName(),
                        "1d",
                        "验证码验证失败"
                );
            } else {
                current.kickPlayer("§c验证码验证失败，请重新加入服务器");
            }
        }

        /**
         * 被外部打断（其它插件 teleport、玩家在 Join 阶段被 spawn 传送抢回主世界、守卫检测到不在验证码区、
         * 管理员强制重开验证码）。
         * 不惩罚、不 kick，仅安静清理（含还背包），避免误踢。
         */
        public void abortSilent() {
            if (completed || failed || cancelled) return;
            cancelled = true;
            // 玩家已经被传出去了，不要再 teleportBack
            cleanup(false);
        }

        private void cleanupTasks() {
            Player current = Bukkit.getPlayer(playerUuid);
            boolean online = current != null && current.isOnline();
            for (CaptchaTask task : tasks) {
                try {
                    if (online) {
                        task.cleanup(current);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        public CaptchaTask getCurrentTask() {
            return currentTask;
        }

        public int getCurrentTaskIndex() {
            return currentTaskIndex;
        }

        public int getTotalTasks() {
            return tasks.size();
        }

        public long getRemainingTime() {
            if (completed || failed) return 0;
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            return Math.max(0, timeLimit - elapsed);
        }

        public Initiator getInitiator() {
            return initiator;
        }

        public Player getPlayer() {
            return player;
        }
    }
}
