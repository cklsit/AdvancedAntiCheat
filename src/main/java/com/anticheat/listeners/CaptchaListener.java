package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.captcha.tasks.TypeA_DirectInteraction;
import com.anticheat.captcha.tasks.TypeB_MotionMimicry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public class CaptchaListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public CaptchaListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    /**
     * 新玩家自动验证码 (修复 /captcha toggle 问题)。
     *
     * <p>历史 Bug：原代码对所有进入玩家无条件 startCaptcha(NEW_PLAYER)，导致老玩家也被：
     * 瞬间传验证码区 → 外部 spawn-teleport / Essentials 传回主世界 → session 残留 → 超时踢人。
     *
     * <p>三重修复 (与 startCaptcha 内部守卫形成双保险)：
     * <ol>
     *   <li>只对 !hasPlayedBefore()（真·第一次加入的新玩家）触发 NEW_PLAYER 验证码</li>
     *   <li>延迟 1 tick（MONITOR 优先级 + runTaskLater=1L）：等 Join 阶段所有 spawn-teleport / 权限 /
     *       ban-kick / authme teleport 全部结束，再启动验证码，避免与其它插件的 Join 传送竞争</li>
     *   <li>1 tick 后再次二次校验：在线、未被 ban、不在 captcha（admin 已手动）、不在查端——任一不满足则跳过</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isNewPlayerCaptchaEnabled()) {
            return;
        }
        // 核心过滤：只有"从未加入过服务器"的新玩家才走自动验证码。老玩家由管理员手动或检测触发。
        if (player.hasPlayedBefore()) {
            return;
        }

        final UUID uid = player.getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uid);
                if (p == null || !p.isOnline()) return;

                // 二次校验：1 tick 内可能被 ban/kick / 管理员手动 / 查端系统启动
                if (plugin.getBanManager() != null && plugin.getBanManager().isBanned(uid)) {
                    return;
                }
                if (plugin.getCaptchaManager().isInCaptcha(p)) {
                    return;
                }
                if (plugin.getCheckClientManager() != null && plugin.getCheckClientManager().isBeingChecked(uid)) {
                    return;
                }
                plugin.getCaptchaManager().startCaptcha(p, CaptchaManager.Initiator.NEW_PLAYER);
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getCaptchaManager().onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.2f);
        player.setAllowFlight(false);
        player.setFlying(false);

        // 动作模仿任务需要玩家的姿态变化（转向 / 起跳 / 位移 / 冲刺状态）来还原动作序列
        CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
        if (session != null && session.getCurrentTask() instanceof TypeB_MotionMimicry) {
            ((TypeB_MotionMimicry) session.getCurrentTask()).onPlayerMove(event);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        CaptchaManager.CaptchaSession session = plugin.getCaptchaManager().getSession(player);
        if (session != null) {
            Object currentTask = session.getCurrentTask();
            if (currentTask instanceof TypeA_DirectInteraction) {
                ((TypeA_DirectInteraction) currentTask).onPlayerSneak(event);
            } else if (currentTask instanceof TypeB_MotionMimicry) {
                ((TypeB_MotionMimicry) currentTask).onPlayerToggleSneak(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getCaptchaManager().isInCaptcha(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (plugin.getCaptchaManager().isInCaptcha(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();

            if (plugin.getCaptchaManager().isInCaptcha(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        if (plugin.getCaptchaManager().isInCaptcha(player)) {
            event.setCancelled(true);
            player.setFlying(false);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            if (plugin.getCaptchaManager().isInCaptcha(player)) {
                event.setCancelled(true);
            }
        }
    }
}
