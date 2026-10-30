package com.conca.ontology.user.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.dto.RoleRuleDTO;
import com.conca.ontology.user.entity.SysRolePermission;
import com.conca.ontology.user.entity.SysUser;
import com.conca.ontology.user.mapper.SysRolePermissionMapper;

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

    /** 全部角色规则（GET 出参装配，运行时快照另见 ruleOf） */
    public Map<String, RoleRuleDTO> all() {
        Map<String, RoleRuleDTO> roles = new LinkedHashMap<>();
        for (SysRolePermission row : ruleMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().orderByAsc(SysRolePermission::getRole))) {
            roles.put(row.getRole(), RoleRuleDTO.from(row));
        }
        return roles;
    }

    /**
     * 指定角色的规则快照（detached SysRolePermission，三清单已归一化非 null，仅作判定数据载体）；
     * ADMIN / 未知角色返回空规则（全放行）。供中间件与 isDeniedXxx 判定链消费。
     */
    public SysRolePermission ruleOf(String role) {
        if (role == null || SysUser.ROLE_ADMIN.equals(role)) {
            return emptyRule();
        }
        SysRolePermission row = ruleMapper.selectById(role);
        if (row == null) {
            return emptyRule();
        }
        row.setDeniedTools(normalize(row.getDeniedTools()));
        row.setDeniedSkills(normalize(row.getDeniedSkills()));
        row.setDeniedSubagents(normalize(row.getDeniedSubagents()));
        return row;
    }

    public boolean isDeniedTool(String role, String toolName) {
        return toolName != null && ruleOf(role).getDeniedTools().contains(toolName);
    }

    public boolean isDeniedSubagent(String role, String agentId) {
        return agentId != null && !agentId.isBlank()
                && ruleOf(role).getDeniedSubagents().contains(agentId);
    }

    /** skillId 匹配：等于被禁技能名，或以「被禁技能名_」开头（SkillCatalog 键为 name_source） */
    public boolean isDeniedSkillId(String role, String skillId) {
        return matchesDeniedSkill(ruleOf(role).getDeniedSkills(), skillId);
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
    public static List<String> deriveDeniedPaths(SysRolePermission rule) {
        if (isEmpty(rule)) {
            return List.of();
        }
        List<String> skills = safe(rule.getDeniedSkills());
        List<String> subs = safe(rule.getDeniedSubagents());
        List<String> paths = new ArrayList<>(skills.size() + subs.size());
        for (String skill : skills) {
            paths.add(("skills/" + skill).toLowerCase());
        }
        for (String sub : subs) {
            paths.add(("subagents/" + sub + ".md").toLowerCase());
        }
        return paths;
    }

    /** 规则快照是否全空（null 安全；空 = 放行一切，中间件据此短路） */
    public static boolean isEmpty(SysRolePermission rule) {
        return rule == null
                || (safe(rule.getDeniedTools()).isEmpty()
                && safe(rule.getDeniedSkills()).isEmpty()
                && safe(rule.getDeniedSubagents()).isEmpty());
    }

    // ---------------- 更新（ADMIN） ----------------

    /** 更新角色规则（UPSERT）；ADMIN 拒绝（代码级全放行，配置无意义） */
    @Transactional
    public void update(String role, RoleRuleDTO rule) {
        if (role == null || role.isBlank()) {
            throw new ApiException(400, "角色不能为空");
        }
        String normalized = role.trim().toUpperCase();
        if (SysUser.ROLE_ADMIN.equals(normalized)) {
            throw new ApiException(400, "ADMIN 角色固定全量放行，无需配置");
        }
        SysRolePermission row = new SysRolePermission();
        row.setRole(normalized);
        row.setDeniedTools(rule == null ? List.of() : rule.deniedTools());
        row.setDeniedSkills(rule == null ? List.of() : rule.deniedSkills());
        row.setDeniedSubagents(rule == null ? List.of() : rule.deniedSubagents());
        row.setUpdateTime(LocalDateTime.now());
        if (ruleMapper.selectById(normalized) == null) {
            ruleMapper.insert(row);
        } else {
            ruleMapper.updateById(row);
        }
        log.info("角色权限已更新：{} -> tools={} skills={} subagents={}",
                normalized, row.getDeniedTools(), row.getDeniedSkills(), row.getDeniedSubagents());
    }

    // ---------------- 内部 ----------------

    /** 全放行空快照（fresh 实例防共享可变状态） */
    private static SysRolePermission emptyRule() {
        SysRolePermission rule = new SysRolePermission();
        rule.setDeniedTools(List.of());
        rule.setDeniedSkills(List.of());
        rule.setDeniedSubagents(List.of());
        return rule;
    }

    /** null → 空列表；剔除空白项并去重（DB 行可能含历史脏数据，容错对齐原 RoleRule 归一化语义） */
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

    private static List<String> safe(List<String> list) {
        return list == null ? List.of() : list;
    }
}
