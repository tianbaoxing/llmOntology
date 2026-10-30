package com.conca.ontology.user.dto;

import java.util.List;
import java.util.Objects;

import com.conca.ontology.user.entity.SysRolePermission;

/**
 * 角色权限规则（黑名单模式）DTO：PUT 请求体与 GET 出参共用。
 * 构造时输入清洗：null → 空列表、剔除空白项并去重（原 RoleRule 归一化逻辑平移——
 * "手工编辑容错"的历史语义自迁库后仅剩 API 输入清洗，归 DTO 边界职责）。
 * 运行时判定快照由 RolePermissionService 基于 SysRolePermission 归一化提供，不经本 DTO。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
public record RoleRuleDTO(
        List<String> deniedTools,
        List<String> deniedSkills,
        List<String> deniedSubagents) {

    public RoleRuleDTO {
        deniedTools = normalize(deniedTools);
        deniedSkills = normalize(deniedSkills);
        deniedSubagents = normalize(deniedSubagents);
    }

    public static RoleRuleDTO from(SysRolePermission row) {
        return new RoleRuleDTO(row.getDeniedTools(), row.getDeniedSkills(), row.getDeniedSubagents());
    }

    /** null → 空列表；剔除空白项并去重（API 输入清洗 + DB 历史脏数据容错） */
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
}
