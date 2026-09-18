# ontology-server 代码结构重构设计方案

> 范围：`llmOntology/ontology-server/src/main/java/com/conca/ontology`（约 160 个 Java 文件）
> 约束：**纯包结构移动** —— 只移动类到新包并更新 package/import 声明，不改类名、不改任何逻辑、不改 URL 路由、不改前端。

---

## 一、现状问题诊断

| # | 问题 | 证据 |
|---|------|------|
| 1 | **按技术分层组织，功能域被打散** | `controller/api` 下堆了 17 个控制器、`service` 下 22 个服务、`model` 下 20+ 个模型、`dto` 下 50+ 个 DTO，全是"大杂烩"。想理解"文件管理"一个功能要跨 4 个包跳转 |
| 2 | **service 是最大混乱源** | 22 个服务覆盖 8 个互不相关的功能域（图数据/文件/用户/Agent会话/内容安全/评测/备份/驾驶舱） |
| 3 | **model 混合抽象层次** | 领域模型（`ClassDef`/`GraphSchema`）与 JSON 持久化格式（`SchemaFile`/`UsersFile`）平铺混放 |
| 4 | **controller 分组逻辑不一致** | `api`/`llmapi`/`page` 是一种分组，`ontologyquery` 又按功能单独成包 |
| 5 | **auth 包只有 1 个类** | `SessionUser` 孤立在 auth 包，而 `AuthInterceptor` 在 config、`AuthController` 在 controller.api |
| 6 | **agent 包混合职责** | middleware 与 tool 无子包区分 |
| 7 | **eval 包反而结构最好** | 功能域内聚 + graders 继承体系，是其他域的范本 |

## 二、设计原则

1. **package-by-feature（按功能域分包）**：顶层包 = 业务域，一个功能的所有代码（controller/service/model/dto）收拢在一处。
2. **根包不变**：`com.conca.ontology` 保持不变，Spring Boot 组件扫描无需任何改动。
3. **行为完全等价**：不改类名、不改逻辑、不改 `@RequestMapping`、不改前端调用。
4. **域内子包统一约定**：

```
<domain>/
├── api/        # 管理端/页面 REST 控制器（/api/**）
├── llmapi/     # LLM 专属控制器（/api/llm/**）
├── service/    # 业务服务
├── model/      # 领域模型 + JSON 文件格式模型
├── dto/        # request/ view/ result/
└── <域特有>/    # middleware/ tool/ config/ graders/ disjoint/ ontologyquery/ 等
```
（每个域按需保留子包，不强求全有）

## 三、目标结构

