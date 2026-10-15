package com.conca.ontology.user.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * data/role-permissions.json 顶层结构：version + 角色名 → 权限规则
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
public record RolePermissionsFile(int version, Map<String, RoleRule> roles) {

    public RolePermissionsFile {
        roles = roles == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(roles));
    }
}
