package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ViolationManager;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.managers.AuditManager;
import com.anticheat.utils.VersionUtil;
import com.anticheat.web.BukkitBridge;
import com.anticheat.web.auth.Permission;
import com.anticheat.web.dto.AlertDTO;
import com.anticheat.web.dto.ServerStatusDTO;
import com.anticheat.web.dto.StatsDTO;
import com.anticheat.web.ws.AlertBroadcaster;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard REST 端点：
 * <ul>
 *   <li>GET /api/dashboard/stats - 全量统计聚合</li>
 *   <li>GET /api/dashboard/alerts - 最近告警（来自 AlertBroadcaster 环形缓冲）</li>
 * </ul>
 */
public class DashboardHandler extends AbstractHandler {

    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final AlertBroadcaster broadcaster;

    public DashboardHandler(AdvancedAntiCheat plugin, AuditManager auditManager, AlertBroadcaster broadcaster) {
        super(plugin, auditManager);
        this.broadcaster = broadcaster;
    }

    public void register(Javalin app) {
        app.get("/api/dashboard/stats", this::stats);
        app.get("/api/dashboard/alerts", this::alerts);
    }

    private void stats(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.DASHBOARD_READ)) return;

        // 在主线程快照在线玩家（1.8 兼容：通过反射安全读取）
        List<Player> online = BukkitBridge.syncSupply(plugin, VersionUtil::safeGetOnlinePlayers);
        plugin.getLogger().info("[Web] /api/dashboard/stats safeGetOnlinePlayers returned " + online.size() + " players");

        ViolationManager vm = null;
        try {
            vm = plugin.getDetectionManager().getViolationManager();
        } catch (Throwable ignored) { /* keep null */ }

        // 风险分桶
        int[] bucket = new int[]{0, 0, 0, 0};
        int todayViolations = 0;
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long dayStartMillis = now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long dayEndMillis = dayStartMillis + 86_400_000L;

        // 每小时桶（24h）
        int[] hourlyViolations = new int[24];
        int[] hourlyBans = new int[24];
        // 模块触发统计
        Map<String, int[]> moduleCounts = new HashMap<>();

        for (Player p : online) {
            int score = computeScore(vm, p.getUniqueId());
            bucket[scoreToLevel(score)]++;
        }

        // 聚合违规历史
        Map<UUID, List<ViolationRecord>> all = snapshotHistory(vm);
        for (Map.Entry<UUID, List<ViolationRecord>> e : all.entrySet()) {
            for (ViolationRecord r : e.getValue()) {
                if (r.getTimestamp() >= dayStartMillis && r.getTimestamp() < dayEndMillis) {
                    todayViolations++;
                }
                ZonedDateTime zdt = Instant.ofEpochMilli(r.getTimestamp()).atZone(ZoneId.systemDefault());
                int hour = zdt.getHour();
                if (hour >= 0 && hour < 24) {
                    hourlyViolations[hour]++;
                }
                moduleCounts.computeIfAbsent(r.getType().name(), k -> new int[]{0})[0]++;
            }
        }

        StatsDTO stats = new StatsDTO();
        stats.onlinePlayers = online.size();
        stats.totalPlayers = Math.max(online.size(), all.size());
        stats.activeCases = countActiveCases(all);
        stats.todayViolations = todayViolations;
        stats.todayBans = countTodayBans();

        // 风险分布
        int totalBucket = Math.max(1, bucket[0] + bucket[1] + bucket[2] + bucket[3]);
        List<StatsDTO.RiskBucket> rb = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rb.add(new StatsDTO.RiskBucket(i, bucket[i], bucket[i] * 100.0 / totalBucket));
        }
        stats.riskDistribution = rb;

        // 模块触发：取 top 7
        List<Map.Entry<String, int[]>> sortedMods = new ArrayList<>(moduleCounts.entrySet());
        sortedMods.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        List<StatsDTO.ModuleTrigger> mt = new ArrayList<>();
        int limit = Math.min(7, sortedMods.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, int[]> en = sortedMods.get(i);
            // 趋势为 0 占位（无历史对比）
            mt.add(new StatsDTO.ModuleTrigger(en.getKey(), en.getValue()[0], 0.0));
        }
        stats.moduleTriggers = mt;

        // 每小时趋势
        List<StatsDTO.HourlyBucket> hb = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String label = String.format("%02d:00", i);
            hb.add(new StatsDTO.HourlyBucket(label, hourlyViolations[i], hourlyBans[i]));
        }
        stats.hourlyTrend = hb;

        // 服务器节点：单机模式仅返回当前服
        List<ServerStatusDTO> ss = new ArrayList<>();
        String serverName = plugin.getConfig().getString("database.server-name", "Server-1");
        String region = "本地";
        double tps = readTps();
        int memory = readMemoryPercent();
        String statusStr = tps >= 19.0 ? "healthy" : (tps >= 16.0 ? "warning" : "critical");
        ss.add(new ServerStatusDTO(serverName, serverName, online.size(), tps, memory, region, statusStr));
        stats.serverStatus = ss;

        ok(ctx, stats);
    }

    private void alerts(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.DASHBOARD_READ)) return;
        AlertDTO[] arr = broadcaster.recentAlerts();
        ok(ctx, arr == null ? new AlertDTO[0] : arr);
    }

    // ===== 辅助 =====

    private int computeScore(ViolationManager vm, UUID uuid) {
        if (vm == null) return 0;
        try {
            List<ViolationRecord> history = vm.getViolationHistory(uuid);
            int score = 0;
            if (history != null) {
                for (ViolationRecord r : history) {
                    score += (int) Math.round(r.getViolationLevel() * 10);
                }
            }
            return Math.min(100, score);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int scoreToLevel(int score) {
        if (score >= 90) return 3;
        if (score >= 70) return 2;
        if (score >= 50) return 1;
        return 0;
    }

    private int countActiveCases(Map<UUID, List<ViolationRecord>> all) {
        int n = 0;
        for (Map.Entry<UUID, List<ViolationRecord>> e : all.entrySet()) {
            int top = 0;
            for (ViolationRecord r : e.getValue()) {
                int s = (int) Math.round(r.getViolationLevel() * 10);
                if (s > top) top = s;
            }
            if (top >= 50 && !plugin.getBanManager().isBanned(e.getKey())) n++;
        }
        return n;
    }

    private int countTodayBans() {
        // 简化：取今天所有 ban 类审计记录数
        try {
            long dayStart = ZonedDateTime.now(ZoneId.systemDefault())
                    .toLocalDate().atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            com.anticheat.managers.audit.AuditQuery q = new com.anticheat.managers.audit.AuditQuery()
                    .setType("ban")
                    .setStartTime(dayStart)
                    .setPageSize(200);
            return (int) auditManager.count(q);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, List<ViolationRecord>> snapshotHistory(ViolationManager vm) {
        return vm.getViolationHistoryByPlayer();
    }

    /** TPS：通过反射取 MinecraftServer.recentTps；失败返回 20.0 */
    private double readTps() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = mcClass.getMethod("getServer").invoke(null);
            java.lang.reflect.Field f = mcClass.getDeclaredField("recentTps");
            f.setAccessible(true);
            double[] tps = (double[]) f.get(server);
            if (tps != null && tps.length > 0) return Math.min(20.0, Math.round(tps[0] * 10) / 10.0);
        } catch (Throwable ignored) {
            // 兼容低版本或不支持
        }
        return 20.0;
    }

    /** 内存占用百分比：Runtime + maxMemory */
    private int readMemoryPercent() {
        long max = Runtime.getRuntime().maxMemory();
        long total = Runtime.getRuntime().totalMemory();
        long free = Runtime.getRuntime().freeMemory();
        long used = total - free;
        if (max <= 0) return 0;
        return (int) Math.min(100, used * 100 / max);
    }
}
