package com.conca.ontology.model;

import java.util.List;
import java.util.Objects;

/**
 * 角色权限规则（黑名单模式）：三类清单命中的资源对该角色禁用。
 *
 * ADMIN 不读取本配置——代码级固定全量放行（RolePermissionService 判定入口短路），
 * 即使配置文件被误改也不影响管理员。
 */
public record RoleRule(
        List<String> deniedTools,
        List<String> deniedSkills,
        List<String> deniedSubagents) {

    /** 全空规则（放行一切） */
    public static final RoleRule EMPTY = new RoleRule(null, null, null);

    public RoleRule {
        deniedTools = normalize(deniedTools);
        deniedSkills = normalize(deniedSkills);
        deniedSubagents = normalize(deniedSubagents);
    }

    /** null → 空列表；剔除空白项并去重（文件可能被手工编辑，需容错） */
    private static List<String> normalize(List<String> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return deniedTools.isEmpty() && deniedSkills.isEmpty() && deniedSubagents.isEmpty();
    }
}
