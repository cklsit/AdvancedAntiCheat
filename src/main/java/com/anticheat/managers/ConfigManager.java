package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final AdvancedAntiCheat plugin;
    private FileConfiguration config;

    public ConfigManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        loadDefaults();
    }

    private void loadDefaults() {
        config.addDefault("detection.fly.enabled", true);
        config.addDefault("detection.fly.maxViolations", 5);
        config.addDefault("detection.fly.banTime", "1h");

        config.addDefault("detection.speed.enabled", true);
        config.addDefault("detection.speed.maxViolations", 5);
        config.addDefault("detection.speed.banTime", "30m");

        config.addDefault("detection.esp.enabled", true);
        config.addDefault("detection.esp.maxViolations", 3);
        config.addDefault("detection.esp.banTime", "6h");

        config.addDefault("detection.killaura.enabled", true);
        config.addDefault("detection.killaura.maxViolations", 5);
        config.addDefault("detection.killaura.banTime", "1d");

        config.addDefault("detection.reach.enabled", true);
        config.addDefault("detection.reach.maxViolations", 5);
        config.addDefault("detection.reach.banTime", "2h");

        config.addDefault("ban.minTime", "1m");
        config.addDefault("ban.maxTime", "1d");

        config.addDefault("messages.prefix", "§8[§cAntiCheat§8]");
        config.addDefault("messages.noPermission", "§c您没有权限执行此命令！");
        config.addDefault("messages.playerNotFound", "§c未找到玩家: §e%s");
        config.addDefault("messages.reportSuccess", "§a举报已提交！管理员将尽快处理。");
        config.addDefault("messages.reportNotification", "§c[举报] §e%s §6举报了 §e%s §7原因: §f%s");
        config.addDefault("messages.banSuccess", "§a玩家 §e%s §a已被封禁 §e%s");
        config.addDefault("messages.unbanSuccess", "§a玩家 §e%s §a已被解封");
        config.addDefault("messages.alreadyBanned", "§c玩家 §e%s §c已经被封禁！");
        config.addDefault("messages.notBanned", "§c玩家 §e%s §c没有被封禁！");
        config.addDefault("messages.gotoSuccess", "§a已传送到 §e%s §a身边");

        config.options().copyDefaults(true);
        plugin.saveConfig();
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public String getMessage(String key) {
        return config.getString("messages." + key, "消息未配置");
    }

    public boolean isDetectionEnabled(String type) {
        return config.getBoolean("detection." + type + ".enabled", true);
    }

    public int getMaxViolations(String type) {
        return config.getInt("detection." + type + ".maxViolations", 5);
    }

    public String getBanTime(String type) {
        return config.getString("detection." + type + ".banTime", "1h");
    }
}