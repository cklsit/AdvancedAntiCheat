package com.anticheat.captcha;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.tasks.CaptchaTask;
import com.anticheat.captcha.tasks.TypeA_DirectInteraction;
import com.anticheat.captcha.tasks.TypeB_PrecisionHit;
import com.anticheat.captcha.tasks.TypeC_SequenceReplay;
import com.anticheat.captcha.tasks.TypeD_BlockMaze;
import com.anticheat.captcha.tasks.TypeE_Puzzle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

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
    private final CaptchaWorld captchaWorld;
    private final Random random;

    private boolean newPlayerCaptchaEnabled;
    private int timeLimit;

    public CaptchaManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.activeSessions = new ConcurrentHashMap<>();
        this.captchaWorld = new CaptchaWorld(plugin);
        this.random = new Random();
        loadConfig();
    }

    private void loadConfig() {
        newPlayerCaptchaEnabled = plugin.getConfig().getBoolean("captcha.new-player-enabled", false);
        timeLimit = plugin.getConfig().getInt("captcha.time-limit", 45);
    }

    public void startCaptcha(Player player) {
        startCaptcha(player, Initiator.AUTO_DETECTION);
    }

    public void startCaptcha(Player player, Initiator initiator) {
        UUID uuid = player.getUniqueId();

        if (activeSessions.containsKey(uuid)) {
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

        session.start();
    }

    private List<CaptchaTask> generateTasks() {
        List<CaptchaTask> tasks = new ArrayList<>();
        int taskCount = random.nextInt(2) + 1;

        List<Class<? extends CaptchaTask>> taskTypes = new ArrayList<>(Arrays.asList(
                TypeA_DirectInteraction.class,
                TypeB_PrecisionHit.class,
                TypeC_SequenceReplay.class,
                TypeD_BlockMaze.class,
                TypeE_Puzzle.class
        ));

        Collections.shuffle(taskTypes);

        for (int i = 0; i < taskCount && i < taskTypes.size(); i++) {
            try {
                CaptchaTask task = taskTypes.get(i).getConstructor(AdvancedAntiCheat.class).newInstance(plugin);
                tasks.add(task);
            } catch (Exception e) {
                plugin.getLogger().severe("创建验证码任务失败: " + e.getMessage());
            }
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
        activeSessions.remove(uuid);
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
        private BukkitRunnable timerTask;
        private BukkitRunnable warningTask;
        private CaptchaTask currentTask;

        public CaptchaSession(AdvancedAntiCheat plugin, Player player, Location originalLocation,
                             Location captchaLocation, List<CaptchaTask> tasks, int timeLimit,
                             Initiator initiator, Map<UUID, CaptchaSession> activeSessions) {
            this.plugin = plugin;
            this.player = player;
            this.originalLocation = originalLocation;
            this.captchaLocation = captchaLocation;
            this.tasks = tasks;
            this.timeLimit = timeLimit;
            this.initiator = initiator;
            this.activeSessions = activeSessions;
            this.currentTaskIndex = 0;
            this.completed = false;
            this.failed = false;
            this.currentTask = null;
        }

        public void start() {
            player.teleport(captchaLocation);

            player.sendMessage("§c§l[!] §f由于你的行为触犯了反作弊系统，正在进行验证");

            startTimer();
            startCurrentTask();
        }

        private void startTimer() {
            startTime = System.currentTimeMillis();

            final long startTimeFinal = startTime;
            final int timeLimitFinal = timeLimit;

            timerTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (completed || failed) {
                        this.cancel();
                        return;
                    }

                    long elapsed = (System.currentTimeMillis() - startTimeFinal) / 1000;
                    long remaining = timeLimitFinal - elapsed;

                    if (remaining <= 0) {
                        fail();
                        return;
                    }

                    float progress = (float) remaining / timeLimitFinal;
                    player.setExp(progress);
                    player.setLevel((int) remaining);

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
                    if (completed || failed) {
                        this.cancel();
                        return;
                    }
                    player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
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
            if (completed || failed) return;

            if (currentTask != null) {
                currentTask.cleanup(player);
            }

            currentTaskIndex++;

            if (currentTaskIndex >= tasks.size()) {
                complete();
            } else {
                startCurrentTask();
            }
        }

        private void complete() {
            completed = true;

            if (timerTask != null) timerTask.cancel();
            if (warningTask != null) warningTask.cancel();

            cleanupTasks();

            player.teleport(originalLocation);
            player.sendMessage("§a§l[!] §f验证完毕");
            player.setExp(0);
            player.setLevel(0);

            activeSessions.remove(player.getUniqueId());
        }

        public void fail() {
            failed = true;

            if (timerTask != null) timerTask.cancel();
            if (warningTask != null) warningTask.cancel();

            cleanupTasks();

            player.setExp(0);
            player.setLevel(0);

            activeSessions.remove(player.getUniqueId());

            if (initiator != Initiator.NEW_PLAYER) {
                plugin.getBanManager().banPlayer(
                        player.getUniqueId(),
                        player.getName(),
                        "1d",
                        "验证码验证失败"
                );
            } else {
                player.kickPlayer("§c验证码验证失败，请重新加入服务器");
            }
        }

        private void cleanupTasks() {
            for (CaptchaTask task : tasks) {
                try {
                    task.cleanup(player);
                } catch (Exception e) {
                    plugin.getLogger().severe("清理验证码任务失败: " + e.getMessage());
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
