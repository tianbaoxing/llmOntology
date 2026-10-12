# llmOntology 平台数据迁移方案（JSON 文件 → MySQL）

> 本文是**持续性迁移规范**：P0~P4 已实施完成，P5 起各期照此执行，不再逐期重新设计。
> 实施日期：2026-10-10 ｜ 进度：P0 ✅ P1 ✅ P2 ✅ P3 ✅ P4 ✅

## 1. 基础设施（已定，勿改）

| 项 | 值 |
|---|---|
| 数据库 | Docker 容器 `ontology-mysql`（镜像 mysql:8.0），宿主机端口 **3307**，root/root，库 `ontology` |
| ORM | MyBatis-Plus 3.5.7（`mybatis-plus-spring-boot3-starter`），Mapper 用 `@Mapper` 注解自动扫描（无配置类） |
| DDL 管理 | Flyway 9.22.3（Boot 3.2.5 BOM 管理，**勿升级 10+**），脚本 `ontology-server/src/main/resources/db/migration/V(n)__xxx.sql` |
| 连接配置 | [application.yml](ontology-server/src/main/resources/application.yml) `spring.datasource`，Hikari 池 10/2/15s/30min |

约束：
- **MySQL 5.7 不可用**：Flyway 社区版（9.x 与 8.5.x）均对 5.7 抛 `FlywayEditionUpgradeRequiredException`，必须 MySQL 8.0+。
- 表排序规则统一 `utf8mb4_bin`（区分大小写，精确对齐 Java `equals` 语义）；忽略大小写的查询在 SQL 显式 `LOWER(列) = LOWER({0})`。
- 时间列 `DATETIME`，实体 `LocalDateTime`，对外 API 仍输出 `yyyy-MM-dd HH:mm:ss` 字符串（前端零感知）。
- 主键均为业务键：`@TableId(type = IdType.INPUT)`，全局 `id-type: input`。
- 复杂嵌套结构用 JSON 列 + `JacksonTypeHandler`（实体需 `@TableName(autoResultMap = true)`）；MySQL 8.0 JSON 列不允许 DEFAULT，列定义 `NOT NULL`，插入必须全字段。

## 2. 迁移骨架契约（后续各期照此挂载）

骨架位于 `ontology-server/src/main/java/com/conca/ontology/shared/db/migration/`，**已实现，各期零改动**：

```
JsonMigrator（接口）                      JsonMigrationRunner（ApplicationRunner）
├─ name()          日志标识               启动末尾（Flyway 建表之后）逐项调度：
├─ sourceFile()    源 JSON 路径            ① 表非空 → 跳过（幂等）；JSON 仍在则 WARN
├─ targetTableEmpty() 目标表是否空          ② 表空 + JSON 存在 → importAll() + 归档
├─ importAll()     JSON → 表（@Transactional）│      （rename 为 *.migrated，保留回退现场）
└─ initDefaults()  全新部署默认数据         ③ 表空 + 无 JSON → initDefaults()
                                          ④ 任一失败 → 抛异常阻断启动
```

各期只需新增（以 P2 图谱配置为例）：
1. `V(n)__xxx.sql` 建表脚本
2. `xxx/entity/` + `xxx/mapper/`（MP 标准）
3. `xxx/migration/XxxMigrator.java`：`@Component @Order(30)`（每期递增 10），实现接口
4. 改造对应 Service：`load()/save()` 整文件读写 + `synchronized` → Mapper 行级 CRUD + `@Transactional`；**公共方法签名、校验、文案、保护逻辑全部不变**（Controller/前端零改动）

## 3. 每期实施 SOP

1. 建表脚本 V(n)（只增不改历史脚本，Flyway checksum 校验）
2. 实体/Mapper/转换器（模型 record 不动，新增 Converter 做 Entity↔Model 时间与格式转换）
3. Migrator（`importAll` 加 `@Transactional` 保证原子导入；全新部署有默认数据的实现 `initDefaults()`）
4. Service 存储层替换（参考 [UserService.java](ontology-server/src/main/java/com/conca/ontology/user/service/UserService.java) 改造版）
5. `mvnw compile -pl ontology-server -am`（**勿用 IDE 构建**；若 `clean` 被 IDE 的 JDT 锁失败，手动删 `target/classes` 再编译）
6. 重启服务，确认日志：Flyway applied + `[xxx] 存量迁移完成`，检查 `.migrated` 归档
7. API 回归（前端页面全功能）+ `docker exec ontology-mysql mysql ...` 核对库内数据

## 4. 已完成

