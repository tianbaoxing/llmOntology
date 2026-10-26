package com.conca.ontology.user.web;

import java.io.Serializable;

import com.conca.ontology.user.model.UserEntry;

import jakarta.servlet.http.HttpSession;

/**
 * 会话中的当前用户快照（登录成功时写入 HttpSession）。
 * Spring Session JDBC 会话属性经 JDK 序列化落 MySQL，必须实现 Serializable；
 * 字段增删改会使存量会话反序列化失效（用户重新登录即可），改结构时同步升 serialVersionUID。
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record SessionUser(String userId, String username, String role) implements Serializable {

    /** JDK 序列化版本（Spring Session JDBC 属性存 SPRING_SESSION_ATTRIBUTES BLOB 列） */
    private static final long serialVersionUID = 1L;

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
