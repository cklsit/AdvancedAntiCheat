package com.anticheat.detection.mining;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiningDetectionModule —— 挖掘与建筑类补充检测模块（监听器）。
 *
 * <p>在既有 FastBreakDetection / ScaffoldDetection 基础上，补齐：</p>
 * <ul>
 *   <li><b>破坏曲线一致性</b>：连续破坏方块耗时分布极窄（标准差过小）判定脚本化挖掘。</li>
 *   <li><b>挖掘与移动协调</b>：跳跃/受击/疾跑时仍保持完美挖掘节奏判定自动化。</li>
 *   <li><b>非法放置检测</b>：放置位置超出交互距离 / 穿过实体或方块。</li>
 * </ul>
 */
public class MiningDetectionModule implements Listener {

    private final AdvancedAntiCheat plugin;

    private final Map<UUID, Deque<Long>> breakTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, BreakContext> breakContexts = new ConcurrentHashMap<>();
    /** 本次挖掘的开始时刻与方块坐标，用于向画像回报单次破坏耗时。 */
    private final Map<UUID, BreakStart> breakStarts = new ConcurrentHashMap<>();

    private static final int MAX_BREAK_HISTORY = 100;
    private static final double BREAK_CV_THRESHOLD = 0.12;   // 破坏间隔变异系数阈值
    private static final int MIN_BREAK_SAMPLES = 12;          // 判定所需最小破坏样本
    private static final double MAX_PLACE_DISTANCE = 6.5;     // 最大合法放置距离
    private static final long MAX_BREAK_DURATION = 60000L;    // 单次破坏耗时上界，超出视为跨方块脏数据

