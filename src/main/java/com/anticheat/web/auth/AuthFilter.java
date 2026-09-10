package com.anticheat.web.auth;

import com.anticheat.web.dto.ApiResp;
import com.anticheat.web.util.InetAddressUtil;
import com.anticheat.web.util.JsonMapper;
import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Javalin before("/api/*") 鉴权过滤器。
 * <ul>
 *   <li>放行 /api/auth/login 与 /api/auth/register（若启用）</li>
 *   <li>从 Authorization: Bearer &lt;token&gt; 提取 token 并校验</li>
 *   <li>失败返回 401 ApiResp JSON；成功把 Account 放入 ctx.attribute 供后续 Handler 使用</li>
 * </ul>
 */
public class AuthFilter implements Handler {

    public static final String ATTR_ACCOUNT = "web.account";
    public static final String ATTR_TOKEN = "web.token";
    public static final String ATTR_IP = "web.ip";

    private final AuthManager authManager;

    public AuthFilter(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void handle(Context ctx) {
        String path = ctx.path();
        // 白名单：登录与登录页资源（保留扩展空间）、调试端点（无需鉴权，方便直接 curl 诊断）
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register") || path.equals("/api/meta")
                || path.equals("/api/debug/online")) {
            return;
        }

        String ip = InetAddressUtil.getRemoteAddr(ctx);
        ctx.attribute(ATTR_IP, ip);

        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            deny(ctx, "未提供 Authorization Bearer token");
            return;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            deny(ctx, "Authorization Bearer token 为空");
            return;
        }

        Account account = authManager.verifyToken(token);
        if (account == null) {
            deny(ctx, "token 无效或已过期");
            return;
        }
        ctx.attribute(ATTR_ACCOUNT, account);
        ctx.attribute(ATTR_TOKEN, token);
    }

    private void deny(Context ctx, String reason) {
        ctx.status(401);
        ctx.contentType("application/json; charset=utf-8");
        ctx.result(JsonMapper.toJson(ApiResp.unauthorized(reason)));
    }
}
