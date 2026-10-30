package com.conca.ontology.user.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.conca.ontology.user.entity.SysUser;

/**
 * 用户出参 VO（登录 / 初始化 / 用户列表统一装配）。
 * 不含 passwordHash——敏感字段不出 service，防线由字段缺失本身保证。
 * 时间字段保持 "yyyy-MM-dd HH:mm:ss" 字符串格式（前端零感知），库内实体为 LocalDateTime。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
public record UserVO(
        String userId,
        String username,
        String role,          // ADMIN | USER（常量见 SysUser）
        String status,        // PENDING | ACTIVE | DISABLED（常量见 SysUser）
        String createdAt,
        String updatedAt,
        String lastLoginAt) {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static UserVO from(SysUser e) {
        return new UserVO(e.getUserId(), e.getUsername(), e.getRole(), e.getStatus(),
                format(e.getCreatedAt()), format(e.getUpdatedAt()), format(e.getLastLoginAt()));
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : TIME_FMT.format(t);
    }
}
