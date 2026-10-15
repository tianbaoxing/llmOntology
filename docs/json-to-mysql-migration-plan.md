# llmOntology 平台数据迁移方案（JSON 文件 → MySQL）

> 本文是**持续性迁移规范**：P0~P7 已实施完成，P8 起各期照此执行，不再逐期重新设计。
> 实施日期：2026-10-10 ｜ 进度：P0 ✅ P1 ✅ P2 ✅ P3 ✅ P4 ✅ P5 ✅ P6 ✅ P7 ✅（备份 DB 化）

## 1. 基础设施（已定，勿改）

| 项 | 值 |
|---|---|
| 数据库 | Docker 容器 `ontology-mysql`（镜像 mysql:8.0），宿主机端口 **3306**，root/root，库 `ontology` |
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
├─ name()            日志标识             启动末尾（Flyway 建表之后）逐项调度：
├─ sourceFile()      源 JSON 路径          ① 表非空 → 跳过（幂等）；源仍在则 WARN
├─ targetTableEmpty() 目标表是否空        ② 表空 + 源存在 → importAll() + archive()
├─ importAll()       JSON → 表（@Transactional）│  （默认整体 rename *.migrated 保回退现场）
├─ initDefaults()    全新部署默认数据     ③ 表空 + 无源 → initDefaults()
├─ sourcePresent()   源是否待迁移（默认 exists）④ 任一失败 → 抛异常阻断启动
└─ archive()         归档（默认整体 rename；混合目录源逐文件归档）
```
P5b 起骨架支持**混合目录源**：目录常驻但仅部分文件需迁移（如 data/agents 的 workspace/state 长期存在），实现覆盖 `sourcePresent()`（是否存在至少一份待迁移文件）与 `archive()`（逐文件 rename，保留目录骨架）。

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
| P5a | 用户提示词 | user_prompt（1 行，每条提示词一行，无全局 version；id 为源 UUID 主键，user_id 取自源文件名，idx_user_updated 覆盖列表查询；update/delete 以 id+userId 双条件定位保持用户隔离；**首个目录型迁移源**：JsonMigrationRunner 判断由 isRegularFile 放宽为 exists，归档整个 prompts 目录为 prompts.migrated） | data/prompts/（按用户一文件） |
| P5b | 智能体定义 | agent_definition（3 行，每智能体一行；agent_id 主键，model 拆 base_url/model_name/api_key_ref 三列，skills/tools/httpTools/mcps/subagents 五个类型化 JSON 保序列，created_at/updated_at 存 ISO 原文保纳秒，idx_updated_at 支撑列表倒序；行内 version 为每智能体独立定义版本（编辑 +1）故保留，非遗留全局 version；**首个混合目录源**：骨架新增 sourcePresent()/archive() 默认方法，AgentDefinitionMigrator 覆盖二者，仅归档各 {id}/agent.json 为 agent.json.migrated，workspace/state 目录保留文件系统；AgentDefinitionStore 存储层替换，dirOf/validateId/404 文案不变，delete 改为删行+best-effort 清目录（DB 为准）；AgentDefinition 为外部框架 record（agentkit），经 Converter 互转零修改） | data/agents/{id}/agent.json（3 个） |
| P6a | 自定义评测器 | custom_grader（1 行，每评测器一行；name 主键，弃文档初稿 definition JSON 列改扁平拆列——description/mode/prompt + created_at/updated_at 存 ISO 原文保纳秒；created_at NULLable 保真新建记录缺省 null 语义（validate 不设创建时间），updated_at 恒有值 NOT NULL；CustomGraderStore 存储层替换，all 按名称字典序（与注册表 TreeMap 同序），validate/409/404 文案不变；**连锁调整**：EvalGraderRegistry 自定义评测器注册从构造器移至 ApplicationReadyEvent——Bean 构造期早于 JsonMigrationRunner（ApplicationRunner），构造时查表会漏掉存量迁移未导入的定义，ready 事件在所有 Runner 之后触发保证全量注册，create/update/delete 的 registerCustom/replaceCustom/removeCustom 热更新路径不变） | data/eval/custom-graders.json |
| P6b | 评测结果 | eval_run（12 行，每任务一行，run_id 主键=文件名）+ eval_run_sample（100 行，每样本一行；自增 id 兼作 samples 数组原序，UNIQUE(run_id,sample_id)）；三代结果文件 schema 泛化——老 4 文件 status/judge/human*/datasetHash/percent NULL 保真（读路径 NULL 列省略键、老代 status 按 success 处理、summary 按 8 键还原；新 6 文件全字段齐备、summary 12 键）；summary 拆 12 列全 NULLable（judge 嵌套整 JSON 列，error/agent_error 用 TEXT 无长度上限）；样本拆列——query/answer MEDIUMTEXT、reference_response JSON（Object 类型保真 String 或嵌套 JSON 值）、tool_calls/scores 类型化 JSON NOT NULL（缺失归一 []）、rounds/durationMs/promptTokens/completionTokens NOT NULL（缺失归一 0）、turns/dialogue/responseSource/tolerance/agent_error NULLable 省略键、**老格式 samples[].expected 死字段直接丢弃（用户拍板）**；started_at/finished_at 存 ISO 原文保纳秒；EvalRunService 存储层替换（公共签名/400/404/409 文案不变）：start 首写 INSERT running 行（僵尸检测改查表 interrupted）、writeTaskMeta 改 UPDATE（null 不覆盖）、completeRun TransactionTemplate 批量插样本后更新 run 行、humanScore 落库（pending 递减+全评完状态翻 success，metadata 纳秒往返）、deleteRun tx 删两表（remaining 回归，deletedBytes=0 前端不消费）、readResult 按 NULL 列省略键重建原文件键序（progress/download 语义与源文件逐键一致）；EvalController.download 改调 exportResult（删 requireResultFile）；listRuns 改 runMapper ORDER BY run_id 倒序（=原文件名倒序，含 _live/_zombie/interrupted 语义）；application.yml 增 app.eval.result-dir 迁移源键；无 initDefaults（零行=无历史任务）；**目录源整体归档**：results 整目录 rename 为 results.migrated（P5a 先例），EvalRunMigrator 覆盖 sourcePresent()（目录内含 eval-{date}-{time}-{hex}.json 即视为存在） | data/eval/results/eval-*.json（12 个） |
| P6c | 评测数据集 | dataset（3 行，每数据集一行，name 主键=原 .jsonl 文件名；不加 created_at——源无时间戳 UI 不显示，样本数走 COUNT(*) 派生）+ dataset_sample（17 行，每非空行一样本；自增 id 兼作文件行序，seq=按 id 升序行号，中删后自然前移=原文件删行语义，KEY(dataset_name,id)；sample_id NULLable 无 UNIQUE——原行无 id 键读取按 line-{seq} 兜底，id 查重走写路径预检 409 文案不变；query/response NULLable 多轮样本无此键、reference_response JSON Object 保真、turns/skills/tools/mcps 类型化 JSON **NULL=原行无此键**（区别于 eval_run_sample 的 NOT NULL 归一[]——数据集以文件行保真为先）、rubric NULLable 保真；全列 updateStrategy=IGNORED 支持整行覆写清空回写）；EvalDatasetService 存储层替换（公共签名/校验/400/404/409 文案/响应键序不变）：readAllSamples→rowsOf+viewRow 视图重建（raw 按原文件键序重建，**17/17 行与源文件字节级一致**）、buildSampleLine→buildSampleEntity（id 自动生成/查重含 line-{seq} 伪 id 同构）、update/delete 按 seq 定位行、loadSamples 查表拼装（评测引擎零改动）、requireDataset Path→void（RE 校验+404 不变）、删 datasetPath/countLines 文件 API；连锁：EvalCustomGraderService.findGraderReferences 文件扫描→datasetService.searchGraderReferences 查表（needle `"名"` 在 raw JSON 转义下数组值命中/文本内嵌引号不命中，与原文件 contains 语义逐字节等价，输出「数据集名 第N行」格式不变）；EvalDatasetMigrator @Order(90)（目录源整体归档 datasets.migrated，行解析 DatasetSampleEntity.of 与 loadSamples 同构、坏行阻断启动）；application.yml 增 app.eval.dataset-dir；无 initDefaults | data/eval/datasets/*.jsonl（3 个） |
| P7 | 备份恢复适配 | zip 新增 `db/` 目录（20 表 JSONL，非新 DB 表）；BackupService 新增 exportTables：表清单 information_schema 自动发现（**排除 flyway_schema_history**——迁移簿记，恢复时目标库 Flyway 自管，带上必 checksum 冲突），JdbcTemplate `SELECT *` 通用导出**零逐表代码**（P8+ 新表自动进备份），JSON 列 readTree 嵌套化（非转义字符串），DATETIME→ISO（注入的 Spring mapper 自带 jsr310），空表写空 .jsonl 占位（区分"0 行"与"表缺失"）；copyDataAndFiles 排除 `*.migrated` 归档（inMigrated 按 data 根相对路径任一段判定，30 个死档不进包——已 DB 化数据走 db/ 导出）；进度五段重分配 nodes 0-35/rels 35-70/db 70-75/files 75-85/zip 85-100，新增 phase=`db`（前端只消费 percent/detail 零改动）；zip 文件名格式与列表/删除/下载 API 零改动（f 数语义=排除归档后有效文件数）；**恢复维持手工流程**（用户拍板 2026-10-13，与图恢复现状一致）：解压 → files/+data/ 拷回 → db/*.jsonl 导表（无 FK 任意序）。已验证（2026-10-13，n6684 r23082 f535 v14）：db/ 20 表 JSONL 行数与 COUNT(*) **20/20 精确一致（268 行）**、zip 内零 .migrated 条目、rule_dedup/agent_definition 的 JSON 列嵌套+中文+纳秒 ISO 时间无损、列表解析与下载正常 | — |

## 5. 待迁移清单（按期）

### P4 文件/文档元数据
| 源文件 | 目标表 | 说明 |
|---|---|---|
| ~~data/file-metadata.json (3.1K)~~ ✅ 已迁移 | file_metadata | files/ 与 files-md/ **本体保留文件系统**（LLM 按路径访问，硬约束），只迁元数据 |
| ~~data/skill-analysis.json (4.2K)~~ ✅ 已迁移 | skill_analysis | skill_name 主键；按字段拆列（放弃单 content JSON 列）：tools 拆 used/not_used，六个清单列类型化 JSON 保序，hidden* 可空保真 |

### P5 提示词 + 智能体定义
| 源文件 | 目标表 | 说明 |
|---|---|---|
| ~~data/prompts/*.json（按用户一文件，现 1 个）~~ ✅ 已迁移 | user_prompt | id/user_id/title/content + created_at/updated_at；无全局 version |
| ~~data/agents/{id}/agent.json（现 3 个）~~ ✅ 已迁移 | agent_definition | 22 列拆列方案（V9）；workspace/、state/ **目录结构保留**（框架仍用） |

### P6 评测
| 源文件 | 目标表 | 说明 |
|---|---|---|
| ~~data/eval/custom-graders.json (2K)~~ ✅ 已迁移 | custom_grader | name 主键；扁平拆列（弃 definition JSON 列）；EvalGraderRegistry 注册移至 ApplicationReadyEvent |
| ~~data/eval/results/eval-*.json（现 12 个）~~ ✅ 已迁移 | eval_run + eval_run_sample | 每任务一文件拆两表；listRuns 由文件名扫描改 ORDER BY run_id 倒序（run_id=文件名，字典序=时间序，规划初稿的 created_at 未采用） |
| ~~data/eval/datasets/*.jsonl（现 3 个）~~ ✅ 已迁移 | dataset + dataset_sample | 已确认（2026-10-12）：入库拆两表；评测引擎 loadSamples 改查表拼装（EvalRunService 零改动） |

### P7 备份恢复适配
- ~~BackupService zip 增加 `db/` 目录（全表导出 JSONL）~~ ✅ 已完成；恢复维持手工流程（用户拍板 2026-10-13）：解压 → files/+data/ 拷回 → db/*.jsonl 导表。

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
