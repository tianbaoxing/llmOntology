package com.conca.ontology.config;

import java.io.IOException;

import org.springframework.web.servlet.HandlerInterceptor;

import com.conca.ontology.auth.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证拦截（Session + 角色）：
 * - 公开：/admin/login（含静态 login.html）、/api/auth/**、/api/app、/api/llm/**（LLM 专属，后续做 token 验证）
 * - /admin/** 页面（含静态资源）：未登录 302 跳登录页；/admin/users 仅 ADMIN
 * - /api/** 其余：未登录 401 JSON；/api/users 与写方法（POST/PUT/DELETE/PATCH，/api/agent AI 问答除外）仅 ADMIN
 */
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();

        // 公开：登录/初始化页与其静态资源
        if (uri.equals("/admin/login") || uri.equals("/admin/login.html")) {
            return true;
        }
        // 公开接口：认证自身（password 等敏感操作在 Controller 内校验会话）、应用信息、LLM 专属
        if (uri.startsWith("/api/auth/") || uri.equals("/api/app") || uri.startsWith("/api/llm/")) {
            return true;
        }

        SessionUser user = SessionUser.of(request.getSession(false));

        // 管理页面（含静态资源 /admin/*.html）
        if (uri.startsWith("/admin")) {
            if (user == null) {
                response.sendRedirect("/admin/login");
                return false;
            }
            if ((uri.equals("/admin/users") || uri.equals("/admin/users.html")) && !user.isAdmin()) {
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
            boolean adminOnly = uri.startsWith("/api/users");
            if (!adminOnly && !isReadMethod(request.getMethod()) && !uri.startsWith("/api/agent")) {
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
