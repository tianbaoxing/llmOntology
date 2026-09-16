package com.conca.ontology.config;

import java.io.IOException;
import java.util.Set;

import org.springframework.web.servlet.HandlerInterceptor;

import com.conca.ontology.auth.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证拦截（Session + 角色）：
 * - 公开页面：/login（含静态 /pages/login.html）
 * - 公开接口：/api/session（登录登出/当前用户，敏感操作在 Controller 内校验）、/api/setup（初始化向导）、
 *   /api/app、/api/llm/**（LLM 专属，后续做 token 验证）、POST /api/users（自助注册）、
 *   PUT /api/users/me/password（自助改密，Controller 内校验会话）
 * - 页面（/、/data、/files、/backup、/chat、/dashboard、/session-detail、/users、/instances、/graph、/eval、/pages/**）：
 *   未登录 302 跳登录页；/users 与 /eval 仅 ADMIN（/data、/backup 页面级权限由前端控制，接口层 403 兜底）
 * - /api/** 其余：未登录 401 JSON；/api/users、/api/eval 与写方法（POST/PUT/DELETE/PATCH，/api/agent AI 问答除外）仅 ADMIN
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** 需登录的页面路由（静态资源 /pages/** 单独按前缀判断） */
    private static final Set<String> PAGE_PATHS = Set.of("/", "/data", "/files", "/backup",
            "/chat", "/dashboard", "/session-detail", "/users", "/instances", "/graph", "/eval");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        // 公开：登录页与其静态资源
        if (uri.equals("/login") || uri.equals("/pages/login.html")) {
            return true;
        }
        // 公开接口：会话自身（login/logout/me，登出幂等）、系统初始化、应用信息、LLM 专属
        if (uri.equals("/api/session") || uri.equals("/api/setup") || uri.equals("/api/app")
                || uri.startsWith("/api/llm/")) {
            return true;
        }
        // 公开接口：自助注册与自助改密（改密在 Controller 内校验会话）
        if (("POST".equalsIgnoreCase(method) && uri.equals("/api/users"))
                || ("PUT".equalsIgnoreCase(method) && uri.equals("/api/users/me/password"))) {
            return true;
        }

        SessionUser user = SessionUser.of(request.getSession(false));

        // 页面（含静态资源 /pages/*.html）
        if (PAGE_PATHS.contains(uri) || uri.startsWith("/pages/")) {
            if (user == null) {
                response.sendRedirect("/login");
                return false;
            }
            if ((uri.equals("/users") || uri.equals("/pages/users.html")
                    || uri.equals("/eval") || uri.equals("/pages/eval.html")) && !user.isAdmin()) {
                response.sendError(403);
                return false;
            }
            return true;
        }

        // API
        if (uri.startsWith("/api")) {
            if (user == null) {
                writeJson(response, 401, "未登录或会话已过期");
                return false;
            }
            // /api/dashboard 已放开为登录即可（统计当前用户自己的用量）；
            // /api/eval 评测中心整体仅 ADMIN（页面与接口一致）；/api/skills Skills 管理、
            // /api/admin 角色权限管理同样仅 ADMIN
            boolean adminOnly = uri.startsWith("/api/users") || uri.startsWith("/api/eval")
                    || uri.startsWith("/api/skills") || uri.startsWith("/api/admin");
            if (!adminOnly && !isReadMethod(method)
                    && !uri.startsWith("/api/agent")) {
                adminOnly = true;
            }
            if (adminOnly && !user.isAdmin()) {
                writeJson(response, 403, "需要管理员权限");
                return false;
            }
        }
        return true;
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    private static void writeJson(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
