package com.conca.ontology.user.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.conca.ontology.user.entity.SysRolePermission;
import com.conca.ontology.user.mapper.SysRolePermissionMapper;
import com.conca.ontology.user.model.RolePermissionsFile;
import com.conca.ontology.user.model.RoleRule;
import com.conca.ontology.user.model.UserEntry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * role-permissions.json → sys_role_permission 存量迁移（P1）。
 * 全新部署（表空且无 JSON）时落默认规则，对齐原文件存储语义：
 * USER 禁用 build_chart 工具及 ontology-builder / academic-anthropologist 技能。
 */
@Component
@Order(20)
public class RolePermissionMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionMigrator.class);

    private final ObjectMapper mapper;
    private final SysRolePermissionMapper ruleMapper;
    private final String permissionsFile;

    public RolePermissionMigrator(ObjectMapper mapper, SysRolePermissionMapper ruleMapper,
            @Value("${app.auth.role-permissions-file:data/role-permissions.json}") String permissionsFile) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
        this.permissionsFile = permissionsFile;
    }

    @Override
    public String name() {
        return "role-permissions";
    }

    @Override
    public Path sourceFile() {
        return Path.of(permissionsFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return ruleMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        RolePermissionsFile f = mapper.readValue(Files.readString(sourceFile()), RolePermissionsFile.class);
        if (f.roles() == null || f.roles().isEmpty()) {
            log.info("role-permissions.json 无角色规则，跳过导入");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        f.roles().forEach((role, rule) -> ruleMapper.insert(toRow(role, rule, now)));
        log.info("role-permissions.json 导入完成：{} 个角色", f.roles().size());
    }

    @Override
    @Transactional
    public void initDefaults() {
        ruleMapper.insert(toRow(UserEntry.ROLE_USER, new RoleRule(
                java.util.List.of("build_chart"),
                java.util.List.of("ontology-builder"),
                java.util.List.of("academic-anthropologist")), LocalDateTime.now()));
        log.info("默认角色规则已初始化：USER 禁用 build_chart / ontology-builder / academic-anthropologist");
    }

    private static SysRolePermission toRow(String role, RoleRule rule, LocalDateTime now) {
        SysRolePermission row = new SysRolePermission();
        row.setRole(role);
        row.setDeniedTools(rule.deniedTools());
        row.setDeniedSkills(rule.deniedSkills());
        row.setDeniedSubagents(rule.deniedSubagents());
        row.setUpdateTime(now);
        return row;
    }
}
