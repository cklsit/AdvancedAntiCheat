package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

    public ReportManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.reportsFile = new File(plugin.getDataFolder(), "reports.dat");
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
        Component message = Component.text()
                .append(Component.text("[举报] ", NamedTextColor.RED))
                .append(Component.text(reporterName, NamedTextColor.YELLOW))
                .append(Component.text(" 举报了 ", NamedTextColor.GOLD))
                .append(Component.text(targetName, NamedTextColor.YELLOW))
                .append(Component.text(" 原因: ", NamedTextColor.GRAY))
                .append(Component.text(reason, NamedTextColor.WHITE))
                .build();

        Component gotoReporterButton = Component.text()
                .content("[前往举报者]")
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/goto " + reporterName))
                .hoverEvent(HoverEvent.showText(Component.text("点击传送至举报者身边", NamedTextColor.AQUA)))
                .build();

        Component gotoTargetButton = Component.text()
                .content("[前往作弊者]")
                .color(NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/goto " + targetName))
                .hoverEvent(HoverEvent.showText(Component.text("点击传送至被举报者身边", NamedTextColor.AQUA)))
                .build();

        Component fullMessage = Component.text()
                .append(message)
                .append(Component.newline())
                .append(Component.text("  "))
                .append(gotoReporterButton)
                .append(Component.text("  "))
                .append(gotoTargetButton)
                .build();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("anticheat.notify")) {
                player.sendMessage(fullMessage);
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