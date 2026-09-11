package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ObserverFollowManager;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * ReplayObserver_* 客户端账号登录/登出处理。
 *
 * 跨版本兼容点：
 * 1) GameMode.SPECTATOR 仅在 1.9+ 存在，1.8.8 会 NoSuchFieldError → 用反射 try/catch，
 *    失败回退 GameMode.CREATIVE + setAllowFlight + setFlying + flySpeed。
 * 2) Player.setInvisible 仅高版本存在，1.8.8 无 → try/catch 忽略，等效通过
 *    遍历所有在线玩家调用 hidePlayer(observer) 老签名 + INVISIBILITY 药水双保险。
 * 3) hidePlayer(Player) 1.8.8 有老签名，高版本 deprecate 但仍保留；不要用
 *    hidePlayer(Plugin, Player) 新签名，避免 1.8.8 NoSuchMethodError。
 */
public class ReplayObserverLoginListener implements Listener {

    private static final String OBSERVER_PREFIX = "ReplayObserver_";
    private static final String PERM_OBSERVER = "anticheat.replay.observer";

    private final AdvancedAntiCheat plugin;
    private final ObserverFollowManager followManager;

    public ReplayObserverLoginListener(AdvancedAntiCheat plugin, ObserverFollowManager followManager) {
        this.plugin = plugin;
        this.followManager = followManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player observer = e.getPlayer();
        String name = observer.getName();
        if (name == null || !name.startsWith(OBSERVER_PREFIX)) {
            return;
        }

        Bukkit.getLogger().info("§b[AntiCheat] 观察者登录：" + name);
        plugin.getLogger().info("[Replay-Observer] 观察者登录: " + name
                + " UUID=" + observer.getUniqueId());

        // 先去掉 op，防止权限滥用
        try {
            if (observer.isOp()) {
                observer.setOp(false);
            }
        } catch (Throwable ignored) {}

        // 1) 游戏模式：优先 SPECTATOR，失败回退 CREATIVE + 飞行
        applyObserverGameMode(observer);

        // 2) 隐身：先尝试 setInvisible（高版本），失败忽略；后面用 hidePlayer 遍历 + 药水
        try {
            Method setInvisible = Player.class.getMethod("setInvisible", boolean.class);
            setInvisible.invoke(observer, true);
        } catch (Throwable ignored) {
            // 1.8.8 无此方法，走后续 fallback
        }

        // 3) 遍历在线玩家：对其他玩家隐藏观察者；观察者之间不互藏
        for (Player other : VersionUtil.safeGetOnlinePlayers()) {
            if (other == null) continue;
            if (other.equals(observer)) continue;
            String otherName = other.getName();
            if (otherName != null && otherName.startsWith(OBSERVER_PREFIX)) continue;

            try {
                // 老签名 hidePlayer(Player)，1.8.8 存在；高版本 deprecate 但仍然可用
                Method hidePlayer = Player.class.getMethod("hidePlayer", Player.class);
                hidePlayer.invoke(other, observer);
            } catch (Throwable t1) {
                // 有的 Paper 版本删除老签名，尝试 (Plugin, Player) 新签名（再不行就放弃）
                try {
                    Method hidePlayerNew = Player.class.getMethod("hidePlayer",
                            org.bukkit.plugin.Plugin.class, Player.class);
                    hidePlayerNew.invoke(other, plugin, observer);
                } catch (Throwable t2) {
                    // 两种签名都不可用，记 warning 然后忽略
                    plugin.getLogger().warning("[Replay-Observer] hidePlayer 失败: "
                            + t1.getMessage() + " / " + t2.getMessage());
                }
            }
        }

        // 4) 观察者账号权限 attachment（永久，3 arg）
        try {
            observer.addAttachment(plugin, PERM_OBSERVER, true);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Observer] addAttachment 失败: " + t.getMessage());
        }

        // 5) 隐身药水双保险（无限时间 Amplifier 0，不产生粒子效果 particle=false）
        try {
            PotionEffectType invis = PotionEffectType.INVISIBILITY;
            if (invis != null) {
                // 第 4 参 particle=false：不显示粒子（视觉效果更干净）；构造签名按 1.8.8
                PotionEffect effect = new PotionEffect(invis, Integer.MAX_VALUE, 0, false);
                observer.addPotionEffect(effect, true);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Observer] INVISIBILITY 药水失败: " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player observer = e.getPlayer();
        String name = observer.getName();
        if (name == null || !name.startsWith(OBSERVER_PREFIX)) {
            return;
        }
        plugin.getLogger().info("[Replay-Observer] 观察者下线: " + name
                + " 清理跟随 task");
        try {
            ObserverFollowManager fm = followManager != null ? followManager : plugin.getObserverFollowManager();
            if (fm != null) {
                fm.stopFollow(name);
            }
        } catch (Throwable ignored) {}

        // 关键：通知观察者池解除该 observer 的绑定。
        // 否则 busyObservers 里会留下一个「已下线」的 observer 占位，导致该目标的
        // 订阅者永远等不到新的 observer（acquire 会判定"已有绑定"直接复用），
        // 表现为直播画面永久定格在最后一帧 —— 观察者即使随后自行重连也不会恢复。
        try {
            com.anticheat.managers.ObserverPoolManager pool = plugin.getObserverPoolManager();
            if (pool != null) {
                pool.onObserverOffline(name);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Observer] 通知观察者池（下线）失败: " + t.getMessage());
        }
    }

    /**
     * 设置观察者游戏模式：优先 SPECTATOR（1.9+），失败回退 CREATIVE + 飞行。
     */
    private void applyObserverGameMode(Player observer) {
        // 尝试 SPECTATOR。注意：不能直接写 GameMode.SPECTATOR，1.8.8 下枚举不存在，
        // 会在类装载时 NoSuchFieldError；使用反射按字段名读取。
        GameMode spectator = null;
        try {
            Field f = GameMode.class.getField("SPECTATOR");
            Object val = f.get(null);
            if (val instanceof GameMode) {
                spectator = (GameMode) val;
            }
        } catch (Throwable ignored) {
            // 1.8.8 无 SPECTATOR，spectator 保持 null
        }

        if (spectator != null) {
            try {
                observer.setGameMode(spectator);
                // 成功则飞行速度也设正常
                try { observer.setFlySpeed(0.2f); } catch (Throwable ignored) {}
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("[Replay-Observer] setGameMode(SPECTATOR) 失败: "
                        + t.getMessage() + "，回退 CREATIVE + flight");
            }
        }

        // fallback: CREATIVE + 允许飞行 + 飞行中
        try {
            observer.setGameMode(GameMode.CREATIVE);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Observer] setGameMode(CREATIVE) 也失败: " + t.getMessage());
        }
        try {
            observer.setAllowFlight(true);
            observer.setFlying(true);
            observer.setFlySpeed(0.2f);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Observer] 飞行设置失败: " + t.getMessage());
        }
    }
}
