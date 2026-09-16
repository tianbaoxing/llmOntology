package com.conca.ontology.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 用户条目：data/users.json 的单元（userId 主标识终身不变；username 展示名，二者当前同值） */
public record UserEntry(
        String userId,
        String username,
        String passwordHash,
        String role,          // ADMIN | USER
        String status,        // PENDING | ACTIVE | DISABLED
        String createdAt,
        String updatedAt,
        String lastLoginAt) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @JsonIgnore   // 避免被 Jackson 当作 is 前缀 getter 序列化进 JSON
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    @JsonIgnore
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
