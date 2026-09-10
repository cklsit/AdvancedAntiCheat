package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 观察者客户端跟随管理器。
 * 每 tick 将 ReplayObserver_* 玩家 teleport 到目标玩家位置，
 * 保证 WebReplay / 回放观察时视角与目标完全同步。
 */
public class ObserverFollowManager {

    private final AdvancedAntiCheat plugin;
    /** key: observer UUID, value: 正在运行的跟随任务 */
    private final Map<UUID, BukkitTask> followTasks = new HashMap<>();

    public ObserverFollowManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    /**
     * 让指定观察者客户端跟随目标玩家（每 tick teleport）。
     *
     * @param observerName 观察者名，如 ReplayObserver_1
     * @param targetUUID   目标玩家 UUID
     */
    public void startFollow(String observerName, UUID targetUUID) {
        if (observerName == null || targetUUID == null) {
            return;
        }
        Player observer = Bukkit.getPlayerExact(observerName);
        if (observer == null) {
            plugin.getLogger().warning("[Replay-Follow] 找不到观察者玩家: " + observerName);
            return;
        }
        UUID observerUUID = observer.getUniqueId();

        // 若已在跟随，先停掉旧 task
        BukkitTask existing = followTasks.get(observerUUID);
        if (existing != null) {
            try { existing.cancel(); } catch (Throwable ignored) {}
            followTasks.remove(observerUUID);
        }

        FollowRunnable runnable = new FollowRunnable(observer, targetUUID);
        // 每 tick 运行一次，延迟 1 tick
        BukkitTask task = runnable.runTaskTimer(plugin, 1L, 1L);
        followTasks.put(observerUUID, task);

        plugin.getLogger().info("[Replay-Follow] " + observerName + " 开始跟随目标 UUID=" + targetUUID);
    }

    /**
     * 停止指定观察者的跟随任务。
     */
    public void stopFollow(String observerName) {
        if (observerName == null) return;
        Player observer = Bukkit.getPlayerExact(observerName);
        UUID observerUUID;
        if (observer != null) {
            observerUUID = observer.getUniqueId();
        } else {
            // 玩家可能已离线，按名字找历史 UUID（fallback：遍历当前所有匹配的）
            observerUUID = null;
            Iterator<Map.Entry<UUID, BukkitTask>> it = followTasks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, BukkitTask> e = it.next();
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && observerName.equals(p.getName())) {
                    observerUUID = e.getKey();
                    break;
                }
            }
            if (observerUUID == null) return;
        }
        BukkitTask task = followTasks.remove(observerUUID);
        if (task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 判断观察者是否正在跟随。
     */
    public boolean isFollowing(String observerName) {
        if (observerName == null) return false;
        Player observer = Bukkit.getPlayerExact(observerName);
        if (observer == null) return false;
        return followTasks.containsKey(observer.getUniqueId());
    }

    /**
     * 插件关闭时 cancel 所有任务。
     */
    public void shutdown() {
        for (BukkitTask task : followTasks.values()) {
            try { task.cancel(); } catch (Throwable ignored) {}
        }
        followTasks.clear();
        plugin.getLogger().info("[Replay-Follow] ObserverFollowManager 已关闭，所有跟随任务已清理");
    }

    /**
     * 调试用：返回 observerName → targetName 的快照（找不到目标时返回 UUID 字符串）。
     */
    public Map<String, String> statusSnapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, BukkitTask> e : followTasks.entrySet()) {
            BukkitTask t = e.getValue();
            if (t == null) continue;
            Player obs = Bukkit.getPlayer(e.getKey());
            if (obs == null) continue;
            String targetName = "";
            // 内部类的 targetUUID 无法直接读；用 BukkitRunnable 的反射或记录辅助 map，
            // 这里额外维护一份观察者→目标映射
            UUID tid = followTargetMap.get(e.getKey());
            if (tid != null) {
                Player tp = Bukkit.getPlayer(tid);
                targetName = tp != null ? tp.getName() : tid.toString();
            }
            result.put(obs.getName(), targetName);
        }
        return result;
    }

    // 额外维护 observerUUID → targetUUID 供 statusSnapshot 读取
    private final Map<UUID, UUID> followTargetMap = new HashMap<>();

    /**
     * 实际每 tick 执行的跟随 Runnble。
     */
    private class FollowRunnable extends BukkitRunnable {

        private final WeakReference<Player> observerWeakRef;
        private final UUID targetUUID;
        private final UUID observerUUID;

        FollowRunnable(Player observer, UUID targetUUID) {
            this.observerWeakRef = new WeakReference<>(observer);
            this.targetUUID = targetUUID;
            this.observerUUID = observer.getUniqueId();
            followTargetMap.put(observerUUID, targetUUID);
        }

        @Override
        public void run() {
            Player observer = observerWeakRef.get();
            if (observer == null || !observer.isOnline()) {
                cancelSelf();
                return;
            }
            Player target = Bukkit.getPlayer(targetUUID);
            if (target == null || !target.isOnline()) {
                // 目标下线则停止跟随，等待外部 PoolManager 重分配
                cancelSelf();
                return;
            }

            try {
                Location targetLoc = target.getLocation();
                observer.teleport(targetLoc);
                // 强制同步视角（有的实现 teleport 后 yaw/pitch 不同步，再次调用 safeSetRotation）
                safeSetRotation(observer, targetLoc.getYaw(), targetLoc.getPitch(), target.getWorld());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "[Replay-Follow] teleport 失败 observer=" + observer.getName()
                                + " target=" + target.getName() + ": " + t.getMessage());
            }
        }

        private void cancelSelf() {
            try { this.cancel(); } catch (Throwable ignored) {}
            followTasks.remove(observerUUID);
            followTargetMap.remove(observerUUID);
        }
    }

    /**
     * 安全设置玩家视角。先尝试 Player.setRotation(float, float)（1.8.8 有），
     * 失败则 fallback 用带 yaw/pitch 的新 Location 再次 teleport 强制发包。
     */
    private void safeSetRotation(Player observer, float yaw, float pitch, World world) {
        try {
            Method setRotation = Player.class.getMethod("setRotation", float.class, float.class);
            setRotation.invoke(observer, yaw, pitch);
        } catch (Throwable t) {
            // fallback: 用 teleport(location with yaw/pitch)
            try {
                Location current = observer.getLocation();
                Location rotated = new Location(
                        world != null ? world : current.getWorld(),
                        current.getX(), current.getY(), current.getZ(),
                        yaw, pitch);
                observer.teleport(rotated);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * overload: 按目标玩家取 yaw/pitch
     */
    @SuppressWarnings("unused")
    private void safeSetRotation(Player observer, Player target) {
        if (target == null) return;
        Location l = target.getLocation();
        safeSetRotation(observer, l.getYaw(), l.getPitch(), target.getWorld());
    }
}
