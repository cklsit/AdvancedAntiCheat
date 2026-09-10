package com.anticheat.replay;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 观察者摄像机绑定器（需求 1：观察者进入旁观者模式；方案 A：违规时自动切过肩）。
 *
 * <p>观察者始终处于 {@link GameMode#SPECTATOR}。该模式下 Minecraft 不渲染原生 HUD
 * （血条/饥饿/经验/热栏全部隐藏），因此：</p>
 * <ul>
 *   <li>不存在"画面 HUD"与"合成层 HUD"双份冲突；</li>
 *   <li>不会显示观察者自身的满血状态而误导取证；</li>
 *   <li>无需把生命/饥饿/经验/背包同步给观察者（状态镜像已取消）；</li>
 *   <li>HUD 完全由合成层渲染，数据取自目标玩家的遥测。</li>
 * </ul>
 *
 * <p>{@link CameraMode#ATTACH} 通过 {@code setSpectatorTarget} 由客户端自行跟随目标实体，
 * 服务端每 tick 零开销且无传送抖动；{@link CameraMode#SHOULDER} 才需要每 tick 传送。</p>
 *
 * <p><b>线程约束</b>：所有方法均操作 Bukkit 实体，必须在主线程调用。</p>
 */
public class CameraBinder {

    private final AdvancedAntiCheat plugin;
    private final ReplaySettings settings;

    /** key = 观察者 UUID */
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    /** key = 目标 UUID，value = SHOULDER 机位的每 tick 传送任务 */
    private final Map<UUID, BukkitTask> shoulderTasks = new ConcurrentHashMap<>();
    /** key = 目标 UUID，value = 取证保持结束后回落 ATTACH 的倒计时任务 */
    private final Map<UUID, BukkitTask> holdTasks = new ConcurrentHashMap<>();
    /** key = 目标 UUID，value = 当前生效机位 */
    private final Map<UUID, CameraMode> activeModes = new ConcurrentHashMap<>();

    /**
     * key = 目标 UUID，value = 机位时间段（归档 manifest 的 video.cameraSegments）。
     *
     * <p>{@code ATTACH} 段画面<b>不含</b>手持物品（原生 MC 限制），必须在归档里如实记录，
     * 避免出现"证据里看不到手持物品却无人知晓"的情况（设计文档 §8.4 红线 4）。</p>
     */
    private final Map<UUID, List<CameraSegment>> segments = new ConcurrentHashMap<>();
    /** key = 目标 UUID，value = 本段录像的起始墙钟（cameraSegments 的 tMs 基准） */
    private final Map<UUID, Long> segmentStartMs = new ConcurrentHashMap<>();

    public CameraBinder(AdvancedAntiCheat plugin, ReplaySettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    // ===================== 绑定 / 解绑 =====================

    /**
     * 把观察者以当前配置的机位绑定到目标。
     *
     * @return 是否绑定成功（观察者或目标离线时返回 false）
     */
    public boolean bind(Player observer, Player target) {
        if (observer == null || target == null || !observer.isOnline() || !target.isOnline()) {
            return false;
        }
        ensureSpectator(observer);

        bindings.put(observer.getUniqueId(),
                new Binding(observer.getUniqueId(), observer.getName(), target.getUniqueId()));

        // 新一段录像开始：重置机位时间线（tMs 相对本段起点）
        segments.put(target.getUniqueId(), new CopyOnWriteArrayList<>());
        segmentStartMs.put(target.getUniqueId(), System.currentTimeMillis());

        // ★ 绑定前预热目标周围区块，避免客户端来不及加载出现"世界空洞"（§4.4.3）
        prewarmChunks(target);

        applyMode(observer, target, settings.getCameraMode());
        return true;
    }

    /**
     * 预热目标周围区块。
     *
     * <p>观察者客户端被跟随/传送时，快速移动会导致区块来不及加载而出现空洞，
     * 直接破坏"世界完整"要求。绑定前把 {@code chunkPrewarmRadius} 半径内的区块
     * 主动载入，可显著降低首帧空洞概率。</p>
     */
    private void prewarmChunks(Player target) {
        int radius = settings.getChunkPrewarmRadius();
        if (radius <= 0) return;
        try {
            Location loc = target.getLocation();
            World world = loc.getWorld();
            if (world == null) return;
            Chunk center = loc.getChunk();
            int cx = center.getX();
            int cz = center.getZ();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final int tx = cx + dx;
                    final int tz = cz + dz;
                    try {
                        if (!world.isChunkLoaded(tx, tz)) {
                            world.loadChunk(tx, tz);
                        }
                    } catch (Throwable ignored) {
                        // 单个区块加载失败不影响整体，继续预热其余区块
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Camera] 区块预热失败: " + t.getMessage());
        }
    }

    /** 解除绑定并归还观察者（清除旁观目标、取消跟随任务）。 */
    public void unbind(Player observer) {
        if (observer == null) return;
        Binding b = bindings.remove(observer.getUniqueId());
        if (b != null) {
            cancelTasks(b.targetUuid);
        }
        clearSpectatorTarget(observer);
    }

    /** 目标下线时的清理（观察者可能已先一步解绑）。 */
    public void unbindTarget(UUID targetUuid) {
        if (targetUuid == null) return;
        cancelTasks(targetUuid);
        for (Map.Entry<UUID, Binding> e : bindings.entrySet()) {
            if (targetUuid.equals(e.getValue().targetUuid)) {
                Player observer = Bukkit.getPlayer(e.getKey());
                if (observer != null) clearSpectatorTarget(observer);
                bindings.remove(e.getKey());
                break;
            }
        }
    }

    // ===================== 机位切换 =====================

    /**
     * 切换指定目标的机位。
     *
     * @param permanent false 表示这是取证临时切换，会在 {@code autoShoulderHoldMs} 后自动回落
     */
    public boolean switchMode(UUID targetUuid, CameraMode mode, boolean permanent) {
        if (targetUuid == null || mode == null) return false;

        Binding binding = findBindingByTarget(targetUuid);
        if (binding == null) return false;

        Player observer = Bukkit.getPlayer(binding.observerUuid);
        Player target = Bukkit.getPlayer(targetUuid);
        if (observer == null || target == null || !observer.isOnline() || !target.isOnline()) {
            return false;
        }

        applyMode(observer, target, mode);

        if (!permanent && mode == CameraMode.SHOULDER) {
            scheduleReturnToAttach(targetUuid);
        }
        return true;
    }

    /**
     * 记录到违规时调用：按方案 A 自动切到过肩机位录下手持物品，保持一段时间后回落。
     *
     * @return 是否成功切换（未绑定或配置关闭时返回 false，调用方应据此上报降级事件）
     */
    public boolean triggerEvidenceHold(UUID targetUuid) {
        if (!settings.isAutoShoulderOnViolation()) return false;
        if (activeModes.get(targetUuid) == CameraMode.SHOULDER) {
            scheduleReturnToAttach(targetUuid); // 已在过肩，续期倒计时
            return true;
        }
        return switchMode(targetUuid, CameraMode.SHOULDER, false);
    }

    public CameraMode currentMode(UUID targetUuid) {
        CameraMode m = activeModes.get(targetUuid);
        return m != null ? m : settings.getCameraMode();
    }

    /** 目标当前是否被拍到手持物品。 */
    public boolean showsHeldItem(UUID targetUuid) {
        return currentMode(targetUuid).showsHeldItem();
    }

    /**
     * 本次录像是否至少有一段拍到了手持物品（归档 manifest 的 {@code video.showsHeldItem}）。
     *
     * <p>与 {@link #showsHeldItem(UUID)} 的区别：后者是"当前这一刻"，
     * 而取证充分性要看"整段录像里有没有录到过"，因此这里按机位时间线累计判断。</p>
     */
    public boolean recordedHeldItem(UUID targetUuid) {
        if (targetUuid == null) return false;
        List<CameraSegment> segs = segments.get(targetUuid);
        if (segs != null) {
            for (CameraSegment s : segs) {
                if (s.mode.showsHeldItem()) return true;
            }
        }
        return currentMode(targetUuid).showsHeldItem();
    }

    /**
     * 取出并可序列化为 {@code video.cameraSegments} 的机位时间线。
     *
     * @return 按 tMs 升序的 {@code [{tMs:number, mode:string}]}，无记录时返回空列表
     */
    public List<Map<String, Object>> getCameraSegments(UUID targetUuid) {
        List<CameraSegment> segs = (targetUuid == null) ? null : segments.get(targetUuid);
        if (segs == null || segs.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<>(segs.size());
        for (CameraSegment s : segs) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("tMs", s.tMs);
            m.put("mode", s.mode.name());
            out.add(m);
        }
        return out;
    }

    // ===================== 内部实现 =====================

    /** 记录一次机位切换（供归档标注哪几段录到了手持物品）。 */
    private void recordSegment(UUID targetUuid, CameraMode mode) {
        if (targetUuid == null || mode == null) return;
        List<CameraSegment> segs = segments.computeIfAbsent(targetUuid,
                k -> new CopyOnWriteArrayList<>());
        Long start = segmentStartMs.get(targetUuid);
        long tMs = start == null ? 0L : Math.max(0L, System.currentTimeMillis() - start);
        segs.add(new CameraSegment(tMs, mode));
    }

    private void applyMode(Player observer, Player target, CameraMode mode) {
        // 先清掉旧机位的所有副作用
        cancelTasks(target.getUniqueId());
        clearSpectatorTarget(observer);

        activeModes.put(target.getUniqueId(), mode);
        recordSegment(target.getUniqueId(), mode);

        switch (mode) {
            case ATTACH:
                if (!trySetSpectatorTarget(observer, target)) {
                    // 绑定失败（1.8 部分实现无此方法）→ 退回过肩机位，保证仍有画面
                    plugin.getLogger().warning("[Replay-Camera] setSpectatorTarget 不可用，回退 SHOULDER 机位");
                    activeModes.put(target.getUniqueId(), CameraMode.SHOULDER);
                    recordSegment(target.getUniqueId(), CameraMode.SHOULDER);
                    startShoulderFollow(observer, target);
                }
                break;
            case SHOULDER:
            default:
                startShoulderFollow(observer, target);
                break;
        }
    }

    private void startShoulderFollow(Player observer, Player target) {
        final UUID targetUuid = target.getUniqueId();
        final String observerName = observer.getName();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player o = Bukkit.getPlayerExact(observerName);
            Player t = Bukkit.getPlayer(targetUuid);
            if (o == null || t == null || !o.isOnline() || !t.isOnline()) return;
            if (!o.getGameMode().name().equalsIgnoreCase("SPECTATOR")) return;

            Location base = t.getLocation();
            double yaw = Math.toRadians(base.getYaw() + settings.getShoulderYawOffset());
            // 目标朝向的后方：forward = (-sin, 0, cos)，故 behind = (sin, 0, -cos)
            double dx = Math.sin(yaw) * settings.getShoulderDistance();
            double dz = -Math.cos(yaw) * settings.getShoulderDistance();

            Location cam = base.clone().add(dx, settings.getShoulderHeight(), dz);
            cam.setYaw(base.getYaw());
            cam.setPitch(base.getPitch());
            o.teleport(cam);
        }, 1L, 1L);

        shoulderTasks.put(targetUuid, task);
    }

    /** 取证保持结束后回落到默认机位。 */
    private void scheduleReturnToAttach(UUID targetUuid) {
        // 取消旧的回落倒计时（续期）
        BukkitTask old = holdTasks.remove(targetUuid);
        if (old != null) old.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            holdTasks.remove(targetUuid);
            if (activeModes.get(targetUuid) != CameraMode.SHOULDER) return;
            Binding b = findBindingByTarget(targetUuid);
            if (b == null) return;
            Player o = Bukkit.getPlayer(b.observerUuid);
            Player t = Bukkit.getPlayer(targetUuid);
            if (o == null || t == null || !o.isOnline() || !t.isOnline()) return;
            applyMode(o, t, CameraMode.ATTACH);
        }, Math.max(1L, settings.getAutoShoulderHoldMs() / 50L));

        holdTasks.put(targetUuid, task);
    }

    /** 取消目标相关的所有任务（肩部传送 + 回落倒计时），并清理机位记录。 */
    private void cancelTasks(UUID targetUuid) {
        BukkitTask s = shoulderTasks.remove(targetUuid);
        if (s != null) s.cancel();
        BukkitTask h = holdTasks.remove(targetUuid);
        if (h != null) h.cancel();
        activeModes.remove(targetUuid);
    }

    private Binding findBindingByTarget(UUID targetUuid) {
        for (Binding b : bindings.values()) {
            if (targetUuid.equals(b.targetUuid)) return b;
        }
        return null;
    }

    private void ensureSpectator(Player observer) {
        try {
            if (!observer.getGameMode().name().equalsIgnoreCase("SPECTATOR")) {
                observer.setGameMode(GameMode.SPECTATOR);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay-Camera] 无法切换到 SPECTATOR 模式: " + t.getMessage()
                    + "（当前=" + observer.getGameMode() + "）");
        }
    }

    /**
     * 绑定到目标眼部。1.8 起 Bukkit 提供 {@code Player#setSpectatorTarget}，
     * 但部分 1.8 分支实现缺失，故先直调、失败再反射，都失败则返回 false 由调用方降级。
     */
    private boolean trySetSpectatorTarget(Player observer, Player target) {
        try {
            observer.setSpectatorTarget(target);
            return true;
        } catch (Throwable direct) {
            try {
                observer.getClass()
                        .getMethod("setSpectatorTarget", org.bukkit.entity.Entity.class)
                        .invoke(observer, target);
                return true;
            } catch (Throwable reflected) {
                plugin.getLogger().warning("[Replay-Camera] setSpectatorTarget 失败: "
                        + reflected.getMessage());
                return false;
            }
        }
    }

    private void clearSpectatorTarget(Player observer) {
        try {
            observer.setSpectatorTarget(null);
        } catch (Throwable ignored) {
            // 部分实现不接受 null，忽略即可
        }
    }

    public void shutdown() {
        for (BukkitTask t : shoulderTasks.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        for (BukkitTask t : holdTasks.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        shoulderTasks.clear();
        holdTasks.clear();
        activeModes.clear();
        segments.clear();
        segmentStartMs.clear();
        for (Binding b : bindings.values()) {
            Player o = Bukkit.getPlayer(b.observerUuid);
            if (o != null) clearSpectatorTarget(o);
        }
        bindings.clear();
    }

    /** 观察者 ↔ 目标的一次绑定关系。 */
    private static final class Binding {
        final UUID observerUuid;
        final String observerName;
        final UUID targetUuid;

        Binding(UUID observerUuid, String observerName, UUID targetUuid) {
            this.observerUuid = observerUuid;
            this.observerName = observerName;
            this.targetUuid = targetUuid;
        }
    }

    /** 一段机位记录：{@code tMs} 相对本段录像起点的毫秒偏移。 */
    private static final class CameraSegment {
        final long tMs;
        final CameraMode mode;

        CameraSegment(long tMs, CameraMode mode) {
            this.tMs = tMs;
            this.mode = mode;
        }
    }
}
