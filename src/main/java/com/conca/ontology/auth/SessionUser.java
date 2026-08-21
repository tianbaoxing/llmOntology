package com.conca.ontology.auth;

import com.conca.ontology.model.UserEntry;

import jakarta.servlet.http.HttpSession;

/** 会话中的当前用户快照（登录成功时写入 HttpSession） */
public record SessionUser(String userId, String username, String role) {

    public static final String SESSION_KEY = "AUTH_USER";

    public boolean isAdmin() {
        return UserEntry.ROLE_ADMIN.equals(role);
    }

    public static SessionUser from(UserEntry u) {
        return new SessionUser(u.userId(), u.username(), u.role());
    }

    public static SessionUser of(HttpSession session) {
        return session == null ? null : (SessionUser) session.getAttribute(SESSION_KEY);
    }
}
