package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.bounty.BountyBoard;
import com.anticheat.bounty.BountyManager;
import com.anticheat.bounty.BountySession;
import com.anticheat.bounty.BountyShop;
import com.anticheat.bounty.BountyWorld;
import com.anticheat.core.bounty.BountyHooks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 赏金沙箱的 Bukkit 事件面。
 *
 * <p>这个类只做三件事：把事件转发给 [BountyManager] / [BountySession]、
 * 保证沙箱的隔离性（命令 / 传送 / 聊天 / 掉落虚空），以及分发两个 GUI 的点击。
 * **所有判定逻辑都不在这里** —— 它们必须留在可离线单测的纯类里。</p>
 */
public class BountyListener implements Listener {

    /** 掉到平台以下多少格就送回出生点。 */
    private static final double VOID_RESCUE_Y = 0.0;

    private final AdvancedAntiCheat plugin;

    public BountyListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    private BountyManager manager() {
        return plugin.getBountyManager();
    }

    // ------------------------------------------------------------------ 生命周期

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        // 已购称号以权限附件的形式挂上（`bounty_purchase` 才是持久化的真相）
        BountyShop.applyTitles(plugin, manager(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        manager().onPlayerQuit(event.getPlayer());
    }

    // ------------------------------------------------------------------ 隔离

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!manager().isInBounty(player)) return;

        String message = event.getMessage();
        String command = message.startsWith("/") ? message.substring(1) : message;
        int space = command.indexOf(' ');
        if (space >= 0) command = command.substring(0, space);
        int colon = command.indexOf(':');
        if (colon >= 0) command = command.substring(colon + 1);   // 去掉命名空间前缀

        if (manager().isCommandAllowed(command)) return;

        event.setCancelled(true);
        player.sendMessage("§c沙箱内只能执行白名单命令（/bounty 可随时离开沙箱）");
    }

    /**
     * 阻止沙箱内玩家被传送到沙箱之外。
     *
     * <p>命令白名单挡不住插件传送，所以这里再兜一层。注意退出沙箱时
     * [BountySession#end] 会**先**解除沙箱标记再传送回来，所以本条不会拦自己的回程。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!BountyHooks.isSandbox(player.getUniqueId())) return;
        if (event.getTo() == null) return;
        BountyWorld world = manager().getBountyWorld();
        if (world.getWorld() == null) return;
        if (!world.getWorld().equals(event.getTo().getWorld())) {
            event.setCancelled(true);
            player.sendMessage("§c沙箱内无法传送到其它世界（/bounty leave 可离开沙箱）");
        }
    }

    /**
     * 聊天隔离：沙箱内玩家只看到沙箱内的消息。
     *
     * <p>做法是在**收件人集合**上做减集，而不是自己重发一遍：
     * 主世界玩家发言时把沙箱玩家摘掉，沙箱玩家发言时把收件人收窄成沙箱玩家。
     * 这样服务端已有的聊天格式/前缀插件依然生效，我们只改"谁能看到"。</p>
     *
     * <p>`AsyncPlayerChatEvent` 在新版本上已标记过时，但为了兼容 1.8 只能用它；
     * 整段包 try/catch，一旦平台不再触发该事件，退化成"隔离失效"而不是报错。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!manager().isChatIsolated()) return;
        try {
            Player speaker = event.getPlayer();
            boolean speakerInSandbox = manager().isInBounty(speaker);

            if (speakerInSandbox) {
                // 沙箱发言：收件人收窄成沙箱玩家，并打上标记让人一眼看出是沙箱消息
                List<Player> recipients = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (manager().isInBounty(online)) recipients.add(online);
                }
                event.getRecipients().clear();
                event.getRecipients().addAll(recipients);
                try {
                    event.setFormat("§8[沙箱] §7" + speaker.getName() + "§8: §f" + event.getMessage());
                } catch (Throwable ignored) {
                    // 格式设置不支持就只做收件人隔离
                }
            } else {
                // 主世界发言：沙箱玩家听不到
                event.getRecipients().removeIf(online -> manager().isInBounty(online));
            }
        } catch (Throwable ignored) {
            // 该平台/该版本不支持这套 API：降级为不隔离聊天
        }
    }

    // ------------------------------------------------------------------ 采样与目标

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        BountySession session = manager().getSession(player);
        if (session == null || event.getTo() == null) return;
        // 掉出平台 → 送回出生点。空岛沙箱没有地板，这是必然会发生的事，
        // 不兜住的话玩家会在"测试飞行失败"时被摔死，体验与数据都被污染。
        if (event.getTo().getY() < VOID_RESCUE_Y) {
            Location spawn = manager().getBountyWorld().getSpawnLocation();
            if (spawn != null) {
                player.teleport(spawn);
                player.setFallDistance(0f);
                player.sendMessage("§7已把你送回沙箱出生点");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        BountySession session = manager().getSession(event.getPlayer());
        if (session != null) session.noteAttack();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        BountySession session = manager().getSession((Player) event.getDamager());
        if (session != null) session.noteAttack();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        BountySession session = manager().getSession(killer);
        if (session != null) session.onKill(event.getEntity());
    }

    /** 沙箱内死亡不踢出：`keepInventory` 已开，且重生点就在沙箱里。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        BountySession session = manager().getSession(event.getEntity());
        if (session == null) return;
        session.getEvidence().event("[DEATH] " + (event.getDeathMessage() == null ? "unknown" : event.getDeathMessage()));
        event.getEntity().sendMessage("§7你在沙箱中死亡，已保留背包；可继续测试或 /bounty leave 退出");
    }

    // ------------------------------------------------------------------ GUI

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof BountyBoard) {
            event.setCancelled(true);
            BountyBoard.handleClick(plugin, manager(), player, (BountyBoard) holder, event.getRawSlot());
            return;
        }
        if (holder instanceof BountyShop) {
            event.setCancelled(true);
            BountyShop.handleClick(plugin, manager(), player, (BountyShop) holder, event.getRawSlot());
            return;
        }
        // 沙箱内的真实背包操作 → 计入 FAST_SWAP 目标
        BountySession session = manager().getSession(player);
        if (session != null) session.onInventoryClick();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof BountyBoard || holder instanceof BountyShop) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ 死亡后回程

    /**
     * 从沙箱死亡并退出后复活：把玩家送回进入沙箱前的位置。
     *
     * <p>死亡状态下 teleport 会被服务端取消，所以 [BountySession#end] 只能把目标位置
     * 暂存起来，由这里在下一次复活时补送。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Location backup = manager().pollPendingRespawnBackup(uuid);
        if (backup == null) return;

        if (backup.getWorld() != null) {
            event.setRespawnLocation(backup.getWorld().getSpawnLocation());
        }
        // 延迟 1 tick 再传送：1.8 在 Respawning 阶段直接 teleport 偶发不生效
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                try {
                    player.teleport(backup);
                    player.sendMessage("§a已将你送回进入赏金前的位置");
                } catch (Throwable t) {
                    plugin.getLogger().warning("[Bounty] 复活后回送失败: " + t.getMessage());
                    if (backup.getWorld() != null) player.teleport(backup.getWorld().getSpawnLocation());
                }
            }
        }.runTask(plugin);
    }

    /** 沙箱内掉落的物品直接消失（避免用掉落绕过"沙箱内所得全部销毁"）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (manager().isInBounty(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§7沙箱内不允许丢弃物品");
        }
    }
}
