package com.conca.ontology.user.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.entity.SysRolePermission;
import com.conca.ontology.user.mapper.SysRolePermissionMapper;
import com.conca.ontology.user.model.RoleRule;
import com.conca.ontology.user.model.UserEntry;

/**
 * 角色权限管理：MySQL sys_role_permission 唯一事实源（P1 起，由 JSON 文件迁移而来），
 * 黑名单模式——清单命中的 tool / skill / subagent 对该角色禁用。
 *
 * ADMIN 固定全量放行：判定入口短路，不查库，误改数据不影响管理员。
 * 规则修改即时生效：RolePermissionMiddleware 每次工具调用实时判定，
 * SkillFilter 每次会话构建时重新读取，无需重启。
 *
 * 三类资源的触发通道（onActing 拦截口径）：
 * - tool：直接按工具名匹配（如 build_chart）；
 * - subagent：agent_spawn 工具的 agent_id 参数；
 * - skill：load_skill_through_path 的 skillId 参数，SkillCatalog 键格式为
 *   {name}_{source}（如 ontology-builder_workspace），故按 name 与 name_ 前缀双重匹配。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Service
public class RolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionService.class);

    private final SysRolePermissionMapper ruleMapper;

    public RolePermissionService(SysRolePermissionMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    // ---------------- 查询 ----------------

    /** 全部角色规则 */
    public Map<String, RoleRule> all() {
        Map<String, RoleRule> roles = new LinkedHashMap<>();
        for (SysRolePermission row : ruleMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().orderByAsc(SysRolePermission::getRole))) {
            roles.put(row.getRole(), toRule(row));
        }
        return roles;
    }

    /** 指定角色的规则；ADMIN / 未知角色返回空规则（全放行） */
    public RoleRule ruleOf(String role) {
        if (role == null || UserEntry.ROLE_ADMIN.equals(role)) {
            return RoleRule.EMPTY;
        }
        SysRolePermission row = ruleMapper.selectById(role);
        return row == null ? RoleRule.EMPTY : toRule(row);
    }

    public boolean isDeniedTool(String role, String toolName) {
        return toolName != null && ruleOf(role).deniedTools().contains(toolName);
    }

    public boolean isDeniedSubagent(String role, String agentId) {
        return agentId != null && !agentId.isBlank()
                && ruleOf(role).deniedSubagents().contains(agentId);
    }

    /** skillId 匹配：等于被禁技能名，或以「被禁技能名_」开头（SkillCatalog 键为 name_source） */
    public boolean isDeniedSkillId(String role, String skillId) {
        return matchesDeniedSkill(ruleOf(role).deniedSkills(), skillId);
    }

    /** skillId 纯匹配（无 IO，供中间件逐调用判定，避免循环内重复查库） */
    public static boolean matchesDeniedSkill(List<String> deniedSkills, String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        for (String denied : deniedSkills) {
            if (skillId.equals(denied) || skillId.startsWith(denied + "_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 被禁文件路径树（小写、无尾斜杠的工作区相对路径），由黑名单自动推导：
     * 技能 → skills/{name}（整目录，含 SKILL.md 及附属文件）；
     * 子代理 → subagents/{agentId}.md（人格定义文件）。
     * 与中间件文件工具路径拦截联动，封堵"绕过技能入口直接读文件"的泄露链。
     */
    public static List<String> deriveDeniedPaths(RoleRule rule) {
        if (rule == null || rule.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(rule.deniedSkills().size() + rule.deniedSubagents().size());
        for (String skill : rule.deniedSkills()) {
            paths.add(("skills/" + skill).toLowerCase());
        }
        for (String sub : rule.deniedSubagents()) {
            paths.add(("subagents/" + sub + ".md").toLowerCase());
        }
        return paths;
    }

    // ---------------- 更新（ADMIN） ----------------

    /** 更新角色规则（UPSERT）；ADMIN 拒绝（代码级全放行，配置无意义） */
    @Transactional
    public void update(String role, RoleRule rule) {
        if (role == null || role.isBlank()) {
            throw new ApiException(400, "角色不能为空");
        }
        String normalized = role.trim().toUpperCase();
        if (UserEntry.ROLE_ADMIN.equals(normalized)) {
            throw new ApiException(400, "ADMIN 角色固定全量放行，无需配置");
        }
        RoleRule safe = rule == null ? RoleRule.EMPTY : rule;
        SysRolePermission row = new SysRolePermission();
        row.setRole(normalized);
        row.setDeniedTools(safe.deniedTools());
        row.setDeniedSkills(safe.deniedSkills());
        row.setDeniedSubagents(safe.deniedSubagents());
        row.setUpdateTime(LocalDateTime.now());
        if (ruleMapper.selectById(normalized) == null) {
            ruleMapper.insert(row);
        } else {
            ruleMapper.updateById(row);
        }
        log.info("角色权限已更新：{} -> tools={} skills={} subagents={}",
                normalized, safe.deniedTools(), safe.deniedSkills(), safe.deniedSubagents());
    }

    // ---------------- 内部 ----------------

    private static RoleRule toRule(SysRolePermission row) {
        return new RoleRule(row.getDeniedTools(), row.getDeniedSkills(), row.getDeniedSubagents());
    }
}
