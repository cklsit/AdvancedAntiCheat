package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.compat.ChatCompat;
import com.anticheat.compat.CompatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReportManager {

    private final AdvancedAntiCheat plugin;
    private final List<Report> reports = new ArrayList<>();
    private final File reportsFile;
    private final File reportFolder;
    private final ChatCompat chatCompat;

    public ReportManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.reportsFile = new File(plugin.getDataFolder(), "reports.dat");
        this.reportFolder = new File(plugin.getDataFolder(), "report");
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

        archiveReportToZip(report, reporter, target);

        saveReports();
    }

    /**
     * 将单条举报记录打包为独立 zip 存档到插件目录的 report 文件夹。
     * 文件名包含时间与举报双方玩家名，zip 内详情包含基础信息与位置信息。
     */
    private void archiveReportToZip(Report report, Player reporter, Player target) {
        try {
            if (!reportFolder.exists() && !reportFolder.mkdirs()) {
                plugin.getLogger().warning("无法创建举报存档文件夹: " + reportFolder.getAbsolutePath());
                return;
            }

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(report.getTimestamp()));
            String safeReporter = sanitizeFileName(report.getReporterName());
            String safeTarget = sanitizeFileName(report.getTargetName());
            String baseName = "report_" + timeStamp + "_" + safeReporter + "_vs_" + safeTarget;

            // 同秒重复举报时追加序号，避免覆盖已有存档
            File zipFile = new File(reportFolder, baseName + ".zip");
            int duplicate = 2;
            while (zipFile.exists()) {
                zipFile = new File(reportFolder, baseName + "-" + duplicate + ".zip");
                duplicate++;
            }

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                zos.putNextEntry(new ZipEntry(baseName + ".txt"));
                zos.write(buildReportContent(report, reporter, target).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            plugin.getLogger().info("举报已存档: " + zipFile.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().severe("举报存档失败: " + e.getMessage());
        }
    }

    private String buildReportContent(Report report, Player reporter, Player target) {
        StringBuilder sb = new StringBuilder();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(report.getTimestamp()));
        sb.append("========== 玩家举报记录 ==========\n");
        sb.append("举报时间: ").append(time).append('\n');
        sb.append("举报者: ").append(report.getReporterName())
                .append(" (UUID: ").append(report.getReporterUUID()).append(")\n");
        sb.append("举报者位置: ").append(formatLocation(reporter)).append('\n');
        sb.append("被举报者: ").append(report.getTargetName())
                .append(" (UUID: ").append(report.getTargetUUID()).append(")\n");
        sb.append("被举报者位置: ").append(formatLocation(target)).append('\n');
        sb.append("举报原因: ").append(report.getReason()).append('\n');
        return sb.toString();
    }

    private String formatLocation(Player player) {
        if (player == null || !player.isOnline()) {
            return "已离线";
        }
        return player.getWorld().getName()
                + " @ (" + player.getLocation().getBlockX()
                + ", " + player.getLocation().getBlockY()
                + ", " + player.getLocation().getBlockZ() + ")";
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
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
