package com.anticheat.managers.audit;

/**
 * 审计日志条目。
 *
 * <p>原位于 {@code com.anticheat.web.dto}，随 Web 面板移除后迁入本包，
 * 供 {@code AuditManager} 的查询接口继续使用。</p>
 */
public class AuditDTO {

    public String id;
    public String time;
    public String operator;
    /** 数字角色枚举：0=SUPER_ADMIN 1=ADMIN 2=REVIEWER 3=OBSERVER */
    public int operatorRole;
    /** login / logout / ban / unban / config_change / case_verdict / ... */
    public String type;
    public String target;
    public String ip;
    /** success / failed / warning */
    public String result;
    public String detail;

    public AuditDTO() {
    }

    public AuditDTO(String id, String time, String operator, int operatorRole, String type,
                    String target, String ip, String result, String detail) {
        this.id = id;
        this.time = time;
        this.operator = operator;
        this.operatorRole = operatorRole;
        this.type = type;
        this.target = target;
        this.ip = ip;
        this.result = result;
        this.detail = detail;
    }
}
