package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class CheckClientConfigManager {

    private final AdvancedAntiCheat plugin;
    private FileConfiguration config;
    private File configFile;

    private String title;
    private String subtitle;
    private List<String> chatMessages;
    private int timeoutMinutes;

    public CheckClientConfigManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        if (configFile == null) {
            configFile = new File(plugin.getDataFolder(), "checkclient.yml");
        }

        if (!configFile.exists()) {
            plugin.saveResource("checkclient.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("checkclient.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
            config.setDefaults(defaultConfig);
        }

        loadValues();
    }

    private void loadValues() {
        title = config.getString("checkclient.title", "§c您正在被管理员查端!");
        subtitle = config.getString("checkclient.subtitle", "§e请看聊天框继续下一步");
        chatMessages = config.getStringList("checkclient.chat_message");
        timeoutMinutes = config.getInt("checkclient.timeout_minutes", 60);

        if (chatMessages == null || chatMessages.isEmpty()) {
            chatMessages = new ArrayList<>();
            chatMessages.add("§8§m------------------------------------------------");
            chatMessages.add("§f您已被 §b{vault_group} §f成员 §c§l冻结所有操作.");
            chatMessages.add("§f请在 §b{timeout} §f分钟内添加 §c{admin} §f的 §bQQ §f好友 §a{qq} §f进行客户端核实。");
            chatMessages.add("§f请不要退出此房间或关闭游戏,否则您的账号将会被封禁！");
            chatMessages.add("§8§m------------------------------------------------");
        }
    }

    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("保存查端配置失败: " + e.getMessage());
        }
    }

    public void reloadConfig() {
        loadConfig();
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public List<String> getChatMessages() {
        return chatMessages;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public String formatMessage(String message, String vaultGroup, String adminName, String qqNumber, int timeout) {
        return message
                .replace("{vault_group}", vaultGroup)
                .replace("{admin}", adminName)
                .replace("{qq}", qqNumber)
                .replace("{timeout}", String.valueOf(timeout));
    }

    public List<String> formatMessages(String vaultGroup, String adminName, String qqNumber, int timeout) {
        List<String> formatted = new ArrayList<>();
        for (String message : chatMessages) {
            formatted.add(formatMessage(message, vaultGroup, adminName, qqNumber, timeout));
        }
        return formatted;
    }
}