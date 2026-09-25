package com.anticheat.gui;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.BanManager;
import com.anticheat.profiles.PlayerProfile;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /ac config —— 游戏内多级管理界面（6 层）。
 *
 * <pre>
 *  第 1 层 主菜单          /ac config
 *  第 2 层 在线玩家列表     点击「在线玩家」
 *  第 3 层 玩家详情        点击列表中的玩家名
 *  第 4 层 封禁时长选择     点击「执行封禁」
 *  第 5 层 封禁玩家列表     点击主菜单「当前封禁玩家数量」
 *  第 6 层 封禁详情/解封    点击封禁列表中的玩家名
 * </pre>
 *
 * <p>菜单身份通过 {@link ConfigMenu}（{@link InventoryHolder}）传递给监听器，
 * 不使用外部 Map 关联，避免嵌套打开时状态串味。
 *
 * <p>跨版本：所有版本相关材质走 {@link VersionUtil#compatMaterial} 反射，
 * 头颅同时兼容 1.8 的 {@code SKULL_ITEM:3} 与 1.13+ 的 {@code PLAYER_HEAD}。
 */
public class ConfigGUI {

    public enum MenuType { MAIN, ONLINE_LIST, PLAYER_DETAIL, BAN_DURATION, BAN_LIST, BAN_DETAIL }

    public static final int SIZE = 54;
    public static final int PADDING = 9;

    /** 内容区槽位（4 行 × 7 列，避开边框），每页 28 个 */
    public static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    public static final int PER_PAGE = CONTENT_SLOTS.length;

    /** 导航栏槽位 */
    public static final int SLOT_BACK = 45;
    public static final int SLOT_PREV = 48;
    public static final int SLOT_PAGE = 49;
    public static final int SLOT_NEXT = 50;
    public static final int SLOT_CLOSE_LIST = 53;
    public static final int SLOT_CLOSE = 49;

    /** 第 1 层 主菜单 */
    public static final int MAIN_SELF = 4;
    public static final int MAIN_ONLINE = 20;
    public static final int MAIN_BANS = 22;
    public static final int MAIN_STATUS = 24;

    /** 第 3 层 玩家详情 */
    public static final int DETAIL_HEAD = 4;
    public static final int DETAIL_LAST_LOGIN = 19;
    public static final int DETAIL_RISK = 21;
    public static final int DETAIL_CAPTCHA = 25;
    public static final int DETAIL_TELEPORT = 28;
    public static final int DETAIL_BAN = 30;
    public static final int DETAIL_WHITELIST = 32;

    /** 第 6 层 封禁详情 */
    public static final int BAN_DETAIL_HEAD = 4;
    public static final int BAN_DETAIL_UNBAN = 22;

    /** 面板标题前缀默认值（可在 config.yml 的 gui.title-prefix 覆盖） */
    private static final String DEFAULT_TITLE_PREFIX = "§8AAC";


    /**
     * 第 4 层封禁选项。{@code duration == null} 表示仅踢出（不写入封禁名单）。
     */
    public static final class BanOption {
        public final int slot;
        public final String label;
        public final String duration;

        BanOption(int slot, String label, String duration) {
            this.slot = slot;
            this.label = label;
            this.duration = duration;
        }

        public boolean isKickOnly() {
            return duration == null;
        }
    }

    private static final BanOption[] BAN_OPTIONS = {
            new BanOption(19, "§c踢出服务器", null),
            new BanOption(21, "§e封禁 30 分钟", "30m"),
            new BanOption(23, "§e封禁 1 小时", "1h"),
            new BanOption(25, "§e封禁 3 天", "3d"),
            new BanOption(28, "§e封禁 5 天", "5d"),
            new BanOption(30, "§e封禁 14 天", "14d"),
            new BanOption(32, "§e封禁 30 天", "30d"),
            new BanOption(34, "§c永久封禁", "permanent"),
    };

    /** 取该槽位对应的封禁选项，非选项槽返回 null */
    public static BanOption banOptionAt(int slot) {
        for (BanOption option : BAN_OPTIONS) {
            if (option.slot == slot) {
                return option;
            }
        }
        return null;
    }

    private final AdvancedAntiCheat plugin;

    public ConfigGUI(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    // ================================================================
    // 第 1 层：主菜单
    // ================================================================

    public void openMain(Player viewer) {
        ConfigMenu menu = new ConfigMenu(MenuType.MAIN, null, null, 0, 0);
        Inventory inv = create(menu, title("主菜单"));
        fill(inv);

        // 打开界面的管理员本人（默认显示打开界面的玩家名）
        inv.setItem(MAIN_SELF, headItem(viewer, 1, "§6§l玩家：§e" + viewer.getName(),
                Arrays.asList("§7默认是显示打开界面的玩家名",
                        "§7当前在线: §f" + onlineCount() + " §7人",
                        "§8" + viewer.getUniqueId())));

        // 点击进入第 2 层
        int online = onlineCount();
        inv.setItem(MAIN_ONLINE, headItem(viewer, clampAmount(online),
                "§a§l在线玩家：§e" + online,
                Arrays.asList("§7数量随着在线玩家的改变而改变",
                        "§7最大 64 个（可点击）",
                        "",
                        "§e点击查看在线玩家列表")));

        // 点击进入第 5 层
        int bans = banEntries().size();
        inv.setItem(MAIN_BANS, item(fillerMaterial(), clampAmount(bans),
                "§c§l当前封禁玩家数量：§e" + bans,
                Arrays.asList("§7随封禁玩家数量变化而变化",
                        "§7最大 64 个（可点击）",
                        "",
                        "§e点击查看封禁名单并解封")));

        // 反作弊运行状态（仅展示）
        inv.setItem(MAIN_STATUS, item(compat("BOOK", "BOOK", Material.BOOK), 1,
                "§b§l反作弊状态总览",
                statusLore()));

        inv.setItem(SLOT_CLOSE, closeItem());
        viewer.openInventory(inv);
    }

    private List<String> statusLore() {
        List<String> lore = new ArrayList<>();
        // 检测开关现在只有一处来源：core.checks.<名字>.enabled。
        // 旧引擎的 detection.fly/speed/... 键已随它删除，继续按那几个名字读会永远返回默认值。
        int enabled = 0;
        int total = 0;
        org.bukkit.configuration.ConfigurationSection checks =
                plugin.getConfig().getConfigurationSection("core.checks");
        if (checks != null) {
            for (String key : checks.getKeys(false)) {
                total++;
                if (checks.getBoolean(key + ".enabled", true)) {
                    enabled++;
                }
            }
        }
        lore.add("§7检测模块: §a" + enabled + " §7/ §f" + total + " §7已启用");
        boolean aiLoaded = plugin.getAILabManager() != null;
        lore.add("§7AI 实验室: " + (aiLoaded ? "§a已加载" : "§8未启用（纯规则模式）"));
        String dbType = "未知";
        try {
            if (plugin.getDatabaseManager() != null) {
                dbType = plugin.getDatabaseManager().getDatabaseType();
            }
        } catch (Throwable ignored) {
            // 数据库未就绪时保持占位
        }
        lore.add("§7存储后端: §f" + dbType);
        lore.add("§7白名单玩家: §f" + (plugin.getWhitelistManager() == null
                ? 0 : plugin.getWhitelistManager().size()) + " §7人");
        lore.add("");
        lore.add("§7封禁时长档位: §f" + BAN_OPTIONS.length + " §7档（含踢出）");
        return lore;
    }

    // ================================================================
    // 第 2 层：在线玩家列表（分页）
    // ================================================================

    public void openOnlineList(Player viewer, int requestPage) {
        List<Player> online = orderedOnlinePlayers();

        int pages = Math.max(1, (int) Math.ceil(online.size() / (double) PER_PAGE));
        int page = Math.max(0, Math.min(requestPage, pages - 1));

        ConfigMenu menu = new ConfigMenu(MenuType.ONLINE_LIST, null, null, page, 0);
        Inventory inv = create(menu, title("在线玩家 §7" + (page + 1) + "/" + pages));
        fill(inv);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= online.size()) {
                break;
            }
            inv.setItem(CONTENT_SLOTS[i], onlineEntry(online.get(index)));
        }

        inv.setItem(SLOT_BACK, woolItem(DyeColor.YELLOW, "§e返回至上一页（可点击）", null));
        if (page > 0) {
            inv.setItem(SLOT_PREV, woolItem(DyeColor.LIME, "§a上一页（可点击）", null));
        }
        inv.setItem(SLOT_PAGE, item(compat("BOOK", "BOOK", Material.BOOK), clampAmount(page + 1),
                "§f当前页数：§e" + (page + 1) + " §7个/ " + pages,
                Arrays.asList("§7物品数量也会随当前页数变化",
                        "§7最大 64",
                        "§7在线玩家总数: §f" + online.size())));
        if (page < pages - 1) {
            inv.setItem(SLOT_NEXT, woolItem(DyeColor.LIGHT_BLUE, "§a下一页（可点击）", null));
        }
        inv.setItem(SLOT_CLOSE_LIST, closeItem());
        viewer.openInventory(inv);
    }

    private ItemStack onlineEntry(Player target) {
        int risk = dangerIndex(target);
        List<String> lore = new ArrayList<>();
        lore.add("§7在线玩家（数量随着在线玩家的改变而改变，最大 64，可点击）");
        lore.add("§7危险指数：§c" + risk + " §8/ §7100");
        lore.add("§7所在世界：§f" + (target.getWorld() == null ? "?" : target.getWorld().getName()));
        lore.add("§7延迟：§f" + ping(target) + "ms");
        lore.add("§8" + target.getUniqueId());
        lore.add("");
        lore.add("§e点击查看该玩家详情");
        return headItem(target, 1, "§f" + target.getName() + " §7(可点击)", lore);
    }

    // ================================================================
    // 第 3 层：玩家详情
    // ================================================================

    public void openPlayerDetail(Player viewer, UUID targetUuid, String targetName, int returnPage) {
        ConfigMenu menu = new ConfigMenu(MenuType.PLAYER_DETAIL, targetUuid, targetName, 0, returnPage);
        Inventory inv = create(menu, title(targetName));
        fill(inv);

        OfflinePlayer offline = toOfflinePlayer(targetUuid, targetName);
        Player online = targetUuid == null ? null : Bukkit.getPlayer(targetUuid);
        boolean isOnline = online != null && online.isOnline();

        List<String> headLore = new ArrayList<>();
        headLore.add("§7玩家名称：§f" + targetName);
        headLore.add("§7状态：" + (isOnline ? "§a在线" : "§c离线"));
        headLore.add("§7UUID: §8" + targetUuid);
        boolean whitelisted = targetUuid != null && plugin.getWhitelistManager() != null
                && plugin.getWhitelistManager().isWhitelisted(targetUuid);
        if (whitelisted) {
            headLore.add("§a✔ 已加入可信白名单");
        }
        inv.setItem(DETAIL_HEAD, headItem(offline, 1, "§6§l" + targetName, headLore));

        // 最后登录时间
        long lastPlayed = lastPlayed(offline);
        inv.setItem(DETAIL_LAST_LOGIN, item(Material.PAPER, 1,
                "§f最后登录于：§e" + formatDate(lastPlayed),
                Arrays.asList("§7首次进入：§f" + formatDate(firstPlayed(offline)),
                        "§7在线时长（本次/累计）：§f" + totalPlayTime(targetUuid, online))));

        // 危险指数（0 ~ 100）
        int risk = online != null ? dangerIndex(online) : dangerIndex(targetUuid);
        inv.setItem(DETAIL_RISK, item(Material.REDSTONE, clampAmount(Math.max(1, risk)),
                "§c当前危险指数：§e" + risk,
                Arrays.asList("§7最小为 §f0§7，最大为 §f100",
                        "§7由反作弊自动检测",
                        "§7等级：§f" + riskLevelText(risk))));

        // 验证码测试（原先的「立即发起查证」已删除：它与验证码走的是同一个 CaptchaManager 流程，
        // 区别仅在于是否强制重开会话，保留两个入口只会造成误导）
        inv.setItem(DETAIL_CAPTCHA, item(Material.NAME_TAG, 1,
                "§b立即向该玩家发送验证码测试（可点击）",
                Arrays.asList("§7无视冷却，强制重开验证码会话",
                        "§7用于验证该玩家的操作能力",
                        isOnline ? "§7目标在线，点击后立即下发" : "§c目标离线，仅在线玩家可发送")));

        // 传送 / 封禁 / 白名单
        inv.setItem(DETAIL_TELEPORT, item(Material.ENDER_PEARL, 1,
                "§a传送至该玩家（可点击）",
                Arrays.asList("§7直接把管理员传送到目标身边",
                        isOnline ? "§7目标在线" : "§c目标离线，无法传送")));
        inv.setItem(DETAIL_BAN, item(Material.REDSTONE_BLOCK, 1,
                "§c§l执行封禁（可点击）",
                Arrays.asList("§7进入封禁时长选择界面",
                        "§7可选 30分钟 / 1小时 / 3天 / 5天 / 14天 / 30天 / 永久")));
        inv.setItem(DETAIL_WHITELIST, item(compat("EMERALD", "EMERALD", Material.EMERALD), 1,
                whitelisted ? "§c将该玩家移出白名单（可点击）" : "§a将该玩家加入白名单（可点击）",
                whitelisted
                        ? Arrays.asList("§7当前状态：§a已在白名单", "§7移出后反作弊检测立即恢复")
                        : Arrays.asList("§7意思是不做反作弊封禁",
                        "§7加入后该玩家全局豁免检测",
                        "§7可随时移出")));

        inv.setItem(SLOT_BACK, woolItem(DyeColor.YELLOW, "§e返回至上一页（可点击）", null));
        inv.setItem(SLOT_CLOSE, closeItem());
        viewer.openInventory(inv);
    }

    // ================================================================
    // 第 4 层：封禁时长选择
    // ================================================================

    public void openBanDuration(Player viewer, UUID targetUuid, String targetName) {
        ConfigMenu menu = new ConfigMenu(MenuType.BAN_DURATION, targetUuid, targetName, 0, 0);
        Inventory inv = create(menu, title("执行封禁 §7" + targetName));
        fill(inv);

        OfflinePlayer offline = toOfflinePlayer(targetUuid, targetName);
        Player online = targetUuid == null ? null : Bukkit.getPlayer(targetUuid);
        List<String> headLore = new ArrayList<>();
        headLore.add("§7目标：§f" + targetName);
        headLore.add("§7状态：" + (online != null && online.isOnline() ? "§a在线" : "§c离线"));
        headLore.add("§8" + targetUuid);
        headLore.add("");
        headLore.add("§c请选择封禁时长，点击后立即生效");
        inv.setItem(4, headItem(offline, 1, "§6§l" + targetName, headLore));

        for (BanOption option : BAN_OPTIONS) {
            List<String> lore = new ArrayList<>();
            if (option.isKickOnly()) {
                lore.add("§7仅将该玩家踢出当前会话");
                lore.add("§7不会写入封禁名单");
            } else {
                lore.add("§7封禁时长：§f" + labelOfDuration(option.duration));
                lore.add("§7将写入封禁名单并支持跨服同步");
                lore.add("§7可通过封禁列表随时解封");
            }
            lore.add("");
            lore.add("§c点击立即执行");
            inv.setItem(option.slot, item(banItemMaterial(option), 1, option.label, lore));
        }

        inv.setItem(SLOT_BACK, woolItem(DyeColor.YELLOW, "§e返回至上一页（可点击）", null));
        inv.setItem(SLOT_CLOSE, closeItem());
        viewer.openInventory(inv);
    }

    private Material banItemMaterial(BanOption option) {
        if (option.isKickOnly()) {
            return compat("IRON_DOOR", "IRON_DOOR", Material.LEATHER);
        }
        if ("permanent".equals(option.duration)) {
            return VersionUtil.compatBarrier();
        }
        return Material.REDSTONE_BLOCK;
    }

    // ================================================================
    // 第 5 层：封禁玩家列表（分页）
    // ================================================================

    public void openBanList(Player viewer, int requestPage) {
        List<Map.Entry<UUID, com.anticheat.managers.BanManager.BanInfo>> list =
                new ArrayList<>(banEntries().entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().getEndTime(), a.getValue().getEndTime()));

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        int page = Math.max(0, Math.min(requestPage, pages - 1));

        ConfigMenu menu = new ConfigMenu(MenuType.BAN_LIST, null, null, page, 0);
        Inventory inv = create(menu, title("封禁名单 §7" + (page + 1) + "/" + pages));
        fill(inv);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= list.size()) {
                break;
            }
            Map.Entry<UUID, com.anticheat.managers.BanManager.BanInfo> entry = list.get(index);
            inv.setItem(CONTENT_SLOTS[i], bannedEntry(entry.getKey(), entry.getValue()));
        }

        inv.setItem(SLOT_BACK, woolItem(DyeColor.YELLOW, "§e返回至上一页（可点击）", null));
        if (page > 0) {
            inv.setItem(SLOT_PREV, woolItem(DyeColor.LIME, "§a上一页（可点击）", null));
        }
        inv.setItem(SLOT_PAGE, item(compat("BOOK", "BOOK", Material.BOOK), clampAmount(page + 1),
                "§f当前页数：§e" + (page + 1) + " §7个/ " + pages,
                Arrays.asList("§7物品数量也会随当前页数变化",
                        "§7最大 64",
                        "§7封禁总数: §f" + list.size())));
        if (page < pages - 1) {
            inv.setItem(SLOT_NEXT, woolItem(DyeColor.LIGHT_BLUE, "§a下一页（可点击）", null));
        }
        inv.setItem(SLOT_CLOSE_LIST, closeItem());
        viewer.openInventory(inv);
    }

    private ItemStack bannedEntry(UUID uuid, com.anticheat.managers.BanManager.BanInfo info) {
        String name = info.getName() == null ? "unknown" : info.getName();
        long endTime = info.getEndTime();
        boolean expired = endTime > 0 && endTime <= System.currentTimeMillis();

        List<String> lore = new ArrayList<>();
        lore.add("§7封禁原因：§f" + (info.getReason() == null ? "未填写" : info.getReason()));
        lore.add("§7封禁到期：§f" + (endTime <= 0 ? "永久封禁" : formatDate(endTime)));
        if (endTime > 0) {
            lore.add("§7剩余时长：§f" + (expired ? "已过期" : remainText(endTime)));
        }
        lore.add("§7状态：" + (expired ? "§e已过期（等待清理）" : "§c封禁中"));
        lore.add("§8" + uuid);
        lore.add("");
        lore.add("§e点击查看详情并解封");
        return headItem(toOfflinePlayer(uuid, name), 1, "§c" + name + " §7(可点击)", lore);
    }

    // ================================================================
    // 第 6 层：封禁详情 / 解封
    // ================================================================

    public void openBanDetail(Player viewer, UUID targetUuid, String targetName, int returnPage) {
        ConfigMenu menu = new ConfigMenu(MenuType.BAN_DETAIL, targetUuid, targetName, 0, returnPage);
        Inventory inv = create(menu, title("封禁详情 §7" + targetName));
        fill(inv);

        BanManager.BanInfo info = null;
        try {
            info = plugin.getBanManager().getBanInfo(targetUuid);
        } catch (Throwable ignored) {
            // 记录缺失时下面按空处理
        }
        String name = info != null && info.getName() != null ? info.getName() : targetName;
        long endTime = info == null ? 0L : info.getEndTime();

        List<String> headLore = new ArrayList<>();
        headLore.add("§7玩家名称：§f" + name);
        headLore.add("§7封禁到期：§f" + (endTime <= 0 ? "永久封禁" : formatDate(endTime)));
        headLore.add("§7剩余时长：§f" + (endTime <= 0 ? "永久" : remainText(endTime)));
        headLore.add("§7封禁原因：§f" + (info == null || info.getReason() == null ? "未填写" : info.getReason()));
        headLore.add("§8" + targetUuid);
        inv.setItem(BAN_DETAIL_HEAD, headItem(toOfflinePlayer(targetUuid, name), 1, "§6§l" + name, headLore));

        inv.setItem(BAN_DETAIL_UNBAN, item(compat("EMERALD", "EMERALD", Material.EMERALD), 1,
                "§a解除封禁（可点击）",
                Arrays.asList("§7立即将该玩家移出封禁名单",
                        "§7同时清理数据库中的封禁记录",
                        "",
                        "§a点击解封")));

        inv.setItem(SLOT_BACK, woolItem(DyeColor.YELLOW, "§e返回至上一页（可点击）", null));
        inv.setItem(SLOT_CLOSE, closeItem());
        viewer.openInventory(inv);
    }

    // ================================================================
    // 数据读取
    // ================================================================

    private int onlineCount() {
        try {
            List<Player> online = VersionUtil.safeGetOnlinePlayers();
            return online == null ? 0 : online.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 在线玩家列表顺序（高风险优先），渲染与点击分发共用，保证索引一致 */
    public List<Player> orderedOnlinePlayers() {
        List<Player> online = new ArrayList<>();
        try {
            List<Player> raw = VersionUtil.safeGetOnlinePlayers();
            if (raw != null) {
                for (Player p : raw) {
                    if (p != null && p.isOnline()) {
                        online.add(p);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 保持空列表
        }
        online.sort(Comparator
                .comparingInt((Player p) -> dangerIndex(p)).reversed()
                .thenComparing(p -> p.getName() == null ? "" : p.getName().toLowerCase()));
        return online;
    }

    /** 封禁名单顺序（到期时间倒序：永久封禁置顶），渲染与点击分发共用 */
    public List<Map.Entry<UUID, BanManager.BanInfo>> orderedBanEntries() {
        List<Map.Entry<UUID, BanManager.BanInfo>> list = new ArrayList<>(banEntries().entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().getEndTime(), a.getValue().getEndTime()));
        return list;
    }

    /** 封禁快照（UUID → BanInfo），异常时返回空表，保证 GUI 永不因存储层异常打不开 */
    public Map<UUID, BanManager.BanInfo> banEntries() {
        try {
            Map<UUID, BanManager.BanInfo> map = plugin.getBanManager().getBanEntries();
            return map == null ? new java.util.LinkedHashMap<>() : map;
        } catch (Throwable t) {
            plugin.getLogger().warning("[GUI] 读取封禁名单失败: " + t.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    /** 危险指数 0~100（由 0~1000 的 riskScore 归一化，与 Web 面板口径一致） */
    public int dangerIndex(Player player) {
        if (player == null) {
            return 0;
        }
        try {
            PlayerProfile profile = plugin.getProfileManager().getProfile(player);
            if (profile == null) {
                profile = plugin.getProfileManager().getOrCreateProfile(player);
            }
            return profile == null ? 0 : clampRisk(profile.getRiskScore());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 离线玩家的危险指数（仅读取已缓存/已持久化档案，不创建新档案） */
    public int dangerIndex(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        try {
            PlayerProfile profile = plugin.getProfileManager().getProfile(uuid);
            return profile == null ? 0 : clampRisk(profile.getRiskScore());
        } catch (Throwable t) {
            return 0;
        }
    }

    private int clampRisk(double rawRiskScore) {
        int index = (int) Math.round(rawRiskScore / 10.0);
        return Math.max(0, Math.min(100, index));
    }

    private String riskLevelText(int risk) {
        if (risk >= 90) {
            return "极高（建议立即处理）";
        }
        if (risk >= 70) {
            return "高";
        }
        if (risk >= 50) {
            return "中";
        }
        if (risk > 0) {
            return "低";
        }
        return "无异常";
    }

    private int ping(Player player) {
        try {
            Object result = Player.class.getMethod("getPing").invoke(player);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private long lastPlayed(OfflinePlayer player) {
        try {
            return player == null ? 0L : player.getLastPlayed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private long firstPlayed(OfflinePlayer player) {
        try {
            return player == null ? 0L : player.getFirstPlayed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private String totalPlayTime(UUID uuid, Player online) {
        try {
            PlayerProfile profile = online != null
                    ? plugin.getProfileManager().getProfile(online)
                    : plugin.getProfileManager().getProfile(uuid);
            if (profile == null) {
                return "未知";
            }
            int seconds = profile.getTotalPlayTime();
            return (seconds / 3600) + " 小时 " + ((seconds % 3600) / 60) + " 分钟";
        } catch (Throwable t) {
            return "未知";
        }
    }

    private OfflinePlayer toOfflinePlayer(UUID uuid, String name) {
        try {
            if (uuid != null) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    return online;
                }
                return Bukkit.getOfflinePlayer(uuid);
            }
            if (name != null && !name.isEmpty()) {
                return Bukkit.getOfflinePlayer(name);
            }
        } catch (Throwable ignored) {
            // 未知玩家 → 使用默认头颅皮肤
        }
        return null;
    }

    // ================================================================
    // 渲染工具
    // ================================================================

    private Inventory create(ConfigMenu menu, String title) {
        Inventory inv = Bukkit.createInventory(menu, SIZE, title);
        menu.bind(inv);
        return inv;
    }

    private String title(String suffix) {
        String prefix = plugin.getConfig().getString("gui.title-prefix", DEFAULT_TITLE_PREFIX);
        if (prefix == null || prefix.isEmpty()) {
            prefix = DEFAULT_TITLE_PREFIX;
        }
        return suffix == null || suffix.isEmpty() ? prefix : prefix + " §8· §f" + suffix;
    }

    /**
     * 仅用配置的填充物铺设最外圈边框（第 1/6 行与左右两列），
     * 内部内容区留空，避免屏障铺满界面；点击安全由
     * {@link com.anticheat.listeners.ConfigGUIListener} 统一 cancel 保证。
     */
    private void fill(Inventory inv) {
        ItemStack filler = filler();
        for (int i = 0; i < SIZE; i++) {
            int row = i / 9;
            int col = i % 9;
            boolean border = row == 0 || row == 5 || col == 0 || col == 8;
            if (border) {
                inv.setItem(i, filler);
            } else {
                inv.setItem(i, null);
            }
        }
    }

    private Material fillerMaterial() {
        String configured = plugin.getConfig().getString("gui.filler-material", "BARRIER");
        if (configured == null || configured.isEmpty()) {
            return VersionUtil.compatBarrier();
        }
        Material material = VersionUtil.compatMaterial(configured.toUpperCase(), null, null);
        return material == null || material == Material.AIR ? VersionUtil.compatBarrier() : material;
    }

    private ItemStack filler() {
        return item(fillerMaterial(), 1, "§8·", null);
    }

    private ItemStack closeItem() {
        return item(VersionUtil.compatBarrier(), 1, "§c§l关闭窗口（可点击）",
                Arrays.asList("§7关闭当前界面", "§8/ac config"));
    }

    /** 通用物品：所有材质名走反射，避免 1.8 与 1.21 枚举差异导致类加载失败 */
    private static Material compat(String modernName, String legacyName, Material fallback) {
        return VersionUtil.compatMaterial(modernName, legacyName, fallback);
    }

    private ItemStack item(Material material, int amount, String name, List<String> lore) {
        Material safeMaterial = material == null ? Material.STONE : material;
        ItemStack item = new ItemStack(safeMaterial, clampAmount(amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(new ArrayList<>(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 玩家头颅：1.13+ 使用 PLAYER_HEAD + setOwningPlayer；
     * 1.8 使用 SKULL_ITEM:3 + setOwner。
     */
    private ItemStack headItem(OfflinePlayer owner, int amount, String name, List<String> lore) {
        ItemStack item;
        try {
            item = new ItemStack(Material.valueOf("PLAYER_HEAD"));
        } catch (Throwable modern) {
            try {
                item = new ItemStack(Material.valueOf("SKULL_ITEM"), 1, (short) 3);
            } catch (Throwable legacy) {
                item = new ItemStack(compat("PLAYER_HEAD", "SKULL_ITEM", Material.STONE));
            }
        }
        item.setAmount(clampAmount(amount));

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta && owner != null) {
            SkullMeta skullMeta = (SkullMeta) meta;
            boolean applied = false;
            try {
                skullMeta.setOwningPlayer(owner);
                applied = true;
            } catch (Throwable ignored) {
                // 1.8 无 setOwningPlayer
            }
            if (!applied) {
                try {
                    String ownerName = owner.getName();
                    if (ownerName != null && !ownerName.isEmpty()) {
                        skullMeta.setOwner(ownerName);
                    }
                } catch (Throwable ignored) {
                    // 皮肤缺失时退化为默认头颅
                }
            }
        }
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(new ArrayList<>(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 彩色羊毛：高版本按 LIME_WOOL 取，1.8 用 WOOL + durability 上色 */
    private ItemStack woolItem(DyeColor color, String name, List<String> lore) {
        ItemStack item = null;
        Material modernWool = VersionUtil.compatMaterial(color.name() + "_WOOL", null, null);
        if (modernWool != null && modernWool != Material.AIR) {
            item = new ItemStack(modernWool, 1);
        } else {
            Material legacyWool = VersionUtil.compatMaterial("WOOL", "WOOL", Material.STONE);
            item = new ItemStack(legacyWool, 1);
            try {
                Method getWoolData = DyeColor.class.getMethod("getWoolData");
                Object data = getWoolData.invoke(color);
                if (data instanceof Byte) {
                    item.setDurability(((Byte) data).shortValue());
                }
            } catch (Throwable ignored) {
                // 高版本无 getWoolData，本身已用彩色枚举
            }
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(new ArrayList<>(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private int clampAmount(int amount) {
        return Math.max(1, Math.min(64, amount));
    }

    public static String formatDate(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        try {
            return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date(millis));
        } catch (Throwable t) {
            return "未知";
        }
    }

    public static String remainText(long endTime) {
        long remain = endTime - System.currentTimeMillis();
        if (remain <= 0) {
            return "已到期";
        }
        long days = remain / 86400000L;
        long hours = (remain % 86400000L) / 3600000L;
        long minutes = (remain % 3600000L) / 60000L;
        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        return Math.max(1, minutes) + " 分钟";
    }

    public static String labelOfDuration(String duration) {
        if (duration == null) {
            return "仅踢出";
        }
        switch (duration) {
            case "30m": return "30 分钟";
            case "1h": return "1 小时";
            case "3d": return "3 天";
            case "5d": return "5 天";
            case "14d": return "14 天";
            case "30d": return "30 天";
            case "permanent": return "永久";
            default: return duration;
        }
    }

    // ================================================================
    // 菜单载体
    // ================================================================

    /** 挂在 Inventory 上的菜单身份，监听器据此判定点击归属 */
    public static class ConfigMenu implements InventoryHolder {

        private final MenuType type;
        private final UUID targetUuid;
        private final String targetName;
        private final int page;
        private final int returnPage;
        private Inventory inventory;

        public ConfigMenu(MenuType type, UUID targetUuid, String targetName, int page, int returnPage) {
            this.type = type;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.page = page;
            this.returnPage = returnPage;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        public MenuType getType() {
            return type;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getTargetName() {
            return targetName;
        }

        public int getPage() {
            return page;
        }

        /** 从第 3/6 层返回时，列表应恢复的页码 */
        public int getReturnPage() {
            return returnPage;
        }

        /** 本菜单实际打开的容器大小（未绑定前按 STANDARD SIZE 兜底） */
        public int inventorySize() {
            return inventory == null ? SIZE : inventory.getSize();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
