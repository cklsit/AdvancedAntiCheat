package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BountySession {
    private final AdvancedAntiCheat plugin;
    private final Player player;
    private final UUID uuid;
    private final Location originalLocation;
    private final long startTime;
    private long timeLimitMinutes;
    private long timeSpentSeconds;
    private boolean inTask;
    private BountyTaskType currentTask;
    private long taskStartTime;
    private List<String> logs;
    private BukkitRunnable timerTask;

    public BountySession(AdvancedAntiCheat plugin, Player player, long timeLimitMinutes) {
        this.plugin = plugin;
        this.player = player;
        this.uuid = player.getUniqueId();
        this.originalLocation = player.getLocation().clone();
        this.startTime = Instant.now().getEpochSecond();
        this.timeLimitMinutes = timeLimitMinutes;
        this.timeSpentSeconds = 0;
        this.inTask = false;
        this.currentTask = null;
        this.taskStartTime = 0;
        this.logs = new ArrayList<>();
    }

    public void start() {
        plugin.getBountyManager().getBountyWorld().preparePlayer(player);
        player.teleport(plugin.getBountyManager().getBountyWorld().getSpawnLocation());

        player.sendMessage("§e================================================");
        player.sendMessage("§c§l欢迎来到漏洞赏金沙箱");
        player.sendMessage("§e================================================");
        player.sendMessage("§6你已被授权在此区域使用任何第三方工具");
        player.sendMessage("§6你的所有行为将被完整记录");
        player.sendMessage("§e================================================");
        player.sendMessage("§a使用 /bounty leave 退出沙箱");
        player.sendMessage("§a使用 /bounty report <描述> 主动报告发现");

        startTimer();
        log("[SESSION] Session started for player " + player.getName());
    }

    private void startTimer() {
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                timeSpentSeconds++;
                if (timeSpentSeconds >= timeLimitMinutes * 60) {
                    player.sendMessage("§c你的沙箱时长已用完，正在退出...");
                    plugin.getBountyManager().leaveBounty(player);
                    cancel();
                } else if (timeSpentSeconds % 300 == 0) { // 每5分钟提醒
                    long remaining = (timeLimitMinutes * 60 - timeSpentSeconds) / 60;
                    player.sendMessage("§e沙箱剩余时长：" + remaining + " 分钟");
                }
            }
        };
        timerTask.runTaskTimer(plugin, 0, 20);
    }

    public void startTask(BountyTaskType taskType) {
        this.currentTask = taskType;
        this.taskStartTime = Instant.now().getEpochSecond();
        this.inTask = true;

        player.sendMessage("§a开始任务：" + taskType.getDisplayName());
        player.sendMessage("§e" + taskType.getDescription());

        log("[TASK] Started task: " + taskType.name());
    }

    public void completeTask(BountyResult result) {
        if (!inTask || currentTask == null) return;

        long taskDuration = Instant.now().getEpochSecond() - taskStartTime;
        log("[TASK] Task completed: " + currentTask.name() + " with result: " + result.name() + " in " + taskDuration + "s");

        switch (result) {
            case DETECTED:
                player.sendMessage("§e你的操作已被检测！感谢参与。");
                break;
            case BYPASSED:
                player.sendMessage("§a恭喜！你发现了一个潜在绕过！");
                BukkitRunnable broadcast = new BukkitRunnable() {
                    @Override
                    public void run() {
                        Bukkit.broadcastMessage("§c§l[漏洞赏金] §a玩家 " + player.getName() + " 在 " + currentTask.getDisplayName() + " 中实现了潜在绕过！");
                    }
                };
                broadcast.runTask(plugin);
                break;
            case ZERO_DAY:
                player.sendMessage("§c§l高危发现！感谢你的贡献！");
                BukkitRunnable broadcastZero = new BukkitRunnable() {
                    @Override
                    public void run() {
                        Bukkit.broadcastMessage("§c§l[漏洞赏金] §e玩家 " + player.getName() + " 发现了一个高危绕过！该漏洞将被紧急修复。");
                    }
                };
                broadcastZero.runTask(plugin);
                break;
        }

        plugin.getBountyManager().saveEvidence(this, result);
        endTask();
    }

    public void endTask() {
        this.inTask = false;
        this.currentTask = null;
        this.taskStartTime = 0;
    }

    public void end() {
        if (timerTask != null) {
            timerTask.cancel();
        }

        plugin.getBountyManager().getBountyWorld().resetPlayerState(player);

        log("[SESSION] Session ended for player " + player.getName());
    }

    public void log(String message) {
        String timestamp = Instant.now().toString();
        logs.add("[" + timestamp + "] " + message);
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Location getOriginalLocation() {
        return originalLocation;
    }

    public boolean isInTask() {
        return inTask;
    }

    public BountyTaskType getCurrentTask() {
        return currentTask;
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }

    public long getTimeSpentSeconds() {
        return timeSpentSeconds;
    }
}