    public MiningDetectionModule(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------------- 破坏曲线一致性 / 挖掘移动协调 ----------------

    /**
     * 记录挖掘起点。BlockDamageEvent 在 1.8 / 1.21 均存在。
     * 直接覆盖而非判空写入：玩家中途换方块时，旧起点即作废。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamageStart(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Block block = event.getBlock();
        breakStarts.put(player.getUniqueId(),
            new BreakStart(System.currentTimeMillis(), block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        reportBreakDuration(player, event.getBlock(), now);

        Deque<Long> times = breakTimestamps.computeIfAbsent(uuid, k -> new LinkedList<>());
        times.addLast(now);
        while (times.size() > MAX_BREAK_HISTORY) times.removeFirst();

        analyzeBreakConsistency(player, times);
        analyzeMiningMovement(player, event.getBlock());
    }

    /**
     * 起点方块与被破坏方块一致才认定是同一轮挖掘，否则丢弃（中途切方块 / 没有起点事件）。
     */
    private void reportBreakDuration(Player player, Block block, long breakTime) {
        UUID uuid = player.getUniqueId();
        BreakStart start = breakStarts.remove(uuid);
        if (start == null || start.x != block.getX() || start.y != block.getY() || start.z != block.getZ()) {
            return;
        }

        long duration = breakTime - start.time;
        if (duration <= 0 || duration > MAX_BREAK_DURATION) return;

        plugin.getBehaviorTracker().recordBlockBreak(uuid, duration, block.getType().name());
    }

    private static final class BreakStart {
        final long time;
        final int x;
        final int y;
        final int z;

        BreakStart(long time, int x, int y, int z) {
            this.time = time;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private void analyzeBreakConsistency(Player player, Deque<Long> times) {
        if (times.size() < MIN_BREAK_SAMPLES) return;
        List<Long> intervals = new ArrayList<>();
        Iterator<Long> it = times.iterator();
        long prev = it.next();
        while (it.hasNext()) {
            long cur = it.next();
            long interval = cur - prev;
            if (interval > 0 && interval < 5000) intervals.add(interval);
            prev = cur;
        }
        if (intervals.size() < MIN_BREAK_SAMPLES - 1) return;

        double mean = mean(intervals);
        double stdDev = stdDev(intervals, mean);
        double cv = mean > 0 ? stdDev / mean : Double.MAX_VALUE;

        // 破坏曲线一致：耗时分布极窄
        if (cv < BREAK_CV_THRESHOLD) {
            record(player, ViolationRecord.ViolationType.BREAK_CONSISTENCY,
                String.format("破坏间隔过于恒定（变异系数=%.4f，均值=%.0fms）", cv, mean), 0.75);
        }
    }

    private void analyzeMiningMovement(Player player, Block block) {
        UUID uuid = player.getUniqueId();
        BreakContext ctx = breakContexts.computeIfAbsent(uuid, k -> new BreakContext());
        long now = System.currentTimeMillis();

        boolean airborne = !player.isOnGround();
        boolean sprinting = player.isSprinting();

        if (airborne || sprinting) {
            ctx.activeMoves++;
        }

        if (ctx.lastBreakTime != 0 && now - ctx.lastBreakTime < 250 && (airborne || sprinting)) {
            ctx.rapidWhileMoving++;
        }

        ctx.lastBreakTime = now;

        // 移动/跳跃状态下仍保持极高频且恒定节奏的挖掘 → 自动化
        if (ctx.rapidWhileMoving >= 8 && ctx.activeMoves >= 20) {
            record(player, ViolationRecord.ViolationType.MINING_COORD,
                String.format("跳跃/疾跑状态下持续保持挖掘节奏（移动中高频破坏=%d次）", ctx.rapidWhileMoving), 0.7);
            ctx.rapidWhileMoving = 0;
            ctx.activeMoves = 0;
        }
    }

    // ---------------- 非法放置检测 ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        Location placed = event.getBlock().getLocation();
        Location eye = player.getEyeLocation();

        double distance = eye.distance(placed);
        if (distance > MAX_PLACE_DISTANCE) {
            event.setCancelled(true);
            record(player, ViolationRecord.ViolationType.ILLEGAL_PLACE,
                String.format("放置距离超限（%.2f格 > %.1f格）", distance, MAX_PLACE_DISTANCE), 0.8);
            return;
        }

        // 放置在视线不可达位置（穿墙放置）
        if (!hasLineOfSight(eye, placed)) {
            event.setCancelled(true);
            record(player, ViolationRecord.ViolationType.ILLEGAL_PLACE,
                "穿墙放置方块（视线不可达）", 0.85);
        }
    }

    private boolean hasLineOfSight(Location eye, Location target) {
        org.bukkit.util.Vector dir = target.toVector().subtract(eye.toVector());
        double distance = dir.length();
        org.bukkit.util.Vector step = dir.normalize().multiply(0.25);
        Location cursor = eye.clone();
        for (double d = 0; d < distance; d += 0.25) {
            cursor.add(step);
            Block b = cursor.getBlock();
            if (b.getType().isSolid() && !VersionUtil.safeIsPassable(b)) {
                // 到达目标方块自身时视为可达
                if (cursor.getBlockX() == target.getBlockX() &&
                    cursor.getBlockY() == target.getBlockY() &&
                    cursor.getBlockZ() == target.getBlockZ()) {
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    // ---------------- 工具 ----------------

    private boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("anticheat.bypass")) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        return false;
    }

    private void record(Player player, ViolationRecord.ViolationType type, String details, double level) {
        plugin.getDetectionManager().getViolationManager().recordViolation(player, type, details, level);
    }

    private double mean(List<Long> values) {
        if (values.isEmpty()) return 0;
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.size();
    }

    private double stdDev(List<Long> values, double mean) {
        if (values.size() < 2) return 0;
        double sum = 0;
        for (long v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    public void clearPlayerData(UUID uuid) {
        breakTimestamps.remove(uuid);
        breakContexts.remove(uuid);
    }

    private static class BreakContext {
        long lastBreakTime = 0;
        int rapidWhileMoving = 0;
        int activeMoves = 0;
    }
}
