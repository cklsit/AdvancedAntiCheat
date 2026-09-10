package com.anticheat.detection.network;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClockDriftDetector —— 时钟漂移 / Timer 变速检测（监听器）。
 *
 * <p>以玩家移动包（{@link PlayerMoveEvent}）到达频率作为客户端时钟的采样源：
 * 正常 20 TPS 服务器下，移动包平均间隔约 50ms；Timer/变速齿轮会让客户端
 * 以更高频率发包，导致平均间隔显著偏低且过于稳定，据此判定时钟漂移。</p>
 */
public class ClockDriftDetector implements Listener {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, Deque<Long>> packetTimes = new ConcurrentHashMap<>();

    private static final int WINDOW_SIZE = 40;
    private static final double NORMAL_INTERVAL_MS = 50.0;   // 20 TPS 理论间隔
    private static final double DRIFT_RATIO = 0.72;           // 平均间隔低于理论值 72% 判定漂移
    private static final double CV_THRESHOLD = 0.25;          // 过于稳定（变异系数低）

    public ClockDriftDetector(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        // 仅统计产生实际位移的移动包，排除原地旋转噪声
        if (event.getFrom().distanceSquared(event.getTo()) < 1e-8) return;

        long now = System.currentTimeMillis();
        Deque<Long> times = packetTimes.computeIfAbsent(player.getUniqueId(), k -> new LinkedList<>());
        times.addLast(now);
        while (times.size() > WINDOW_SIZE) times.removeFirst();

        analyzeClockDrift(player, times);
    }

    private void analyzeClockDrift(Player player, Deque<Long> times) {
        if (times.size() < WINDOW_SIZE) return;

        List<Long> intervals = new ArrayList<>();
        Iterator<Long> it = times.iterator();
        long prev = it.next();
        while (it.hasNext()) {
            long cur = it.next();
            long interval = cur - prev;
            if (interval > 0 && interval < 2000) intervals.add(interval);
            prev = cur;
        }
        if (intervals.size() < 20) return;

        double mean = mean(intervals);
        double stdDev = stdDev(intervals, mean);
        double cv = mean > 0 ? stdDev / mean : Double.MAX_VALUE;

        // 平均间隔显著低于理论值 → 客户端时钟加速
        if (mean < NORMAL_INTERVAL_MS * DRIFT_RATIO && cv < CV_THRESHOLD) {
            double drift = 1.0 - (mean / NORMAL_INTERVAL_MS);
            record(player, ViolationRecord.ViolationType.CLOCK_DRIFT,
                String.format("客户端时钟漂移（平均间隔=%.1fms，漂移=%.0f%%）", mean, drift * 100), 0.75);
        }
    }

    private boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("anticheat.bypass")) return true;
        return false;
    }

    private void record(Player player, ViolationRecord.ViolationType type, String details, double level) {
        plugin.getDetectionManager().getViolationManager().recordViolation(player, type, details, level);
    }

    private double mean(List<Long> values) {
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.size();
    }

    private double stdDev(List<Long> values, double mean) {
        double sum = 0;
        for (long v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    public void clearPlayerData(UUID uuid) {
        packetTimes.remove(uuid);
    }
}
