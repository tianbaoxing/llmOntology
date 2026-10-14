-- Migrator 本体退役（P0~P8 存量迁移全部完成）：原 RolePermissionMigrator.initDefaults
-- 的全新部署默认值移植——空表时落 USER 角色黑名单：禁用 build_chart 工具及
-- ontology-builder / academic-anthropologist 技能（对齐原文件存储语义）。
-- 幂等：role 为主键，已存在 USER 行（含已迁移存量库）时 INSERT IGNORE 跳过，零变化。
INSERT IGNORE INTO sys_role_permission (role, denied_tools, denied_skills, denied_subagents, update_time)
VALUES ('USER', '["build_chart"]', '["ontology-builder"]', '["academic-anthropologist"]', NOW());