| 期 | 内容 | 表 | 源文件（已归档） |
|---|---|---|---|
| P0 | 依赖/datasource/Flyway/迁移骨架 | flyway_schema_history | — |
| P1 | 用户系统 | sys_user（5 行）、sys_role_permission（1 行） | users.json、role-permissions.json |
| P2 | 图谱配置 | graph_schema_meta（1 行，全局 version）+ graph_class（10 行）/ graph_class_prop（30 行）/ graph_rel_def（16 行）；ontology_profile（11 行，每条问答一行，无全局 version——V3 调整，去 JSON 列）；rule_dedup（1 行，3 个 JSON 列）；rule_disjoint（12 行，每条规则一行，无全局 version——V4 调整，去 JSON 列） | graph-schema.json、ontology-profile.json、dedup-rules.json、disjoint-rules.json |
| P3 | 内容安全 + 改写配置 | rule_content_security（30 行，每条规则一行，无全局 version；allowlist 为行内 allowlisted 标记列，ord 列保序）；rewrite_config（4 行，每功能一行，无全局 version） | content-security-rules.json、rewrite-config.json |
| P4 | 文件元数据 + 技能使用分析 | file_metadata（7 行，每文件一行，无全局 version；自增 id 兼作列表顺序，uk_name 唯一键；转换四列可空 + updateStrategy=IGNORED 支持清空回写；FileListView/LlmFileListView 及三个 Result DTO 已删 version 字段）；skill_analysis（3 行，每技能一行，无全局 version；skill_name 主键；analyzed_at/content_hash 可空（keyInfo-only 记录），analyzed_at 存 ISO 原文保纳秒；tools 拆 used/not_used 两列，六个清单列类型化 JSON 保序，hidden* 三列 NULLable 保真旧数据） | file-metadata.json、skill-analysis.json |

## 5. 待迁移清单（按期）

### P4 文件/文档元数据
| 源文件 | 目标表 | 说明 |
|---|---|---|
| ~~data/file-metadata.json (3.1K)~~ ✅ 已迁移 | file_metadata | files/ 与 files-md/ **本体保留文件系统**（LLM 按路径访问，硬约束），只迁元数据 |
| ~~data/skill-analysis.json (4.2K)~~ ✅ 已迁移 | skill_analysis | skill_name 主键；按字段拆列（放弃单 content JSON 列）：tools 拆 used/not_used，六个清单列类型化 JSON 保序，hidden* 可空保真 |

### P5 提示词 + 智能体定义
| 源文件 | 目标表 | 说明 |
|---|---|---|
| data/prompts/*.json（按用户一文件，现 1 个） | user_prompt | id/user_id/title/content |
| data/agents/{id}/agent.json（现 3 个） | agent_definition | agent_id 主键 + definition JSON 列；workspace/、state/ **目录结构保留**（框架仍用） |

### P6 评测
| 源文件 | 目标表 | 说明 |
|---|---|---|
| data/eval/custom-graders.json | custom_grader | name 主键 + definition JSON 列 |
| data/eval/results/eval-*.json（现 12 个） | eval_run + eval_run_sample | 每任务一文件拆两表；listRuns 由文件名扫描改 ORDER BY created_at |
| data/eval/datasets/*.jsonl（3 个） | （待定） | 建议入库拆 dataset/dataset_sample；或保留文件——**实施 P6 前与用户确认** |

### P7 备份恢复适配
- BackupService zip 增加 `db/` 目录（全表导出 JSONL）；恢复 = 导表 + 还原 files 目录。

### P8（可选，需用户确认）框架会话状态
- agent-state/{userId}/{sessionId}/agent_state.json → 实现 `MysqlAgentStateStore implements AgentStateStore`（8 个方法，三段 String key 映射三列）；log.jsonl 转录仍在文件。

## 6. 永不迁移（保留文件系统）

| 路径 | 原因 |
|---|---|
| files/、files-md/ | LLM 按路径访问原文件/转换后 MD（项目硬约束） |
| skills/（含 data/agent-workspace/skills） | 技能是目录结构（SKILL.md+资源），LLM 按路径加载 |
| 各 workspace 的 log.jsonl | 框架 append-only 转录，双源合并展示依赖它 |
| backups/ | 备份产物本身 |

## 7. 通用验证清单（每期必做）

1. 编译：`mvnw compile`（产物需含新类）
2. 启动日志：Flyway `Successfully applied` + `[xxx] 存量迁移完成` + 源 JSON 归档 `.migrated`
3. 库内行数 = 原 JSON 条目数
4. 对应前端页面全功能回归（读写路径都过 DB）
5. 幂等重放：重启一次，日志应显示"目标表已有数据，跳过"
6. admin/admin123 登录仍正常（P1 用户表是其他所有验证的前提）
