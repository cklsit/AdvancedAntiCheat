package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.core.AntiCheatCore;
import com.anticheat.core.db.AuditFilter;
import com.anticheat.core.db.AuditRow;
import com.anticheat.core.db.BanRow;
import com.anticheat.core.db.DatabaseService;
import com.anticheat.core.db.ProfileRow;
import com.anticheat.managers.audit.AuditQuery;
import com.anticheat.managers.audit.AuditRecord;
import com.anticheat.profiles.PlayerProfile;
import com.anticheat.utils.ProfileSerializer;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 旧数据库门面的**薄适配层**（保留旧签名，内部全部转到核心层的 PostgreSQL / H2 实现）。
 *
 * <h3>为什么保留这一层，而不是把调用点全部改掉</h3>
 * 换的是**存储实现**，不是业务语义。`BanManager` / `ProfileManager` / `AuditManager` /
 * `BehaviorTracker` / `ConfigGUI` 各自带着一整套缓存、合并、UI 逻辑，
 * 它们依赖的是"给个 uuid 拿一条封禁记录"这种契约，而不是"数据存在哪个引擎里"。
 * 一次性改掉所有调用点，等于把这次改动的风险面从"数据库"扩大到"封禁/档案/审计/界面"，
 * 而其中任何一处行为漂移都不会被现有测试发现。
 *
 * <p>因此这里刻意做成"零业务逻辑的转发"：不缓存、不重试、不改变线程模型
 * （旧行为里异步的仍异步、同步的仍同步），只做两件事：</p>
 * 1. 把旧模型（`BanRecord` / `AuditRecord` / `PlayerProfile`）与新的行模型互相映射；
 * 2. 在数据库不可用时**安静降级**（返回空/null，而不是抛异常）——
 *    旧实现里连接失败是抛 `RuntimeException` 出来让 `BanManager` 自己去吞，
 *    现在统一由 `DatabaseService` 记录状态，调用方只需要处理"没数据"。</p>
 */
public class DatabaseManager {

    private final AdvancedAntiCheat plugin;

    /**
     * @deprecated 连接与建表由核心层的 {@code DatabaseInit} 负责，构造这个对象不再触发任何 IO。
     */
    @Deprecated
    public DatabaseManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    private DatabaseService service() {
        return AntiCheatCore.getDatabase();
    }

    private boolean ready() {
        DatabaseService service = service();
        return service != null && service.isReady();
    }

    /** 后端名（`h2` / `postgresql`），未就绪时返回 `disabled`；仅用于界面与日志显示。 */
    public String getDatabaseType() {
        DatabaseService service = service();
        if (service == null) {
            return "disabled";
        }
        if (!service.isReady()) {
            return service.isEnabled() ? "unavailable" : "disabled";
        }
        return service.getSettings().getBackend().name().toLowerCase();
    }

    /** 一条人类可读的状态行（写进启动日志）。 */
    public String describe() {
        DatabaseService service = service();
        return service == null ? "未初始化" : service.describe();
    }

    // ------------------------------------------------------------------ 封禁

