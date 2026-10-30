package com.conca.ontology.user.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.user.dto.RoleRuleDTO;
import com.conca.ontology.user.service.RolePermissionService;

/**
 * 角色权限管理（仅 ADMIN：AuthInterceptor 按 /api/admin 前缀拦截）：
 * GET /api/admin/role-permissions         → 全部角色规则
 * PUT /api/admin/role-permissions/{role}  → 更新角色黑名单（body 为 RoleRuleDTO 三清单）
 * 资源全集（工具/技能/子代理）由 GET /api/agent/tools|skills|subagents 提供，前端自行取用。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@RestController
@RequestMapping("/api/admin/role-permissions")
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    public RolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(Map.of("roles", rolePermissionService.all()));
    }

    @PutMapping("/{role}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String role,
            @RequestBody(required = false) RoleRuleDTO rule) {
        rolePermissionService.update(role, rule);
        return ApiResponse.ok(Map.of("updated", true));
    }
}
