package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.compat.CompatManager;
import com.anticheat.gui.ConfigGUI;
import com.anticheat.managers.BanManager;
import com.anticheat.managers.WhitelistManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /ac config 六层菜单的点击分发。
 *
 * <p>菜单身份由 {@link ConfigGUI.ConfigMenu}（InventoryHolder）携带，
 * 因此多层嵌套打开、多管理员并发使用都不会串味。
 *
 * <p>所有打开下一层界面的动作都延后 1 tick 执行：在点击事件栈内直接
 * openInventory 容易被客户端忽略，出现"点了没反应"。
 */
public class ConfigGUIListener implements Listener {

    private static final String PREFIX = "§8[§cAntiCheat§8] §r";

    private final AdvancedAntiCheat plugin;
    private final ConfigGUI gui;

    public ConfigGUIListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.gui = new ConfigGUI(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        ConfigGUI.ConfigMenu menu = resolveMenu(event);
        if (menu == null) {
            return;
        }
        // 菜单内禁止任何取放行为（必须在任何可能抛异常的代码之前）
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player viewer = (Player) event.getWhoClicked();
        if (!viewer.hasPermission("anticheat.admin")) {
            viewer.closeInventory();
            viewer.sendMessage(PREFIX + "§c你的权限已被收回，无法继续使用该界面。");
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= menu.inventorySize()) {
            return;
        }
        dispatch(viewer, menu, rawSlot);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (resolveMenu(event) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * 解析事件对应的 ConfigMenu。
     *
     * <p>⚠️ 不能用 {@code event.getView().getTopInventory()}：{@code InventoryView}
     * 在 1.8 是 class、在 1.13+ 是 interface，按 1.21 编译出的 {@code invokeinterface}
     * 在 1.8 服务端会抛 {@code IncompatibleClassChangeError}，导致监听器整段失效
     * （表现为点击无反应 + 物品可被拖出）。
     *
     * <p>先用 {@link org.bukkit.event.inventory.InventoryEvent#getInventory()}
     * （各版本均为"顶层容器"，且是类方法，编译为 invokevirtual）；
     * 再用反射兜底取 {@code getOpenInventory().getTopInventory()}，同样避开类型差异。
     */
    private ConfigGUI.ConfigMenu resolveMenu(org.bukkit.event.inventory.InventoryEvent event) {
        Inventory top = null;
        try {
            top = event.getInventory();
        } catch (Throwable ignored) {
            // 落到反射兜底
        }
        ConfigGUI.ConfigMenu menu = asMenu(top);
        if (menu != null) {
            return menu;
        }
        try {
            Object who = event.getClass().getMethod("getWhoClicked").invoke(event);
            if (who != null) {
                Object view = who.getClass().getMethod("getOpenInventory").invoke(who);
                Object topInv = view == null ? null : view.getClass().getMethod("getTopInventory").invoke(view);
                if (topInv instanceof Inventory) {
                    menu = asMenu((Inventory) topInv);
                }
            }
        } catch (Throwable ignored) {
            // 两种方式都失败时放弃识别
        }
        return menu;
    }

    private ConfigGUI.ConfigMenu asMenu(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        try {
            InventoryHolder holder = inventory.getHolder();
            return holder instanceof ConfigGUI.ConfigMenu ? (ConfigGUI.ConfigMenu) holder : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================================================================
    // 分发
    // ================================================================

    private void dispatch(Player viewer, ConfigGUI.ConfigMenu menu, int slot) {
        switch (menu.getType()) {
            case MAIN:
                handleMain(viewer, slot);
                break;
            case ONLINE_LIST:
                handleOnlineList(viewer, menu, slot);
                break;
            case PLAYER_DETAIL:
                handlePlayerDetail(viewer, menu, slot);
                break;
            case BAN_DURATION:
                handleBanDuration(viewer, menu, slot);
                break;
            case BAN_LIST:
                handleBanList(viewer, menu, slot);
                break;
            case BAN_DETAIL:
                handleBanDetail(viewer, menu, slot);
                break;
            default:
                break;
        }
    }

    /** 第 1 层：主菜单 */
    private void handleMain(Player viewer, int slot) {
        if (slot == ConfigGUI.MAIN_ONLINE) {
            openLater(() -> gui.openOnlineList(viewer, 0));
            return;
        }
        if (slot == ConfigGUI.MAIN_BANS) {
            openLater(() -> gui.openBanList(viewer, 0));
            return;
        }
        if (slot == ConfigGUI.SLOT_CLOSE || slot == ConfigGUI.SLOT_CLOSE_LIST) {
            viewer.closeInventory();
        }
    }

    /** 第 2 层：在线玩家列表 */
    private void handleOnlineList(Player viewer, ConfigGUI.ConfigMenu menu, int slot) {
        int contentIndex = contentIndex(slot);
        if (contentIndex >= 0) {
            List<Player> online = gui.orderedOnlinePlayers();
            int index = menu.getPage() * ConfigGUI.PER_PAGE + contentIndex;
            if (index >= online.size()) {
                return;
            }
            Player target = online.get(index);
            UUID uuid = target.getUniqueId();
            String name = target.getName();
            int returnPage = menu.getPage();
            openLater(() -> gui.openPlayerDetail(viewer, uuid, name, returnPage));
            return;
        }
        if (slot == ConfigGUI.SLOT_BACK) {
            openLater(() -> gui.openMain(viewer));
        } else if (slot == ConfigGUI.SLOT_PREV && menu.getPage() > 0) {
            int page = menu.getPage() - 1;
            openLater(() -> gui.openOnlineList(viewer, page));
        } else if (slot == ConfigGUI.SLOT_NEXT) {
            int page = menu.getPage() + 1;
            openLater(() -> gui.openOnlineList(viewer, page));
        } else if (slot == ConfigGUI.SLOT_CLOSE_LIST) {
            viewer.closeInventory();
        }
    }

    /** 第 3 层：玩家详情 */
    private void handlePlayerDetail(Player viewer, ConfigGUI.ConfigMenu menu, int slot) {
        UUID uuid = menu.getTargetUuid();
        String name = menu.getTargetName();

        if (slot == ConfigGUI.SLOT_BACK) {
            int returnPage = menu.getReturnPage();
            openLater(() -> gui.openOnlineList(viewer, returnPage));
            return;
        }
        if (slot == ConfigGUI.SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }
        if (uuid == null) {
            return;
        }
        Player target = Bukkit.getPlayer(uuid);

        switch (slot) {
            case ConfigGUI.DETAIL_INVESTIGATE:
                if (requirePermission(viewer, "anticheat.captcha")) {
                    startCaptcha(viewer, target, name, false);
                }
                break;
            case ConfigGUI.DETAIL_CAPTCHA:
                if (requirePermission(viewer, "anticheat.captcha")) {
                    startCaptcha(viewer, target, name, true);
                }
                break;
            case ConfigGUI.DETAIL_TELEPORT:
                if (requirePermission(viewer, "anticheat.goto")) {
                    teleport(viewer, target, name);
                }
                break;
            case ConfigGUI.DETAIL_BAN:
                if (requirePermission(viewer, "anticheat.ban")) {
                    openLater(() -> gui.openBanDuration(viewer, uuid, name));
                }
                break;
            case ConfigGUI.DETAIL_WHITELIST:
                if (requirePermission(viewer, "anticheat.whitelist")) {
                    toggleWhitelist(viewer, uuid, name, menu.getReturnPage());
                }
                break;
            default:
                break;
        }
    }

    /** 第 4 层：封禁时长选择 */
    private void handleBanDuration(Player viewer, ConfigGUI.ConfigMenu menu, int slot) {
        UUID uuid = menu.getTargetUuid();
        String name = menu.getTargetName();

        if (slot == ConfigGUI.SLOT_BACK) {
            openLater(() -> gui.openPlayerDetail(viewer, uuid, name, 0));
            return;
        }
        if (slot == ConfigGUI.SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }

        ConfigGUI.BanOption option = ConfigGUI.banOptionAt(slot);
        if (option == null) {
            return;
        }
        applyPunishment(viewer, uuid, name, option);
    }

    /** 第 5 层：封禁名单 */
    private void handleBanList(Player viewer, ConfigGUI.ConfigMenu menu, int slot) {
        int contentIndex = contentIndex(slot);
        if (contentIndex >= 0) {
            List<Map.Entry<UUID, BanManager.BanInfo>> bans = gui.orderedBanEntries();
            int index = menu.getPage() * ConfigGUI.PER_PAGE + contentIndex;
            if (index >= bans.size()) {
                return;
            }
            Map.Entry<UUID, BanManager.BanInfo> entry = bans.get(index);
            UUID uuid = entry.getKey();
            String name = entry.getValue().getName();
            int returnPage = menu.getPage();
            openLater(() -> gui.openBanDetail(viewer, uuid, name, returnPage));
            return;
        }
        if (slot == ConfigGUI.SLOT_BACK) {
            openLater(() -> gui.openMain(viewer));
        } else if (slot == ConfigGUI.SLOT_PREV && menu.getPage() > 0) {
            int page = menu.getPage() - 1;
            openLater(() -> gui.openBanList(viewer, page));
        } else if (slot == ConfigGUI.SLOT_NEXT) {
            int page = menu.getPage() + 1;
            openLater(() -> gui.openBanList(viewer, page));
        } else if (slot == ConfigGUI.SLOT_CLOSE_LIST) {
            viewer.closeInventory();
        }
    }

    /** 第 6 层：封禁详情 / 解封 */
    private void handleBanDetail(Player viewer, ConfigGUI.ConfigMenu menu, int slot) {
        UUID uuid = menu.getTargetUuid();
        String name = menu.getTargetName();
        int returnPage = menu.getReturnPage();

        if (slot == ConfigGUI.SLOT_BACK) {
            openLater(() -> gui.openBanList(viewer, returnPage));
            return;
        }
        if (slot == ConfigGUI.SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }
        if (slot == ConfigGUI.BAN_DETAIL_UNBAN) {
            if (!requirePermission(viewer, "anticheat.unban")) {
                return;
            }
            unban(viewer, uuid, name, returnPage);
        }
    }

    // ================================================================
    // 动作
    // ================================================================

    private void startCaptcha(Player viewer, Player target, String name, boolean forceRestart) {
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(PREFIX + "§c玩家 §e" + name + " §c已离线，无法发起查证。");
            return;
        }
        CaptchaManager captchaManager = plugin.getCaptchaManager();
        if (captchaManager == null) {
            viewer.sendMessage(PREFIX + "§c验证码模块未启用。");
            return;
        }
        if (!forceRestart && captchaManager.isInCaptcha(target)) {
            viewer.sendMessage(PREFIX + "§e玩家 §f" + name + " §e正在查证中，如需重开请使用「发送验证码测试」。");
            return;
        }
        if (forceRestart && captchaManager.isInCaptcha(target)) {
            captchaManager.removeSession(target.getUniqueId());
        }
        try {
            captchaManager.startCaptcha(target, CaptchaManager.Initiator.ADMIN);
            viewer.sendMessage(PREFIX + (forceRestart ? "§a已强制重开验证码测试：§f" : "§a已向 §f")
                    + name + " §a发起查证。");
            viewer.closeInventory();
        } catch (Throwable t) {
            viewer.sendMessage(PREFIX + "§c发起查证失败：" + t.getMessage());
        }
    }

    private void teleport(Player viewer, Player target, String name) {
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(PREFIX + "§c玩家 §e" + name + " §c已离线，无法传送。");
            return;
        }
        try {
            viewer.teleport(target);
            viewer.sendMessage(PREFIX + "§a已传送至 §f" + name + " §a身边。");
            viewer.closeInventory();
        } catch (Throwable t) {
            viewer.sendMessage(PREFIX + "§c传送失败：" + t.getMessage());
        }
    }

    private void toggleWhitelist(Player viewer, UUID uuid, String name, int returnPage) {
        WhitelistManager whitelistManager = plugin.getWhitelistManager();
        if (whitelistManager == null) {
            viewer.sendMessage(PREFIX + "§c白名单模块未启用。");
            return;
        }
        boolean whitelisted = whitelistManager.isWhitelisted(uuid);
        if (whitelisted) {
            whitelistManager.remove(uuid);
            viewer.sendMessage(PREFIX + "§e已将 §f" + name + " §e移出白名单，反作弊检测重新生效。");
        } else {
            boolean added = whitelistManager.add(uuid, name, viewer.getName());
            if (added) {
                viewer.sendMessage(PREFIX + "§a已将 §f" + name + " §a加入白名单，该玩家不再被反作弊封禁。");
                Player target = Bukkit.getPlayer(uuid);
                if (target != null) {
                    target.sendMessage(PREFIX + "§a你已被管理员加入服务器可信白名单。");
                }
            } else {
                viewer.sendMessage(PREFIX + "§e该玩家已在白名单中。");
            }
        }
        openLater(() -> gui.openPlayerDetail(viewer, uuid, name, returnPage));
    }

    private void applyPunishment(Player viewer, UUID uuid, String name, ConfigGUI.BanOption option) {
        if (!requirePermission(viewer, "anticheat.ban")) {
            return;
        }
        Player target = uuid == null ? null : Bukkit.getPlayer(uuid);

        // 仅踢出：不写入封禁名单
        if (option.isKickOnly()) {
            if (target == null || !target.isOnline()) {
                viewer.sendMessage(PREFIX + "§c玩家 §e" + name + " §c已离线，无需踢出。");
                return;
            }
            String kickReason = plugin.getConfig().getString("gui.kick-reason", "§c你已被管理员移出服务器");
            try {
                CompatManager.getChatCompat().kickPlayer(target, kickReason);
                viewer.sendMessage(PREFIX + "§a已将 §f" + name + " §a踢出服务器。");
                viewer.closeInventory();
            } catch (Throwable t) {
                // 兼容层失败时退回原生 API
                try {
                    target.kickPlayer(kickReason);
                    viewer.sendMessage(PREFIX + "§a已将 §f" + name + " §a踢出服务器。");
                    viewer.closeInventory();
                } catch (Throwable t2) {
                    viewer.sendMessage(PREFIX + "§c踢出失败：" + t2.getMessage());
                }
            }
            return;
        }

        String reason = plugin.getConfig().getString("gui.ban-reason", "管理员通过管理界面封禁");
        try {
            if (uuid != null) {
                plugin.getBanManager().banPlayer(uuid, name, option.duration, reason);
            } else {
                plugin.getBanManager().banPlayer(name, option.duration, reason);
            }
        } catch (Throwable t) {
            viewer.sendMessage(PREFIX + "§c封禁失败：" + t.getMessage());
            plugin.getLogger().warning("[GUI] 封禁 " + name + " 失败: " + t);
            return;
        }

        if (target != null && target.isOnline()) {
            try {
                plugin.getProfileManager().recordViolation(target, "Admin Ban (GUI)", 100,
                        option.duration, viewer.getName());
            } catch (Throwable ignored) {
                // 违规流水写入失败不影响封禁结果
            }
        }

        viewer.closeInventory();
        viewer.sendMessage(PREFIX + "§c已封禁 §f" + name + " §c时长：§f"
                + ConfigGUI.labelOfDuration(option.duration) + "§c，原因：§f" + reason);
    }

    private void unban(Player viewer, UUID uuid, String name, int returnPage) {
        try {
            if (uuid != null) {
                plugin.getBanManager().unbanPlayer(uuid);
            } else {
                plugin.getBanManager().unbanPlayer(name);
            }
            viewer.sendMessage(PREFIX + "§a已解除对 §f" + name + " §a的封禁。");
        } catch (Throwable t) {
            viewer.sendMessage(PREFIX + "§c解封失败：" + t.getMessage());
            return;
        }
        openLater(() -> gui.openBanList(viewer, returnPage));
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 内容区槽位 → 页内序号；非内容区返回 -1 */
    private int contentIndex(int slot) {
        for (int i = 0; i < ConfigGUI.CONTENT_SLOTS.length; i++) {
            if (ConfigGUI.CONTENT_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private boolean requirePermission(Player viewer, String permission) {
        if (viewer.hasPermission("anticheat.admin") || viewer.hasPermission(permission)) {
            return true;
        }
        viewer.sendMessage(PREFIX + "§c你没有权限执行该操作（§7" + permission + "§c）。");
        return false;
    }

    /** 延后 1 tick 打开下一层界面，避免点击事件栈内切换被客户端忽略 */
    private void openLater(Runnable action) {
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (Throwable t) {
            action.run();
        }
    }
}