    public void banPlayer(UUID playerUUID, String playerName, String reason,
                          String bannedBy, long banTime, long expiryTime, String serverName) {
        new BukkitRunnable() {
            @Override
            public void run() {
                DatabaseService service = service();
                if (service == null) {
                    return;
                }
                // 旧 API 用 0 表示永久（expiryTime <= 0）
                Long expiresAt = expiryTime > 0 ? expiryTime : null;
                Long id = service.banUuid(playerUUID, playerName, reason, bannedBy, expiresAt, banTime, null);
                if (id == null) {
                    plugin.getLogger().warning("写入封禁失败（数据库不可用或该玩家已有生效封禁）: " + playerName);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 是否处于生效封禁中（登录路径上的同步判定）。
     *
     * <p>顺带把"已过期但状态还没刷"的封禁落状态：旧实现也这么做，
     * 而这条路径恰好在玩家每次登录时执行，是最自然的清理时机。</p>
     */
    public boolean isPlayerBanned(UUID playerUUID) {
        DatabaseService service = service();
        if (service == null || !service.isReady()) {
            return false;
        }
        long now = System.currentTimeMillis();
        BanRow ban = service.findBan(playerUUID, null, now);
        if (ban == null) {
            return false;
        }
        if (!ban.isEffective(now)) {
            service.expireOverdue(now);
            return false;
        }
        return true;
    }

    public BanManager.BanRecord getBanRecord(UUID playerUUID) {
        DatabaseService service = service();
        if (service == null || !service.isReady()) {
            return null;
        }
        BanRow ban = service.findBan(playerUUID, null, System.currentTimeMillis());
        if (ban == null || !ban.isEffective(System.currentTimeMillis())) {
            return null;
        }
        return toRecord(ban, playerUUID);
    }

    public void unbanPlayer(UUID playerUUID) {
        new BukkitRunnable() {
            @Override
            public void run() {
                DatabaseService service = service();
                if (service != null) {
                    service.unbanUuid(playerUUID, "console", "unban", System.currentTimeMillis());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    public List<BanManager.BanRecord> getAllBans() {
        DatabaseService service = service();
        if (service == null || !service.isReady()) {
            return new ArrayList<>();
        }
        List<BanRow> rows = service.listActiveBans(System.currentTimeMillis(), 500);
        List<BanManager.BanRecord> out = new ArrayList<>(rows.size());
        for (BanRow row : rows) {
            UUID uuid = row.getUuid() != null ? row.getUuid() : uuidOrNull(row);
            if (uuid != null) {
                out.add(toRecord(row, uuid));
            }
        }
        return out;
    }

    private UUID uuidOrNull(BanRow row) {
        try {
            return UUID.fromString(row.getTarget());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BanManager.BanRecord toRecord(BanRow row, UUID uuid) {
        String name = row.getName();
        if (name == null) {
            ProfileRow profile = service() != null ? service().profileOf(uuid) : null;
            name = profile != null ? profile.getName() : "";
        }
        long expiry = row.getExpiresAt() == null ? 0L : row.getExpiresAt();
        return new BanManager.BanRecord(
                uuid, name, row.getReason(), row.getOperator(), row.getIssuedAt(), expiry, row.getServerName());
    }

    // ------------------------------------------------------------------ 玩家档案

    public void savePlayerProfile(PlayerProfile profile) {
        if (profile == null) {
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                DatabaseService service = service();
                if (service == null) {
                    return;
                }
                try {
                    service.saveProfileBlob(
                            profile.getPlayerUUID(), profile.getPlayerName(), ProfileSerializer.serialize(profile));
                } catch (Exception e) {
                    plugin.getLogger().warning("保存玩家档案失败: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    public PlayerProfile loadPlayerProfile(UUID playerUUID) {
        if (playerUUID == null) {
            return null;
        }
        DatabaseService service = service();
        if (service == null || !service.isReady()) {
            return null;
        }
        try {
            String serialized = service.loadProfileBlob(playerUUID);
            if (serialized != null) {
                return ProfileSerializer.deserialize(serialized);
            }
        } catch (Exception e) {
            // 反序列化失败通常意味着档案格式与当前代码不兼容（旧档案结构变更）：
            // 报一行警告并当作"没有档案"，而不是让调用方崩在加载流程里
            plugin.getLogger().warning("加载玩家档案失败（将按新档案处理）: " + playerUUID + " - " + e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------ 审计日志

    public void saveAudit(AuditRecord audit) {
        if (audit == null) {
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                DatabaseService service = service();
                if (service != null) {
                    service.saveAudit(new AuditRow(
                            null, audit.getTimestamp(), audit.getOperator(), audit.getOperatorRole(),
                            audit.getType(), audit.getTarget(), audit.getIp(), audit.getResult(), audit.getDetail()));
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    public List<AuditRecord> queryAudits(AuditQuery query) {
        DatabaseService service = service();
        if (service == null || !service.isReady()) {
            return new ArrayList<>();
        }
        try {
            List<AuditRow> rows = service.queryAudits(toFilter(query));
            List<AuditRecord> out = new ArrayList<>(rows.size());
            for (AuditRow row : rows) {
                AuditRecord record = new AuditRecord(
                        row.getTimestamp(), row.getOperator(), row.getOperatorRole(), row.getType(),
                        row.getTarget(), row.getIp(), row.getResult(), row.getDetail());
                record.setId(row.getId());
                out.add(record);
            }
            return out;
        } catch (Exception e) {
            plugin.getLogger().warning("查询审计记录失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public long countAudits(AuditQuery query) {
        DatabaseService service = service();
        if (service == null || !service.isReady()) {
            return 0L;
        }
        try {
            return service.countAudits(toFilter(query));
        } catch (Exception e) {
            plugin.getLogger().warning("统计审计记录失败: " + e.getMessage());
            return 0L;
        }
    }

    private AuditFilter toFilter(AuditQuery query) {
        if (query == null) {
            return new AuditFilter(null, null, null, null, null, 1, 20);
        }
        return new AuditFilter(
                query.getType(), query.getResult(), query.getKeyword(),
                query.getStartTime(), query.getEndTime(), query.getPage(), query.getPageSize());
    }

    /**
     * 空实现：连接池的关闭由核心层生命周期负责
     * （旧实现的 `close()` 从来没有任何调用点，连接因此一直泄漏到 JVM 退出）。
     */
    public void close() {
        // 见方法注释
    }
}
