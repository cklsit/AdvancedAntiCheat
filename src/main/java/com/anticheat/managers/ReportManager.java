package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.lang.reflect.Method;
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
        try {
            Class<?> textComponentClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> chatColorClass = Class.forName("net.md_5.bungee.api.ChatColor");
            Class<?> clickEventClass = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            Class<?> hoverEventClass = Class.forName("net.md_5.bungee.api.chat.HoverEvent");
            Class<?> hoverTextClass = Class.forName("net.md_5.bungee.api.chat.hover.content.Text");

            Object message = textComponentClass.getConstructor().newInstance();

            addTextPart(message, "[举报] ", chatColorClass.getField("RED").get(null), textComponentClass);
            addTextPart(message, reporterName, chatColorClass.getField("YELLOW").get(null), textComponentClass);
            addTextPart(message, " 举报了 ", chatColorClass.getField("GOLD").get(null), textComponentClass);
            addTextPart(message, targetName, chatColorClass.getField("YELLOW").get(null), textComponentClass);
            addTextPart(message, " 原因: ", chatColorClass.getField("GRAY").get(null), textComponentClass);
            addTextPart(message, reason, chatColorClass.getField("WHITE").get(null), textComponentClass);
            addTextPart(message, " ", null, textComponentClass);

            Object gotoReporter = textComponentClass.getConstructor(String.class).newInstance("[前往举报者]");
            textComponentClass.getMethod("setColor", chatColorClass).invoke(gotoReporter, chatColorClass.getField("GREEN").get(null));
            textComponentClass.getMethod("setBold", boolean.class).invoke(gotoReporter, true);
            
            Object[] clickEnumConstants = clickEventClass.getEnumConstants();
            Object clickAction = clickEnumConstants[0];
            Object clickEvent1 = clickEventClass.getConstructor(clickAction.getClass(), String.class)
                    .newInstance(clickAction, "/goto " + reporterName);
            textComponentClass.getMethod("setClickEvent", clickEventClass).invoke(gotoReporter, clickEvent1);
            
            Object hoverText1 = hoverTextClass.getConstructor(String.class).newInstance("点击传送至举报者身边");
            Object[] hoverEnumConstants = hoverEventClass.getEnumConstants();
            Object hoverAction = hoverEnumConstants[0];
            Object hoverEvent1 = hoverEventClass.getConstructor(hoverAction.getClass(), Object.class)
                    .newInstance(hoverAction, hoverText1);
            textComponentClass.getMethod("setHoverEvent", hoverEventClass).invoke(gotoReporter, hoverEvent1);
            textComponentClass.getMethod("addExtra", Object.class).invoke(message, gotoReporter);

            addTextPart(message, " ", null, textComponentClass);

            Object gotoTarget = textComponentClass.getConstructor(String.class).newInstance("[前往作弊者]");
            textComponentClass.getMethod("setColor", chatColorClass).invoke(gotoTarget, chatColorClass.getField("RED").get(null));
            textComponentClass.getMethod("setBold", boolean.class).invoke(gotoTarget, true);
            
            Object clickEvent2 = clickEventClass.getConstructor(clickAction.getClass(), String.class)
                    .newInstance(clickAction, "/goto " + targetName);
            textComponentClass.getMethod("setClickEvent", clickEventClass).invoke(gotoTarget, clickEvent2);
            
            Object hoverText2 = hoverTextClass.getConstructor(String.class).newInstance("点击传送至被举报者身边");
            Object hoverEvent2 = hoverEventClass.getConstructor(hoverAction.getClass(), Object.class)
                    .newInstance(hoverAction, hoverText2);
            textComponentClass.getMethod("setHoverEvent", hoverEventClass).invoke(gotoTarget, hoverEvent2);
            textComponentClass.getMethod("addExtra", Object.class).invoke(message, gotoTarget);

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("anticheat.notify")) {
                    Method spigotMethod = player.getClass().getMethod("spigot");
                    Object spigot = spigotMethod.invoke(player);
                    spigot.getClass().getMethod("sendMessage", textComponentClass).invoke(spigot, message);
                }
            }
        } catch (Exception e) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("anticheat.notify")) {
                    player.sendMessage("§c[举报] §e" + reporterName + " §6举报了 §e" + targetName + " §7原因: §f" + reason);
                    player.sendMessage("§a使用 /goto " + reporterName + " 前往举报者位置");
                    player.sendMessage("§c使用 /goto " + targetName + " 前往被举报者位置");
                }
            }
        }
    }

    private void addTextPart(Object message, String text, Object color, Class<?> textComponentClass) throws Exception {
        Object part = textComponentClass.getConstructor(String.class).newInstance(text);
        if (color != null) {
            textComponentClass.getMethod("setColor", Class.forName("net.md_5.bungee.api.ChatColor")).invoke(part, color);
        }
        textComponentClass.getMethod("addExtra", Object.class).invoke(message, part);
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