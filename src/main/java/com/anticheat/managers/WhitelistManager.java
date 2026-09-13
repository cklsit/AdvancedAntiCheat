package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.utils.VersionUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件级白名单（可信玩家名单）。
 *
 * <p>语义：名单内的玩家被服务器信任，<b>不做反作弊封禁</b>。
 *
 * <p>实现要点：不逐处修改检测代码，而是在玩家登录（或加入名单）时为其挂载
 * {@code anticheat.bypass} 权限附件。项目内 30+ 处 {@code hasPermission("anticheat.bypass")}
 * 判定会自动放行，因此白名单天然对全部检测模块生效。
 *
 * <p>持久化：{@code plugins/AdvancedAntiCheat/whitelist.yml}。
 */
public class WhitelistManager implements Listener {

    /** 全局豁免权限节点（与检测模块约定一致） */
    public static final String BYPASS_PERMISSION = "anticheat.bypass";

    /** 细粒度豁免节点，与 detection.yml/config.yml 中的检测类型保持一致 */
    private static final String[] BYPASS_CHILDREN = {
            "anticheat.bypass.fly",
            "anticheat.bypass.speed",
            "anticheat.bypass.esp",
            "anticheat.bypass.killaura",
            "anticheat.bypass.reach"
    };

    private final AdvancedAntiCheat plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public WhitelistManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "whitelist.yml");
        load();
        applyToOnlinePlayers();
    }

    // ================================================================
    // 查询
    // ================================================================

    public boolean isWhitelisted(UUID uuid) {
        return uuid != null && entries.containsKey(uuid);
    }

    public boolean isWhitelisted(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        Entry entry = entryOf(name);
        return entry != null;
    }

    /** 按玩家名（忽略大小写）查找条目，找不到返回 null */
    public Entry entryOf(String name) {
        if (name == null) {
            return null;
        }
        for (Entry entry : entries.values()) {
            if (entry.getName() != null && entry.getName().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    public Entry entryOf(UUID uuid) {
        return uuid == null ? null : entries.get(uuid);
    }

    /** 全部名单条目，按加入时间倒序（最新加入的在前） */
    public List<Entry> getAll() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingLong(Entry::getAddedAt).reversed());
        return Collections.unmodifiableList(list);
    }

    public int size() {
        return entries.size();
    }

    // ================================================================
    // 增删
    // ================================================================

    /**
     * 加入白名单。
     *
     * @return true 表示新增成功；false 表示已在该名单中（UUID 或同名玩家已存在）
     */
    public boolean add(UUID uuid, String name, String addedBy) {
        if (uuid == null || name == null || name.isEmpty()) {
            return false;
        }
        if (entries.containsKey(uuid)) {
            return false;
        }
        Entry existing = entryOf(name);
        if (existing != null && !existing.getUuid().equals(uuid)) {
            // 同名不同 UUID（正版/离线切换）视为已存在，避免重复条目
            return false;
        }
        entries.put(uuid, new Entry(uuid, name, addedBy == null ? "console" : addedBy, System.currentTimeMillis()));
        save();
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            applyBypass(online);
        }
        return true;
    }

    /**
     * 移出白名单。
     *
     * @return true 表示确有条目被移除
     */
    public boolean remove(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Entry removed = entries.remove(uuid);
        if (removed == null) {
            return false;
        }
        save();
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            clearBypass(online);
            online.sendMessage("§e[AntiCheat] §7你已被移出服务器可信白名单，反作弊检测重新生效。");
        }
        return true;
    }

    /** 按玩家名移出白名单 */
    public boolean remove(String name) {
        Entry entry = entryOf(name);
        return entry != null && remove(entry.getUuid());
    }

    // ================================================================
    // 权限附件（真正的豁免落地）
    // ================================================================

    /** 为白名单玩家挂载 bypass 权限（可重复调用，内部会先清理旧附件） */
    public void applyBypass(Player player) {
        if (player == null) {
            return;
        }
        clearBypass(player);
        try {
            PermissionAttachment attachment = player.addAttachment(plugin);
            attachment.setPermission(BYPASS_PERMISSION, true);
            for (String child : BYPASS_CHILDREN) {
                attachment.setPermission(child, true);
            }
            attachments.put(player.getUniqueId(), attachment);
            player.recalculatePermissions();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Whitelist] 挂载 bypass 权限失败（"
                    + player.getName() + "）: " + t.getMessage());
        }
    }

    /** 移除本管理器挂载的 bypass 权限附件 */
    public void clearBypass(Player player) {
        if (player == null) {
            return;
        }
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment == null) {
            return;
        }
        try {
            player.removeAttachment(attachment);
            player.recalculatePermissions();
        } catch (Throwable ignored) {
            // 玩家已退服等场景，忽略
        }
    }

    private void applyToOnlinePlayers() {
        List<Player> online;
        try {
            online = VersionUtil.safeGetOnlinePlayers();
        } catch (Throwable t) {
            return;
        }
        if (online == null) {
            return;
        }
        for (Player player : online) {
            if (player != null && isWhitelisted(player.getUniqueId())) {
                applyBypass(player);
            }
        }
    }

    // ================================================================
    // 事件
    // ================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player != null && isWhitelisted(player.getUniqueId())) {
            applyBypass(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearBypass(event.getPlayer());
    }

    // ================================================================
    // 持久化
    // ================================================================

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yml.getConfigurationSection("players");
            if (section == null) {
                return;
            }
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String name = section.getString(key + ".name", "unknown");
                    String addedBy = section.getString(key + ".added-by", "console");
                    long addedAt = section.getLong(key + ".added-at", 0L);
                    entries.put(uuid, new Entry(uuid, name, addedBy, addedAt));
                } catch (IllegalArgumentException ignored) {
                    // 非法 UUID 键，跳过
                }
            }
            plugin.getLogger().info("[Whitelist] 已加载 " + entries.size() + " 个白名单玩家");
        } catch (Throwable t) {
            plugin.getLogger().warning("[Whitelist] 加载白名单失败: " + t.getMessage());
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Entry entry : new ArrayList<>(entries.values())) {
            String base = "players." + entry.getUuid() + ".";
            yml.set(base + "name", entry.getName());
            yml.set(base + "added-by", entry.getAddedBy());
            yml.set(base + "added-at", entry.getAddedAt());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[Whitelist] 保存白名单失败: " + e.getMessage());
        } catch (Throwable t) {
            plugin.getLogger().warning("[Whitelist] 保存白名单异常: " + t.getMessage());
        }
    }

    // ================================================================
    // 条目
    // ================================================================

    public static class Entry {
        private final UUID uuid;
        private final String name;
        private final String addedBy;
        private final long addedAt;

        public Entry(UUID uuid, String name, String addedBy, long addedAt) {
            this.uuid = uuid;
            this.name = name;
            this.addedBy = addedBy;
            this.addedAt = addedAt;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public String getAddedBy() {
            return addedBy;
        }

        public long getAddedAt() {
            return addedAt;
        }
    }
}
