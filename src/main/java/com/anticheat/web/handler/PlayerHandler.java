package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.ViolationManager;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.managers.AuditManager;
import com.anticheat.utils.VersionUtil;
import com.anticheat.web.BukkitBridge;
import com.anticheat.web.auth.Permission;
import com.anticheat.web.dto.ApiResp;
import com.anticheat.web.dto.LinkedAccountDTO;
import com.anticheat.web.dto.PageDTO;
import com.anticheat.web.dto.PlayerDTO;
import com.anticheat.web.dto.ViolationRecordDTO;
import com.anticheat.web.util.JsonMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 玩家相关 REST 端点：
 * <ul>
 *   <li>GET /api/players - 在线玩家分页</li>
 *   <li>GET /api/players/{uuid} - 玩家详情</li>
 *   <li>POST /api/players/{uuid}/ban - 封禁玩家</li>
 * </ul>
 */
public class PlayerHandler extends AbstractHandler {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault());

    public PlayerHandler(AdvancedAntiCheat plugin, AuditManager auditManager) {
        super(plugin, auditManager);
    }

    public void register(Javalin app) {
        app.get("/api/players", this::list);
        app.get("/api/players/{uuid}", this::detail);
        app.post("/api/players/{uuid}/ban", this::ban);
        app.post("/api/players/kick", this::kick);
        app.post("/api/players/gamemode", this::gamemode);
    }

    private void list(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;

        int page = parseInt(ctx.queryParam("page"), 1);
        int pageSize = parseInt(ctx.queryParam("pageSize"), 20);
        String keyword = ctx.queryParam("keyword");
        String status = ctx.queryParam("status");

        // 切到主线程拿玩家快照（1.8 兼容：通过反射安全读取在线玩家）
        // 注意：toDTO 调用 getLocation/getWorld/getAddress 等 Bukkit API 必须在主线程
        List<PlayerDTO> snapshot;
        try {
            snapshot = BukkitBridge.syncSupply(plugin, () -> {
                List<Player> online = VersionUtil.safeGetOnlinePlayers();
                plugin.getLogger().info("[Web] /api/players safeGetOnlinePlayers returned " + online.size() + " players");
                List<PlayerDTO> list = new ArrayList<>();
                for (Player p : online) {
                    try {
                        list.add(toDTO(p));
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[Web] toDTO failed for " + p.getName() + ": " + t.getMessage());
                    }
                }
                return list;
            });
        } catch (Throwable t) {
            plugin.getLogger().severe("[Web] /api/players syncSupply failed: " + t.getMessage());
            t.printStackTrace();
            snapshot = new ArrayList<>();
        }

        // 过滤
        List<PlayerDTO> filtered = new ArrayList<>();
        for (PlayerDTO p : snapshot) {
            if (keyword != null && !keyword.isEmpty()) {
                String k = keyword.toLowerCase(Locale.ROOT);
                if (!p.name.toLowerCase(Locale.ROOT).contains(k) && !p.uuid.toLowerCase(Locale.ROOT).contains(k) && !p.ip.contains(k)) {
                    continue;
                }
            }
            if (status != null && !status.isEmpty() && !status.equals(p.status)) {
                continue;
            }
            filtered.add(p);
        }
        // 按风险分倒序
        filtered.sort((a, b) -> Integer.compare(b.riskScore, a.riskScore));

        int total = filtered.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(filtered.size(), from + pageSize);
        List<PlayerDTO> pageList = (from >= filtered.size())
                ? Collections.emptyList()
                : filtered.subList(from, to);

        PageDTO<PlayerDTO> result = new PageDTO<>(pageList, total, page, pageSize);
        ok(ctx, result);
    }

    private void detail(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_READ)) return;
        String uuidStr = ctx.pathParam("uuid");
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            notFound(ctx, "UUID 非法: " + uuidStr);
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            notFound(ctx, "玩家不在线或不存在");
            return;
        }
        PlayerDTO dto = BukkitBridge.syncSupply(plugin, () -> toDTO(player));
        ok(ctx, dto);
    }

    private void ban(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_BAN)) return;
        String uuidStr = ctx.pathParam("uuid");
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            notFound(ctx, "UUID 非法: " + uuidStr);
            return;
        }
        BanPayload payload = JsonMapper.fromJson(ctx.body(), BanPayload.class);
        if (payload == null || payload.duration == null || payload.duration.isEmpty()) {
            fail(ctx, 400, "请提供 duration 与 reason");
            return;
        }

        // 切回主线程执行封禁
        BukkitBridge.syncRun(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            String name = p == null ? uuidStr : p.getName();
            plugin.getBanManager().banPlayer(uuid, name, payload.duration,
                    payload.reason == null ? "Web 面板封禁" : payload.reason);
        });

        audit(ctx, "ban", uuidStr, "success", "duration=" + payload.duration
                + (payload.reason != null ? " reason=" + payload.reason : ""));
        ok(ctx, null);
    }

    // ================================================================
    // POST /api/players/kick   { uuid: string, reason?: string }
    // ================================================================
    private void kick(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_KICK)) return;
        KickPayload payload = JsonMapper.fromJson(ctx.body(), KickPayload.class);
        if (payload == null || payload.uuid == null || payload.uuid.isEmpty()) {
            fail(ctx, 400, "缺少 uuid");
            return;
        }
        UUID uuid;
        try { uuid = UUID.fromString(payload.uuid); }
        catch (IllegalArgumentException e) { fail(ctx, 400, "UUID 非法: " + payload.uuid); return; }

        String reason = (payload.reason == null || payload.reason.isEmpty())
                ? "管理员踢出" : payload.reason;

        String name = BukkitBridge.syncSupply(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) { return null; }
            StringBuilder kickMsg = new StringBuilder();
            kickMsg.append("§c§l您已被踢出服务器\n");
            kickMsg.append("§7原因: §f").append(reason);
            p.kickPlayer(kickMsg.toString());
            return p.getName();
        });
        if (name == null) {
            notFound(ctx, "玩家不在线");
            return;
        }
        audit(ctx, "kick", uuid.toString(), "success", "reason=" + reason);
        ok(ctx, null);
    }

    // ================================================================
    // POST /api/players/gamemode   { uuid: string, mode: "SPECTATOR"|"SURVIVAL"|"CREATIVE"|"ADVENTURE"|"OBSERVER" }
    //   "OBSERVER" -> GameMode.SPECTATOR (中文按钮：观察模式)
    // ================================================================
    private void gamemode(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.PLAYERS_GAMEMODE)) return;
        GamemodePayload payload = JsonMapper.fromJson(ctx.body(), GamemodePayload.class);
        if (payload == null || payload.uuid == null || payload.uuid.isEmpty()) {
            fail(ctx, 400, "缺少 uuid");
            return;
        }
        if (payload.mode == null || payload.mode.isEmpty()) {
            fail(ctx, 400, "缺少 mode");
            return;
        }
        UUID uuid;
        try { uuid = UUID.fromString(payload.uuid); }
        catch (IllegalArgumentException e) { fail(ctx, 400, "UUID 非法: " + payload.uuid); return; }

        String modeIn = payload.mode.toUpperCase(Locale.ROOT);
        if (modeIn.equals("OBSERVER")) modeIn = "SPECTATOR";

        final String finalMode = modeIn;
        String[] result = BukkitBridge.syncSupply(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return new String[]{"NOT_ONLINE", ""};
            try {
                org.bukkit.GameMode gm = org.bukkit.GameMode.valueOf(finalMode);
                p.setGameMode(gm);
                return new String[]{"OK", gm.name()};
            } catch (IllegalArgumentException badEnum) {
                return new String[]{"BAD_MODE", finalMode};
            }
        });
        if (result[0].equals("NOT_ONLINE")) { notFound(ctx, "玩家不在线"); return; }
        if (result[0].equals("BAD_MODE"))  { fail(ctx, 400, "非法 mode: " + result[1]); return; }
        audit(ctx, "gamemode", uuid.toString(), "success", "mode=" + result[1]);
        ok(ctx, java.util.Collections.singletonMap("mode", result[1]));
    }

    private UUID parseUuid(Context ctx) {
        String s = ctx.pathParam("uuid");
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) {
            notFound(ctx, "UUID 非法: " + s);
            return null;
        }
    }

    // ===== 内部映射 =====

    public PlayerDTO toDTO(Player p) {
        if (p == null) {
            PlayerDTO empty = new PlayerDTO();
            empty.uuid = ""; empty.name = "null-player"; empty.avatar = "?"; empty.ip = "";
            empty.ping = 0; empty.gameMode = ""; empty.world = ""; empty.locationX = 0; empty.locationZ = 0;
            empty.firstJoin = ""; empty.lastJoin = ""; empty.onlineDuration = 0; empty.version = "";
            empty.country = ""; empty.hardwareId = ""; empty.status = "offline";
            empty.riskScore = 0; empty.riskLevel = 0; empty.lastTrigger = "";
            empty.violationsCount = 0; empty.violationHistory = new ArrayList<>();
            empty.linkedAccounts = Collections.emptyList();
            return empty;
        }
        PlayerDTO dto = new PlayerDTO();
        // ===== 基础身份（容错：全部 try-catch 独立） =====
        try { dto.uuid = p.getUniqueId() == null ? "" : p.getUniqueId().toString(); }
        catch (Throwable t) { dto.uuid = ""; }
        try { dto.name = p.getName() == null ? "" : p.getName(); }
        catch (Throwable t) { dto.name = "unknown"; }
        try {
            String nm = p.getName();
            dto.avatar = (nm == null || nm.isEmpty()) ? "?" : nm.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        } catch (Throwable t) { dto.avatar = "?"; }
        try {
            dto.ip = (p.getAddress() == null || p.getAddress().getAddress() == null)
                    ? "" : p.getAddress().getAddress().getHostAddress();
        } catch (Throwable t) { dto.ip = ""; }
        try { dto.ping = p.getPing(); } catch (Throwable t) { dto.ping = 0; }
        try { dto.gameMode = p.getGameMode() == null ? "" : p.getGameMode().name(); }
        catch (Throwable t) { dto.gameMode = ""; }
        try { dto.world = p.getWorld() == null ? "" : p.getWorld().getName(); }
        catch (Throwable t) { dto.world = ""; }
        // 坐标快照（用于实时地图）
        try {
            org.bukkit.Location loc = p.getLocation();
            dto.locationX = loc.getX();
            dto.locationZ = loc.getZ();
        } catch (Throwable ignored) {
            dto.locationX = 0; dto.locationZ = 0;
        }
        // 时间戳
        try { dto.firstJoin = ISO.format(Instant.ofEpochMilli(Math.max(0, p.getFirstPlayed()))); }
        catch (Throwable t) { dto.firstJoin = ""; }
        try { dto.lastJoin = ISO.format(Instant.ofEpochMilli(Math.max(0, p.getLastPlayed()))); }
        catch (Throwable t) { dto.lastJoin = ""; }
        try {
            long played = p.getLastPlayed() > 0 ? p.getLastPlayed() : System.currentTimeMillis();
            dto.onlineDuration = Math.max(0, (System.currentTimeMillis() - played) / 1000L);
        } catch (Throwable t) { dto.onlineDuration = 0; }
        try { dto.version = Bukkit.getVersion(); }
        catch (Throwable t) { dto.version = ""; }
        dto.country = "";
        dto.hardwareId = "";

        // 状态：在线 / 封禁
        try {
            boolean banned = plugin.getBanManager().isBanned(p.getUniqueId());
            dto.status = banned ? "banned" : "online";
        } catch (Throwable t) {
            // BanManager 没初始化时回退
            dto.status = "online";
        }

        // 违规历史 + 风险评分（容错：所有层级全部 try/catch）
        List<ViolationRecordDTO> his = new ArrayList<>();
        int score = 0;
        long lastTs = 0;
        try {
            String serverName = "";
            try { serverName = plugin.getConfig().getString("database.server-name", "Server-1"); }
            catch (Throwable ignored) {}
            ViolationManager vm = null;
            try { vm = plugin.getDetectionManager().getViolationManager(); } catch (Throwable ignored) {}
            if (vm != null) {
                java.util.UUID pu = null;
                try { pu = p.getUniqueId(); } catch (Throwable ignored) {}
                List<ViolationRecord> history = null;
                if (pu != null) try { history = vm.getViolationHistory(pu); } catch (Throwable ignored) {}
                if (history != null) {
                    for (ViolationRecord r : history) {
                        try {
                            int s = (int) Math.round(r.getViolationLevel() * 10);
                            score += s;
                            String type = r.getType() != null ? r.getType().name() : "UNKNOWN";
                            String sev = r.getSeverity() != null ? r.getSeverity().name() : "UNKNOWN";
                            his.add(new ViolationRecordDTO(
                                    (pu == null ? "" : pu.toString()) + "-" + r.getTimestamp(),
                                    type, sev, s,
                                    ISO.format(Instant.ofEpochMilli(r.getTimestamp())),
                                    serverName,
                                    r.getDetails()
                            ));
                            if (r.getTimestamp() > lastTs) lastTs = r.getTimestamp();
                        } catch (Throwable ignored) { /* 跳过单条 */ }
                    }
                }
            }
        } catch (Throwable ignored) { /* 违规模块整体缺失降级 */ }
        if (score > 100) score = 100;
        dto.riskScore = score;
        dto.riskLevel = scoreToLevel(score);
        dto.lastTrigger = lastTs == 0 ? "" : ISO.format(Instant.ofEpochMilli(lastTs));
        dto.violationsCount = his.size();
        dto.violationHistory = his;
        try { dto.linkedAccounts = Collections.emptyList(); } catch (Throwable ignored) { dto.linkedAccounts = new ArrayList<>(); }
        return dto;
    }

    private static int scoreToLevel(int score) {
        if (score >= 90) return 3;
        if (score >= 70) return 2;
        if (score >= 50) return 1;
        return 0;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    public static class BanPayload {
        public String duration;
        public String reason;
    }

    public static class KickPayload {
        public String uuid;
        public String reason;
    }

    public static class GamemodePayload {
        public String uuid;
        public String mode;
    }
}