```
com.conca.ontology
├── Ontology2Application.java          # 不动
│
├── shared/                            # 共享内核（零业务依赖）
│   └── web/
│       ├── ApiResponse.java           ← dto/ApiResponse
│       ├── ApiException.java           ← exception/ApiException
│       ├── GlobalExceptionHandler.java ← exception/GlobalExceptionHandler
│       ├── PageController.java         ← controller/page/PageController
│       └── OpenApiConfig.java          ← config/OpenApiConfig（内含 packagesToScan 需联动更新，见第五节）
│
├── user/                              # 用户与权限域
│   ├── web/                           # 认证是用户域的 Web 横切关注点
│   │   ├── SessionUser.java           ← auth/SessionUser
│   │   ├── AuthInterceptor.java       ← config/AuthInterceptor
│   │   └── WebMvcConfig.java          ← config/WebMvcConfig
│   ├── api/
│   │   ├── AuthController.java        ← controller/api/
│   │   ├── UserController.java        ← controller/api/
│   │   ├── RolePermissionController.java ← controller/api/
│   │   ├── UserPromptController.java  ← controller/api/
│   │   └── AppController.java        ← controller/api/（服务登录页初始化状态判断）
│   ├── service/
│   │   ├── UserService.java          ← service/
│   │   ├── RolePermissionService.java ← service/
│   │   └── UserPromptService.java    ← service/
│   ├── model/
│   │   ├── UserEntry.java, UsersFile.java          ← model/
│   │   ├── RoleRule.java, RolePermissionsFile.java ← model/
│   │   └── UserPromptEntry.java, UserPromptFile.java ← model/
│   └── dto/request/
│       ├── LoginRequest, RegisterRequest, PasswordChangeRequest,
│       │   ResetPasswordRequest, UserPatchRequest, InitRequest,
│       │   PromptSaveRequest, PromptToSkillRequest        ← dto/request/
│
├── graph/                             # 图谱核心域
│   ├── api/                           # SchemaController, GraphQueryController, GraphImportController ← controller/api/
│   ├── llmapi/                        # LlmSchemaController, LlmGraphController, ProfileController ← controller/llmapi/
│   ├── service/                       # SchemaService, GraphQueryService, GraphWriteService, GraphImportService, ProfileService ← service/
│   ├── model/                         # GraphSchema, ClassDef, PropertyDef, RelationDef, SchemaFile, ProfileEntry, ProfileFile ← model/
│   ├── config/
│   │   ├── GraphInitializer.java      ← config/
│   │   └── Neo4jConfig.java           ← config/
│   ├── dto/
│   │   ├── request/                   # NodeCreateRequest, NodeUpdateRequest, NodeBatchRequest, RelRequest, RelBatchRequest, QueryRequest, ProfileSaveRequest ← dto/request/
│   │   ├── view/                      # SchemaView, ClassView, ClassSummary, ClassDetailView, RelationView, RelationDetail, ProfileView, NodesPageView, NeighborsView, NeighborGroupsView, IncomingRelation, GraphStatsView ← dto/view/
│   │   └── result/                    # NodeCreateResult, NodeUpdateResult, NodeDeleteResult, RelCreateResult, RelUpdateResult, RelDeleteResult, BatchNodesResult, BatchRelsResult, QueryResult, ProfileSaveResult, ClassDeleteResult ← dto/result/
│   ├── disjoint/                      # 不相交规则子域
│   │   ├── api/                       # DisjointRulesController ← controller/api/
│   │   ├── llmapi/                    # LlmDisjointRulesController ← controller/llmapi/
│   │   ├── service/                   # DisjointRuleService ← service/
│   │   ├── tool/                      # DisjointRulesTool ← agent/
│   │   ├── model/                     # DisjointRule, DisjointRuleFile ← model/
│   │   └── dto/                       # DisjointCheckRequest（←dto/根）, view/DisjointConflictView
│   └── ontologyquery/                 # 查询服务子域
│       ├── OntologyQueryController.java    ← controller/ontologyquery/
│       ├── OntologyQueryExecutionService.java ← service/ontologyquery/
│       └── OntologyQueryRequest.java       ← dto/ontologyquery/
│
├── file/                              # 文件知识库域
│   ├── api/                           # FileController ← controller/api/
│   ├── llmapi/                        # LlmFileController ← controller/llmapi/
│   ├── service/                       # FileStorageService, MdConvertService ← service/
│   ├── model/                         # FileEntry, FileMetadataFile ← model/
│   └── dto/
│       ├── request/                   # FileDescRequest ← dto/request/
│       ├── view/                      # FileListView, LlmFileListView, LlmFileEntryView ← dto/view/
│       └── result/                    # FileUploadResult, FileUpdateResult, FileDeleteResult ← dto/result/
│
├── agent/                             # AI 会话域
│   ├── api/                           # AgentChatController, DashboardController, RewriteConfigController ← controller/api/
│   ├── service/                       # AgentChatService, AgentMemoryService, AgentSessionService, SessionDetailService, SessionTitleState, DashboardService, RewriteConfigService, RewriteAgentHolder ← service/ + agent/
│   ├── middleware/                    # ContentSecurityMiddleware, RolePermissionMiddleware ← agent/
│   ├── tool/                          # ChartPluginTool, HttpProxyTool ← agent/
│   ├── config/                        # AgentScopeConfig ← config/
│   ├── model/                         # RewriteConfigFile ← model/
│   └── dto/request/                   # AgentChatRequest, SessionTitleRequest ← dto/request/
│
├── content/                           # 内容安全域
│   ├── api/                           # ContentSecurityController ← controller/api/
│   ├── service/                       # ContentSecurityService ← service/
│   └── model/                         # model/content/ 下全部 9 个类（ContentSecurityRule, ContentSecurityCheckResult, PromptInjectionScanResult, ContentSecurityRuleHit, ContentSecurityRuleFile, ContentSecurityAllowlistFile, ContentRiskLevel, ContentDecision, ContentSeverity）← model/content/
│
├── eval/                              # 评测域（结构已良好，仅收拢 Controller 与 DTO）
│   ├── api/                           # EvalController ← controller/api/
│   ├── dto/request/                   # EvalSampleRequest, EvalHumanScoreRequest, EvalCustomGraderTestRequest, EvalCustomGraderRequest, EvalRunRequest, EvalDatasetRequest ← dto/request/
│   ├── dto/result/                    # FromChatSampleResult ← dto/result/
│   └── （其余 33 个类保持原样：EvalService, JudgeClient, Grader 体系, graders/** 继承树）
│
├── skill/                             # 技能域
│   ├── api/                           # SkillManageController ← controller/api/
│   ├── service/                       # SkillAnalysisService, SkillAnalysisStore, SkillUsageJudge ← skill/
│   └── model/                         # SkillAnalysis ← skill/
│
└── backup/                            # 备份域
    ├── api/                           # BackupController ← controller/api/
    └── service/                       # BackupService ← service/
```

