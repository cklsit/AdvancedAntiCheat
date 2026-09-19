package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.audit.AuditDTO;
import com.anticheat.managers.audit.AuditQuery;
import com.anticheat.managers.audit.AuditRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志管理器。
 * <p>
 * - {@link #log} 异步入库，由 {@link DatabaseManager#saveAudit} 投递到异步任务
 * - {@link #query} / {@link #count} 同步查询（会阻塞调用线程，不要在检测热路径上调用）
 *
 * <p>Web 面板已随观察者架构一并移除，本类保留落库与查询能力，供日后接入新的管理端使用。</p>
 */
public class AuditManager {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AdvancedAntiCheat plugin;

    public AuditManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    /** 记录审计日志（异步落库，fire-and-forget）。 */
    public void log(String operator, int operatorRole, String type, String target,
                    String ip, String result, String detail) {
        try {
            AuditRecord record = new AuditRecord(
                    System.currentTimeMillis(),
                    operator,
                    operatorRole,
                    type,
                    target,
                    ip,
                    result,
                    detail
            );
            plugin.getDatabaseManager().saveAudit(record);
        } catch (Exception e) {
            plugin.getLogger().warning("写入审计日志失败: " + e.getMessage());
        }
    }

    /** 查询审计记录并映射为 AuditDTO 列表。 */
    public List<AuditDTO> query(AuditQuery query) {
        List<AuditRecord> records = plugin.getDatabaseManager().queryAudits(query);
        List<AuditDTO> dtos = new ArrayList<>();
        for (AuditRecord r : records) {
            dtos.add(toDTO(r));
        }
        return dtos;
    }

    /** 查询审计记录总数。 */
    public long count(AuditQuery query) {
        return plugin.getDatabaseManager().countAudits(query);
    }

    /** 分页结果：返回 (当前页 DTO 列表, 总条数)。 */
    public Page<AuditDTO> queryPage(AuditQuery query) {
        long total = count(query);
        List<AuditDTO> list = query(query);
        int pageSize = query.getPageSize();
        int pages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        return new Page<>(list, total, query.getPage(), pageSize, pages);
    }

    public static AuditDTO toDTO(AuditRecord r) {
        return new AuditDTO(
                r.getId() == null ? "" : String.valueOf(r.getId()),
                ISO.format(Instant.ofEpochMilli(r.getTimestamp())),
                r.getOperator() == null ? "" : r.getOperator(),
                r.getOperatorRole(),
                r.getType() == null ? "" : r.getType(),
                r.getTarget() == null ? "" : r.getTarget(),
                r.getIp() == null ? "" : r.getIp(),
                r.getResult() == null ? "" : r.getResult(),
                r.getDetail()
        );
    }

    /** 简单分页结果。 */
    public static class Page<T> {
        public final List<T> list;
        public final long total;
        public final int page;
        public final int pageSize;
        public final int pages;

        public Page(List<T> list, long total, int page, int pageSize, int pages) {
            this.list = list;
            this.total = total;
            this.page = page;
            this.pageSize = pageSize;
            this.pages = pages;
        }
    }
}
