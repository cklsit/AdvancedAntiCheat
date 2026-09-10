package com.anticheat.detection.behavior;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeystrokeDynamics —— 操作生物特征检测（按键/鼠标/交互节律）。
 *
 * <p>对应《全项检测规范》第八大类「操作生物特征（宏/脚本）」：人类操作天然携带随机抖动，
 * 而宏/脚本产生的交互节律极度稳定。通过统计三类交互的间隔序列并计算变异系数（CV），
 * 捕捉机械化的操作模式。</p>
 *
 * <ul>
 *   <li><b>点击节律</b>：左右键交互间隔过于恒定 → 连点器/自动交互。</li>
 *   <li><b>视角运动学</b>：水平视角（yaw）变化过于平滑 → 自瞄类脚本。</li>
 *   <li><b>热键切换</b>：快捷栏切换间隔恒定 → 宏。</li>
 * </ul>
 */
public class KeystrokeDynamics implements Listener {

    private final AdvancedAntiCheat plugin;

    private final Map<UUID, Deque<Long>> clickTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> slotSwitchTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Double>> yawDeltas = new ConcurrentHashMap<>();

    private static final int WINDOW = 40;
    private static final double CLICK_CV_THRESHOLD = 0.18;
    private static final double SLOT_CV_THRESHOLD = 0.20;
    private static final double YAW_SMOOTH_STD = 0.08; // 视角变化标准差过低 → 过度平滑

    public KeystrokeDynamics(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------------- 点击节律 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        Deque<Long> times = clickTimes.computeIfAbsent(player.getUniqueId(), k -> new LinkedList<>());
        times.addLast(System.currentTimeMillis());
        while (times.size() > WINDOW) times.removeFirst();
        analyzeClickRhythm(player, times);
    }

    private void analyzeClickRhythm(Player player, Deque<Long> times) {
        if (times.size() < 15) return;
        double cv = intervalCV(times, 2000);
        if (cv < CLICK_CV_THRESHOLD) {
            record(player, String.format("点击间隔过于恒定（变异系数=%.3f，疑似连点/宏）", cv), 0.65);
        }
    }

    // ---------------- 热键切换节律 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotSwitch(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        Deque<Long> times = slotSwitchTimes.computeIfAbsent(player.getUniqueId(), k -> new LinkedList<>());
        times.addLast(System.currentTimeMillis());
        while (times.size() > WINDOW) times.removeFirst();
        analyzeSlotRhythm(player, times);
    }

    private void analyzeSlotRhythm(Player player, Deque<Long> times) {
        if (times.size() < 15) return;
        double cv = intervalCV(times, 3000);
        if (cv < SLOT_CV_THRESHOLD) {
            record(player, String.format("快捷栏切换过于恒定（变异系数=%.3f，疑似宏）", cv), 0.6);
        }
    }

    // ---------------- 视角运动学 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        float fromYaw = event.getFrom().getYaw();
        float toYaw = event.getTo().getYaw();
        double delta = normalizeYaw(toYaw - fromYaw);
        if (Math.abs(delta) < 1e-6) return; // 忽略纯位移

        Deque<Double> deltas = yawDeltas.computeIfAbsent(player.getUniqueId(), k -> new LinkedList<>());
        deltas.addLast(delta);
        while (deltas.size() > WINDOW) deltas.removeFirst();
        analyzeYawMotion(player, deltas);
    }

    private void analyzeYawMotion(Player player, Deque<Double> deltas) {
        if (deltas.size() < 20) return;
        double mean = 0;
        for (double d : deltas) mean += d;
        mean /= deltas.size();
        double var = 0;
        for (double d : deltas) var += (d - mean) * (d - mean);
        var /= Math.max(1, deltas.size() - 1);
        double std = Math.sqrt(var);

        // 视角持续平滑且均值非零 → 机械锁定
        if (std < YAW_SMOOTH_STD && Math.abs(mean) > 0.01) {
            record(player, String.format("视角变化过度平滑（标准差=%.4f，均值=%.3f，疑似自瞄/脚本）", std, mean), 0.7);
        }
    }

    // ---------------- 工具 ----------------

    private double intervalCV(Deque<Long> times, long maxInterval) {
        List<Long> intervals = new ArrayList<>();
        Iterator<Long> it = times.iterator();
        long prev = it.next();
        while (it.hasNext()) {
            long cur = it.next();
            long interval = cur - prev;
            if (interval > 0 && interval < maxInterval) intervals.add(interval);
            prev = cur;
        }
        if (intervals.size() < 10) return Double.MAX_VALUE;
        double mean = 0;
        for (long v : intervals) mean += v;
        mean /= intervals.size();
        double var = 0;
        for (long v : intervals) var += (v - mean) * (v - mean);
        var /= Math.max(1, intervals.size() - 1);
        return mean > 0 ? Math.sqrt(var) / mean : Double.MAX_VALUE;
    }

    private double normalizeYaw(double delta) {
        while (delta > 180) delta -= 360;
        while (delta < -180) delta += 360;
        return delta;
    }

    private boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("anticheat.bypass")) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        return false;
    }

    private void record(Player player, String details, double level) {
        plugin.getDetectionManager().getViolationManager().recordViolation(
            player, ViolationRecord.ViolationType.KEYSTROKE, details, level);
    }

    public void clearPlayerData(UUID uuid) {
        clickTimes.remove(uuid);
        slotSwitchTimes.remove(uuid);
        yawDeltas.remove(uuid);
    }
}