## 四、归属决策说明（有争议的类）

| 类 | 决策 | 理由 |
|----|------|------|
| `AppController` | user/api | 依赖 `UserService.initialized()`，服务于登录页初始化向导模式切换；放 shared 会造成 shared→user 反向依赖 |
| `AuthInterceptor`/`SessionUser`/`WebMvcConfig` | user/web | 认证是用户域的 Web 横切关注点，保证 shared 零业务依赖 |
| `Neo4jConfig`/`GraphInitializer` | graph/config | 图数据库连接与 schema 初始化是 graph 域基础设施，与 SchemaService 同域内聚 |
| `DisjointRulesTool` | graph/disjoint/tool | 业务语义属于不相交规则子域，虽被 AgentScopeConfig 注册为 agent 工具（import 更新即可） |
| `DashboardService`/`SessionTitleState` 等 | agent/service | 均操作 HarnessAgent 会话状态（agent-state 落盘），属 AI 会话域 |
| `OntologyQuery*` 三件套 | graph/ontologyquery | 查询服务依赖图 schema，取消顶层 `controller/ontologyquery` 单独成包的不一致 |
| `PromptSaveRequest`/`PromptToSkillRequest` | user/dto/request | 由 UserPromptController 使用，属用户提示词功能 |

## 五、联动修改点（包移动之外必须同步的）

1. **OpenApiConfig.packagesToScan**（唯一硬性联动）：
   - `llmGroup`：`com.conca.ontology.controller.llmapi` → `com.conca.ontology.graph.llmapi, com.conca.ontology.file.llmapi, com.conca.ontology.graph.disjoint.llmapi`
   - `adminGroup`：`com.conca.ontology.controller.api` → 列举各域 api 包（`user.api, graph.api, file.api, agent.api, content.api, eval.api, skill.api, backup.api`）
2. **全局 import 更新**：所有引用被移动类的文件更新 import（IDE Move Refactor 可自动完成，脚本批量处理亦可）。
3. **无需改动**：`application.yml`（无包名引用，已验证）、前端 `static/pages/**`（URL 不变）、`@ComponentScan`（根包不变，自动覆盖所有新包）。

## 六、迁移步骤（分批执行，每批后编译验证）

| 批次 | 内容 | 验证 |
|------|------|------|
| 0 | 准备：确认工作区干净（git status），当前可编译基线 | `mvnw -q clean compile` |
| 1 | shared/web + user 域（含 auth 收拢、OpenApiConfig 联动更新） | 同上 |
| 2 | graph 域（含 disjoint、ontologyquery 子域） | 同上 |
| 3 | file + content 域 | 同上 |
| 4 | agent 域（middleware/tool/config 拆分） | 同上 |
| 5 | eval + skill + backup 域收尾 | 同上 |
| 6 | 清理：删除空的旧目录（controller/、service/、model/、dto/、auth/、exception/、config/ 残留） | 同上 + 冒烟 |

每批操作模式：`git mv` 移动文件 → 修改文件 package 声明 → 全局更新引用 import → 编译。

## 七、验证方案

1. **编译验证**（每批）：`cd llmOntology && mvnw.cmd -q clean compile`
2. **包结构核查**（批次 6 后）：Glob 确认无旧包残留、无孤儿文件
3. **启动冒烟**（可选，需本地 Neo4j 运行）：
   - 启动 `mvnw.cmd spring-boot:run -pl ontology-server`（或直接运行 jar）
   - 访问 `http://localhost:9876/` → 跳转登录页、admin 登录成功
   - 抽查各域核心接口：`/api/session`（user）、`/api/schema`（graph）、`/api/files`（file）、`/api/llm/schema`（llmapi）
   - 访问 `/swagger-ui.html` 确认 llm/admin 两个分组接口列表完整（验证 packagesToScan 联动正确）
4. **回归确认**：git diff 仅含 package 声明、import 语句、文件路径三类变化（可抽查 `git diff --stat` + 关键文件 diff）

## 八、风险与回滚

- **风险低**：纯移动操作，无逻辑变更；唯一的语义联动点（OpenApiConfig.packagesToScan）已识别。
- **回滚**：分批 git mv + 独立 commit，任一批出问题 `git revert` 该批即可，不影响其他批次。
