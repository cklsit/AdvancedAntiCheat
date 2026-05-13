package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BountyManager {
    private final AdvancedAntiCheat plugin;
    private final BountyWorld bountyWorld;
    private final Map<UUID, BountySession> activeSessions;
    private final Map<UUID, Long> dailyTimeUsed;
    private final Map<String, Integer> leaderboard;
    private long defaultTimeLimitMinutes;
    private boolean enabled;

    public BountyManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.bountyWorld = new BountyWorld(plugin);
        this.activeSessions = new ConcurrentHashMap<>();
        this.dailyTimeUsed = new ConcurrentHashMap<>();
        this.leaderboard = new LinkedHashMap<>();
        loadConfig();
    }

    private void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("bounty.enabled", true);
        this.defaultTimeLimitMinutes = config.getLong("bounty.default-time-limit-minutes", 30);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enterBounty(Player player) {
        enterBounty(player, defaultTimeLimitMinutes);
    }

    public void enterBounty(Player player, long timeLimitMinutes) {
        if (!enabled) {
            player.sendMessage("§c漏洞赏金计划当前未启用");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (activeSessions.containsKey(uuid)) {
            player.sendMessage("§c你已经在漏洞赏金沙箱中了");
            return;
        }

        long usedToday = dailyTimeUsed.getOrDefault(uuid, 0L);
        if (usedToday + timeLimitMinutes > defaultTimeLimitMinutes && !player.hasPermission("anticheat.bounty.unlimited")) {
            player.sendMessage("§c你今天的沙箱时长已用完");
            return;
        }

        BountySession session = new BountySession(plugin, player, timeLimitMinutes);
        activeSessions.put(uuid, session);
        session.start();
    }

    public void leaveBounty(Player player) {
        UUID uuid = player.getUniqueId();
        BountySession session = activeSessions.remove(uuid);
        if (session != null) {
            session.end();
            long timeUsed = session.getTimeSpentSeconds() / 60;
            dailyTimeUsed.merge(uuid, timeUsed, Long::sum);
            player.sendMessage("§a已退出漏洞赏金沙箱");
        }
    }

    public void invitePlayer(Player inviter, Player target) {
        if (!inviter.hasPermission("anticheat.bounty.admin")) {
            inviter.sendMessage("§c你没有权限邀请玩家");
            return;
        }

        target.sendMessage("§a管理员" + inviter.getName() + "邀请你进入漏洞赏金沙箱！");
        target.sendMessage("§a使用 /bounty enter 加入");
        inviter.sendMessage("§a已向 " + target.getName() + " 发送邀请");
    }

    public void reportFinding(Player player, String description) {
        BountySession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c你不在漏洞赏金沙箱中");
            return;
        }

        session.log("[REPORT] " + description);
        saveReportToFile(player, description);
        player.sendMessage("§a你的报告已记录，感谢你的贡献！");
    }

    private void saveReportToFile(Player player, String description) {
        try {
            File reportsDir = new File(plugin.getDataFolder(), "reports");
            if (!reportsDir.exists()) {
                reportsDir.mkdirs();
            }

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File reportFile = new File(reportsDir, "report-" + date + ".txt");

            FileWriter writer = new FileWriter(reportFile, true);
            writer.write("[" + System.currentTimeMillis() + "] " + player.getName() + ": " + description + "\n");
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save report: " + e.getMessage());
        }
    }

    public void saveEvidence(BountySession session, BountyResult result) {
        try {
            File evidenceDir = new File(plugin.getDataFolder(), "evidence");
            if (!evidenceDir.exists()) {
                evidenceDir.mkdirs();
            }

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String timestamp = String.valueOf(System.currentTimeMillis());
            File evidenceFile = new File(evidenceDir, "evidence-" + date + "-" + timestamp + ".txt");

            FileWriter writer = new FileWriter(evidenceFile);
            writer.write("=== Bounty Evidence ===\n");
            writer.write("Player: " + session.getPlayer().getName() + "\n");
            writer.write("Task: " + (session.getCurrentTask() != null ? session.getCurrentTask().name() : "None") + "\n");
            writer.write("Result: " + result.name() + "\n");
            writer.write("====================\n");
            writer.write("Logs:\n");
            for (String log : session.getLogs()) {
                writer.write(log + "\n");
            }
            writer.close();

            if (result == BountyResult.BYPASSED || result == BountyResult.ZERO_DAY) {
                String playerName = session.getPlayer().getName();
                leaderboard.merge(playerName, 1, Integer::sum);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save evidence: " + e.getMessage());
        }
    }

    public void showLeaderboard(Player player) {
        player.sendMessage("§e=== 漏洞赏金排行榜 ===");
        int rank = 1;
        for (Map.Entry<String, Integer> entry : leaderboard.entrySet()) {
            player.sendMessage("§a" + rank + ". " + entry.getKey() + " - " + entry.getValue() + " 次发现");
            rank++;
            if (rank > 10) break;
        }
        player.sendMessage("§e======================");
    }

    public boolean isInBounty(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public BountySession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public BountyWorld getBountyWorld() {
        return bountyWorld;
    }

    public void onDisable() {
        for (BountySession session : new ArrayList<>(activeSessions.values())) {
            session.end();
        }
        activeSessions.clear();
    }
}
