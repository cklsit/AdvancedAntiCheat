package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final AdvancedAntiCheat plugin;
    private FileConfiguration config;
    private FileConfiguration messagesConfig;
    private File messagesFile;

    public ConfigManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        loadConfigDefaults();
        loadMessagesConfig();
    }

    private void loadConfigDefaults() {
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

        config.options().copyDefaults(true);
        plugin.saveConfig();
    }

    private void loadMessagesConfig() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        
        try (InputStream is = plugin.getResource("messages.yml")) {
            if (is != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                messagesConfig.setDefaults(defaultConfig);
                messagesConfig.options().copyDefaults(true);
                saveMessagesConfig();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载默认messages.yml: " + e.getMessage());
        }
    }

    public void reloadMessagesConfig() {
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        try (InputStream is = plugin.getResource("messages.yml")) {
            if (is != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                messagesConfig.setDefaults(defaultConfig);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("无法重新加载messages.yml: " + e.getMessage());
        }
    }

    public void saveMessagesConfig() {
        try {
            messagesConfig.save(messagesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存messages.yml: " + e.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public String getMessage(String key) {
        return messagesConfig.getString(key, "消息未配置");
    }

    public List<String> getBanScreenLines() {
        return messagesConfig.getStringList("ban-screen.lines");
    }

    public String getPermanentBanText() {
        return messagesConfig.getString("ban-screen.permanent-ban", "§c永久封禁");
    }

    public String getTimeFormat(String key) {
        return messagesConfig.getString("ban-screen.time-format." + key, "");
    }

    public String formatBanScreen(String reason, String banTime) {
        List<String> lines = getBanScreenLines();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            line = line.replace("{reason}", reason);
            line = line.replace("{banTime}", banTime);
            sb.append(line);
            if (i < lines.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public String formatTime(long milliseconds, Map<String, String> replacements) {
        if (milliseconds <= 0) {
            return "已到期";
        }

        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        Map<String, Long> values = new HashMap<>();
        values.put("days", days);
        values.put("hours", hours % 24);
        values.put("minutes", minutes % 60);
        values.put("seconds", seconds % 60);

        String format;
        if (days > 0) {
            format = getTimeFormat("combined");
        } else if (hours > 0) {
            format = getTimeFormat("combined-hours");
        } else if (minutes > 0) {
            format = getTimeFormat("combined-minutes");
        } else {
            format = getTimeFormat("seconds");
        }

        for (Map.Entry<String, Long> entry : values.entrySet()) {
            format = format.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }

        return format;
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