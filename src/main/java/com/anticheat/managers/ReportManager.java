package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.compat.ChatCompat;
import com.anticheat.compat.CompatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReportManager {

    private final AdvancedAntiCheat plugin;
    private final List<Report> reports = new ArrayList<>();
    private final File reportsFile;
    private final ChatCompat chatCompat;

    public ReportManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.reportsFile = new File(plugin.getDataFolder(), "reports.dat");
        this.chatCompat = CompatManager.getChatCompat();
        loadReports();
    }

    public void addReport(Player reporter, Player target, String reason) {
        Report report = new Report(
                reporter.getUniqueId(),
                reporter.getName(),
                target.getUniqueId(),
                target.getName(),
                reason,
                System.currentTimeMillis()
        );
        reports.add(report);

        sendReportNotification(reporter.getName(), target.getName(), reason);

        saveReports();
    }

    private void sendReportNotification(String reporterName, String targetName, String reason) {
        StringBuilder message = new StringBuilder();
        message.append("§c[举报] §e").append(reporterName).append(" §6举报了 §e").append(targetName);
        message.append(" §7原因: §f").append(reason);

        String gotoReporterButton = "§a[前往举报者]";
        String gotoTargetButton = "§c[前往作弊者]";

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("anticheat.notify")) {
                player.sendMessage(message.toString());
                chatCompat.sendMessageWithButton(player, "  ", gotoReporterButton, "/goto " + reporterName);
                chatCompat.sendMessageWithButton(player, "  ", gotoTargetButton, "/goto " + targetName);
            }
        }
    }

    public List<Report> getReports() {
        return reports;
    }

    public void removeReport(int index) {
        if (index >= 0 && index < reports.size()) {
            reports.remove(index);
            saveReports();
        }
    }

    public void clearReports() {
        reports.clear();
        saveReports();
    }

    private void loadReports() {
        if (!reportsFile.exists()) {
            try {
                reportsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("无法创建举报文件: " + e.getMessage());
            }
            return;
        }

        if (reportsFile.length() == 0) {
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(reportsFile))) {
            @SuppressWarnings("unchecked")
            List<Report> loaded = (List<Report>) ois.readObject();
            reports.addAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().severe("加载举报文件失败: " + e.getMessage());
        }
    }

    public void saveReports() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(reportsFile))) {
            oos.writeObject(reports);
        } catch (Exception e) {
            plugin.getLogger().severe("保存举报文件失败: " + e.getMessage());
        }
    }

    public static class Report implements Serializable {
        private static final long serialVersionUID = 1L;
        private final UUID reporterUUID;
        private final String reporterName;
        private final UUID targetUUID;
        private final String targetName;
        private final String reason;
        private final long timestamp;

        public Report(UUID reporterUUID, String reporterName, UUID targetUUID, String targetName, String reason, long timestamp) {
            this.reporterUUID = reporterUUID;
            this.reporterName = reporterName;
            this.targetUUID = targetUUID;
            this.targetName = targetName;
            this.reason = reason;
            this.timestamp = timestamp;
        }

        public UUID getReporterUUID() {
            return reporterUUID;
        }

        public String getReporterName() {
            return reporterName;
        }

        public UUID getTargetUUID() {
            return targetUUID;
        }

        public String getTargetName() {
            return targetName;
        }

        public String getReason() {
            return reason;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
