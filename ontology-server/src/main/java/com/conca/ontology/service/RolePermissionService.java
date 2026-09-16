package com.conca.ontology.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.RolePermissionsFile;
import com.conca.ontology.model.RoleRule;
import com.conca.ontology.model.UserEntry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 角色权限管理：data/role-permissions.json 唯一事实源
 * （version + tmp 原子写 + synchronized，与 UserService 同款风格），
 * 黑名单模式——清单命中的 tool / skill / subagent 对该角色禁用。
 *
 * ADMIN 固定全量放行：判定入口短路，不读配置文件，误改文件不影响管理员。
 * 规则修改即时生效：RolePermissionMiddleware 每次工具调用实时判定，
 * SkillFilter 每次会话构建时重新读取，无需重启。
 *
 * 三类资源的触发通道（onActing 拦截口径）：
 * - tool：直接按工具名匹配（如 build_chart）；
 * - subagent：agent_spawn 工具的 agent_id 参数；
 * - skill：load_skill_through_path 的 skillId 参数，SkillCatalog 键格式为
 *   {name}_{source}（如 ontology-builder_workspace），故按 name 与 name_ 前缀双重匹配。
 */
@Service
public class RolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionService.class);

    private final ObjectMapper mapper;

    @Value("${app.auth.role-permissions-file:data/role-permissions.json}")
    private String permissionsFile;

    public RolePermissionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------- 查询 ----------------

    /** 全部角色规则 */
    public synchronized Map<String, RoleRule> all() {
        return load().roles();
    }

    /** 指定角色的规则；ADMIN / 未知角色返回空规则（全放行） */
    public synchronized RoleRule ruleOf(String role) {
        if (role == null || UserEntry.ROLE_ADMIN.equals(role)) {
            return RoleRule.EMPTY;
        }
        return load().roles().getOrDefault(role, RoleRule.EMPTY);
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
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        for (String denied : ruleOf(role).deniedSkills()) {
            if (skillId.equals(denied) || skillId.startsWith(denied + "_")) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 更新（ADMIN） ----------------

    /** 更新角色规则并落盘；ADMIN 拒绝（代码级全放行，配置无意义） */
    public synchronized void update(String role, RoleRule rule) {
        if (role == null || role.isBlank()) {
            throw new ApiException(400, "角色不能为空");
        }
        String normalized = role.trim().toUpperCase();
        if (UserEntry.ROLE_ADMIN.equals(normalized)) {
            throw new ApiException(400, "ADMIN 角色固定全量放行，无需配置");
        }
        RoleRule safe = rule == null ? RoleRule.EMPTY : rule;
        RolePermissionsFile current = load();
        Map<String, RoleRule> roles = new LinkedHashMap<>(current.roles());
        roles.put(normalized, safe);
        save(new RolePermissionsFile(current.version() + 1, roles));
        log.info("角色权限已更新：{} -> tools={} skills={} subagents={}",
                normalized, safe.deniedTools(), safe.deniedSkills(), safe.deniedSubagents());
    }

    // ---------------- 文件读写 ----------------

    /** 读取；文件不存在时落盘默认规则（USER 禁用 build_chart / ontology-builder / academic-anthropologist） */
    private RolePermissionsFile load() {
        Path p = Path.of(permissionsFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            RolePermissionsFile init = defaultFile();
            try {
                save(init);
            } catch (Exception e) {
                // 落盘失败降级：仅本次以内存默认规则运行，下次启动重试初始化
                log.warn("角色权限默认文件写入失败（本次以默认规则运行）：{}", e.getMessage());
            }
            return init;
        }
        try {
            RolePermissionsFile f = mapper.readValue(
                    Files.readString(p, StandardCharsets.UTF_8), RolePermissionsFile.class);
            return new RolePermissionsFile(f.version(), f.roles());
        } catch (IOException e) {
            throw new ApiException(500, "角色权限文件读取失败: " + e.getMessage());
        }
    }

    private void save(RolePermissionsFile f) {
        Path p = Path.of(permissionsFile).toAbsolutePath().normalize();
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(f),
                    StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, "角色权限文件写入失败: " + e.getMessage());
        }
    }

    private static RolePermissionsFile defaultFile() {
        return new RolePermissionsFile(1, Map.of(UserEntry.ROLE_USER, new RoleRule(
                List.of("build_chart"),
                List.of("ontology-builder"),
                List.of("academic-anthropologist"))));
    }
}
