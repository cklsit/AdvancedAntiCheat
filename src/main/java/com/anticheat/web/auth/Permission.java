package com.anticheat.web.auth;

/**
 * 权限常量 + 校验工具。与前端 hasPerm 逻辑一致：
 * 支持 {@code *} 全匹配，{@code xxx:*} 前缀通配（如 {@code dashboard:*} 命中 {@code dashboard:read}）。
 */
public final class Permission {

    // ====== 权限字符串常量 ======
    public static final String ALL = "*";

    public static final String DASHBOARD_READ = "dashboard:read";
    public static final String DASHBOARD_ALL = "dashboard:*";

    public static final String PLAYERS_READ = "players:read";
    public static final String PLAYERS_BAN = "players:ban";
    public static final String PLAYERS_KICK = "players:kick";
    public static final String PLAYERS_GAMEMODE = "players:gamemode";
    public static final String PLAYERS_UNBAN = "players:unban";
    public static final String PLAYERS_ALL = "players:*";

    public static final String CASES_READ = "cases:read";
    public static final String CASES_VERDICT = "cases:verdict";
    public static final String CASES_ALL = "cases:*";

    public static final String CONFIG_READ = "config:read";
    public static final String CONFIG_UPDATE = "config:update";
    public static final String CONFIG_ALL = "config:*";

    public static final String AUDIT_READ = "audit:read";

    public static final String NOTIFICATIONS_READ = "notifications:read";
    public static final String NOTIFICATIONS_ALL = "notifications:*";

    public static final String AILAB_READ = "ailab:read";
    public static final String AILAB_MANAGE = "ailab:manage";
    public static final String AILAB_ALL = "ailab:*";

    private Permission() {
    }

    /**
     * 校验账号是否拥有指定权限。
     *
     * @param account 待校验账号，null 则返回 false
     * @param perm    权限字符串，例如 "dashboard:read"
     */
    public static boolean hasPermission(Account account, String perm) {
        if (account == null || account.permissions == null || perm == null) {
            return false;
        }
        for (String p : account.permissions) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (ALL.equals(p)) {
                return true;
            }
            if (p.equals(perm)) {
                return true;
            }
            // "xxx:*" 前缀通配
            if (p.endsWith(":*") && perm.startsWith(p.substring(0, p.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
