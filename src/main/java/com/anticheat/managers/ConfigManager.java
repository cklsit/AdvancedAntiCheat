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
import java.util.ArrayList;
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
        // 检测项默认值（包含细粒度封禁强度配置）
        addDetectionDefaults("fly",      true, 5, "1h",  20, 15, 2, 5000);
        addDetectionDefaults("speed",    true, 5, "30m", 20, 15, 2, 5000);
        addDetectionDefaults("esp",      true, 3, "6h",  15, 10, 2, 5000);
        addDetectionDefaults("killaura", true, 5, "1d",  20, 15, 2, 5000);
        addDetectionDefaults("reach",    true, 5, "2h",  20, 15, 2, 5000);

        // 顶层通知节流（防刷屏）
        config.addDefault("notify.throttleMs", 2000);

        config.addDefault("ban.minTime", "1m");
        config.addDefault("ban.maxTime", "1d");

        // Web 面板默认值
        config.addDefault("web.enabled", true);
        config.addDefault("web.host", "0.0.0.0");
        config.addDefault("web.port", 8080);
        config.addDefault("web.session-timeout-minutes", 120);
        config.addDefault("web.cors.enabled", false);

        // /ac config 游戏内管理界面
        config.addDefault("gui.enabled", true);
        config.addDefault("gui.title-prefix", "§8AAC");
        config.addDefault("gui.filler-material", "BARRIER");
        config.addDefault("gui.ban-reason", "管理员通过管理界面封禁");
        config.addDefault("gui.kick-reason", "§c你已被管理员移出服务器");

        config.options().copyDefaults(true);
        plugin.saveConfig();
    }

    /**
     * 批量为单个检测项写入默认值，避免重复样板代码。
     */
    private void addDetectionDefaults(String id, boolean enabled, int maxViolations, String banTime,
                                      int kickThreshold, int humanReviewThreshold,
                                      int warningCooldownSecs, int notifyCooldownMs) {
        String prefix = "detection." + id + ".";
        config.addDefault(prefix + "enabled", enabled);
        config.addDefault(prefix + "maxViolations", maxViolations);
        config.addDefault(prefix + "banTime", banTime);
        config.addDefault(prefix + "kickThreshold", kickThreshold);
        config.addDefault(prefix + "humanReviewThreshold", humanReviewThreshold);
        config.addDefault(prefix + "warningCooldownSecs", warningCooldownSecs);
        config.addDefault(prefix + "notifyCooldownMs", notifyCooldownMs);
    }

    // ===================== Web 面板配置 Getter =====================

    public boolean isWebEnabled() {
        return config.getBoolean("web.enabled", true);
    }

    public String getWebHost() {
        return config.getString("web.host", "0.0.0.0");
    }

    public int getWebPort() {
        return config.getInt("web.port", 8080);
    }

    public int getWebSessionTimeoutMinutes() {
        return config.getInt("web.session-timeout-minutes", 120);
    }

    public boolean isWebCorsEnabled() {
        return config.getBoolean("web.cors.enabled", false);
    }

    /**
     * 读取 web.auth.accounts 列表，每项含 username/password-hash/role/permissions。
     * 缺失时返回空列表，不会抛 NPE。
     */
    public List<Map<String, Object>> getWebAccounts() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<?> raw = config.getList("web.auth.accounts");
        if (raw == null) {
            return result;
        }
        for (Object item : raw) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) item;
                result.add(entry);
            }
        }
        return result;
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

    // ===================== 细粒度封禁强度 Getter（供 Web 面板 / DecisionActionCenter 使用） =====================

    /**
     * 将时长字符串（"30s"/"5m"/"2h"/"1d"/"permanent"）解析为毫秒。
     * 无法识别时返回 defMs。
     */
    public long parseDurationMs(String text, long defMs) {
        if (text == null) {
            return defMs;
        }
        String trimmed = text.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return defMs;
        }
        if ("permanent".equals(trimmed) || "perm".equals(trimmed) || "forever".equals(trimmed)) {
            return Long.MAX_VALUE;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+)\\s*([smhd])$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(trimmed);
        if (!m.matches()) {
            return defMs;
        }
        long value = Long.parseLong(m.group(1));
        char unit = Character.toLowerCase(m.group(2).charAt(0));
        switch (unit) {
            case 's': return value * 1000L;
            case 'm': return value * 60_000L;
            case 'h': return value * 3_600_000L;
            case 'd': return value * 86_400_000L;
            default:  return defMs;
        }
    }

    /** 获取指定检测项的封禁时长（毫秒）；"permanent" 返回 Long.MAX_VALUE。 */
    public long getBanTimeMs(String id, long defMs) {
        return parseDurationMs(config.getString("detection." + id + ".banTime", null), defMs);
    }

    /** 获取指定检测项触发踢出的违规阈值。 */
    public int getKickThreshold(String id, int def) {
        return config.getInt("detection." + id + ".kickThreshold", def);
    }

    /** 获取指定检测项升级人工审核的违规阈值。 */
    public int getHumanReviewThreshold(String id, int def) {
        return config.getInt("detection." + id + ".humanReviewThreshold", def);
    }

    /** 获取指定检测项的警告消息冷却（秒）。 */
    public int getWarningCooldownSecs(String id, int def) {
        return config.getInt("detection." + id + ".warningCooldownSecs", def);
    }

    /** 获取指定检测项的通知冷却（毫秒）。 */
    public long getNotifyCooldownMs(String id, long defMs) {
        return config.getLong("detection." + id + ".notifyCooldownMs", defMs);
    }

    /** 获取全局通知节流间隔（毫秒），控制 DecisionActionCenter 同类消息最小重复间隔。 */
    public long getGlobalNotifyThrottleMs(long defMs) {
        return config.getLong("notify.throttleMs", defMs);
    }

    /**
     * 获取指定通知类别的"重复提示间隔"（毫秒），键位于 notify.&lt;key&gt;（单位：秒）。
     * 配置为 0 或负数时返回 Long.MAX_VALUE，表示同一档位只提示一次、之后不再重复（仍会正常处罚）。
     * defMs &lt;= 0 表示默认"只提示一次"，只有配置里显式写正数才允许重复。
     */
    public long getNotifyRepeatMs(String key, long defMs) {
        int defSecs = defMs <= 0L ? 0 : (int) Math.max(1L, defMs / 1000L);
        int secs = config.getInt("notify." + key, defSecs);
        if (secs <= 0) {
            return Long.MAX_VALUE;
        }
        return secs * 1000L;
    }

    /** 设置指定检测项字段（Web 面板热更新用），值类型由调用方保证合法。 */
    public void setDetectionField(String id, String field, Object value) {
        config.set("detection." + id + "." + field, value);
    }

    /** 设置顶层字段（Web 面板热更新用）。 */
    public void setField(String path, Object value) {
        config.set(path, value);
    }

    /** 持久化配置到磁盘（必须在主线程调用）。 */
    public void saveConfig() {
        plugin.saveConfig();
    }

    /**
     * 在 plugin.reloadConfig() 之后调用，让本类持有的 config 引用重新指向最新的 FileConfiguration。
     * 必须在主线程调用。
     */
    public void refreshConfig() {
        this.config = plugin.getConfig();
    }
}