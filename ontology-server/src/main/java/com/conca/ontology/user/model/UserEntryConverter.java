package com.conca.ontology.user.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.conca.ontology.user.entity.SysUser;

/**
 * {@link SysUser} ↔ {@link UserEntry} 转换器。
 * 对外 API 模型 UserEntry 的时间字段保持 "yyyy-MM-dd HH:mm:ss" 字符串格式（前端零感知），
 * 库内实体使用 LocalDateTime（DATETIME 列）。
 */
public final class UserEntryConverter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UserEntryConverter() {
    }

    public static UserEntry toEntry(SysUser e) {
        return new UserEntry(e.getUserId(), e.getUsername(), e.getPasswordHash(), e.getRole(), e.getStatus(),
                format(e.getCreatedAt()), format(e.getUpdatedAt()), format(e.getLastLoginAt()));
    }

    public static SysUser toEntity(UserEntry u) {
        SysUser e = new SysUser();
        e.setUserId(u.userId());
        e.setUsername(u.username());
        e.setPasswordHash(u.passwordHash());
        e.setRole(u.role());
        e.setStatus(u.status());
        e.setCreatedAt(parse(u.createdAt()));
        e.setUpdatedAt(parse(u.updatedAt()));
        e.setLastLoginAt(parse(u.lastLoginAt()));
        return e;
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : TIME_FMT.format(t);
    }

    private static LocalDateTime parse(String s) {
        return s == null || s.isBlank() ? null : LocalDateTime.parse(s, TIME_FMT);
    }
}
