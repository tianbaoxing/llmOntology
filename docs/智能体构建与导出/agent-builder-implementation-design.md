# 方案 1 落地设计：引导式搭建智能体 + 平台运行 + 导出独立运行

> 日期：2026-09-24（2026-10-15 修正对齐已落地实现：定义存储迁 MySQL（P5b）、密钥改环境变量注入、会话状态迁 MySQL（P8）、独立交付改为 L1 导出包 + L2 导出工程——原"shade jar 追加"方案未采用）
> 前置文档：[agent-builder-and-export-design.md](agent-builder-and-export-design.md)（方案选型与业界调研）
> 需求：用户输入 9 个槽位（提示词、大模型地址、模型名字、key、skills、mcp、子 agent、工具、开场白），搭建的智能体既能在平台内运行，也能导出独立运行（实现为可二次开发的 AgentScope 2.0 原生源码工程；"单 jar"形态未落地）。
> 实施前提：agentscope-harness 使用 2.0.3 正式版（Maven Central，母 pom `agentscope.version=2.0.3`；修复 Windows GBK 解码与 working\_directory 单引号死锁）。

***

## 一、需求槽位与数据模型

### 1.1 AgentDefinition（智能体定义，MySQL 持久化）

定义本体为 runtime 模块 record `com.conca.agentkit.AgentDefinition`（[AgentDefinition.java](../../ontology-agent-runtime/src/main/java/com/conca/agentkit/AgentDefinition.java)），持久化到 MySQL `agent_definition` 表（[V9__p5_agent_definition.sql](../../ontology-server/src/main/resources/db/migration/V9__p5_agent_definition.sql)，每智能体一行拆列存储）；workspace（skills、subagents）仍为文件系统目录。定义的内存/API 视图：

```json
{
  "id": "agent-5f71c5b9",
  "name": "文物问答助手",
  "description": "面向国博藏品的问答智能体",
  "sysPrompt": "你是「文物问答助手」……",
  "greeting": "你好，我可以帮你查询馆藏文物，请问想了解什么？",
  "model": {
    "baseUrl": "https://openrouter.ai/api/v1",
    "modelName": "deepseek/deepseek-v4-flash",
    "apiKeyRef": ""
  },
  "maxIters": 6,
  "skills": ["ontology-query", "xxx"],
  "tools": ["chart-plugin", "graph-query"],
  "httpTools": [
    { "name": "weather", "description": "查询天气", "endpoint": "http://host:port/api/x",
      "method": "POST", "pathPrefix": "/api/x/" }
  ],
  "mcps": [
    { "name": "weather", "url": "http://localhost:8766/mcp", "transport": "streamable" }
  ],
  "subagents": [
    { "name": "academic-historian", "description": "史学考据", "emoji": "📚",
      "color": "blue", "prompt": "你是严谨的史学考据子智能体……" }
  ],
  "projectName": "RelicAssistant",
  "packageName": "com.nmc.relic",
  "status": "test",
  "version": 1,
  "minHarnessVersion": "2.0.3",
  "createdAt": "2026-09-24T10:00:00+08:00", "owner": "admin"
}
```

与原设计的关键差异：

- **model.apiKeyRef 恒为空串**：密钥不进定义、不落库、不落盘，运行时统一经环境变量 `AGENT_API_KEY` 注入；导出视图（`forExport()`）写死占位符 `${AGENT_API_KEY}`；
- **subagents 是 SubagentSpec 对象数组**（name/description/emoji/color/prompt，落盘为 frontmatter .md），不是字符串清单；
- 新增 `projectName`/`packageName`（L2 导出工程名与 Java 包名）与 `status`（test=试运行不上架 / active=上架进 chat 页）；`minHarnessVersion` 为顶层字段（原设计的 runtime 嵌套对象未采用）。

拆列映射：model 拆 base_url/model_name/api_key_ref 三列；skills/tools/httpTools/mcps/subagents 为五个类型化 JSON 列（保序，空即 []）；created_at/updated_at 存 ISO-8601 原文（保纳秒）；行内 version 为每智能体独立定义版本（编辑 +1）；idx_updated_at 支撑管理列表更新时间倒序。

### 1.2 槽位映射表（9 项需求逐一落点）

| 用户输入 | 字段 | 平台内运行载体 | 导出包载体 | 关键点 |
| ----- | ----- | ----- | ----- | ----- |
| 提示词 | sysPrompt | Bootstrapper.effectivePrompt（空时回退默认人格模板） | agent.json（L1）/ application.yml（L2） | 过 ContentSecurityMiddleware 审查；不入开场白逻辑 |
| 大模型地址 | model.baseUrl | OpenAIChatModel.baseUrl | 同上 | 必须 OpenAI 兼容协议；保存仅静态校验 URL 格式，连通性走独立 test-model 端点（可选） |
| 模型名字 | model.modelName | OpenAIChatModel.modelName | 同上 | — |
| key | （不进定义，apiKeyRef 恒空串） | 运行时统一经环境变量 `AGENT_API_KEY` 注入（requireEnvApiKey，未设置给 503 确定性报错） | 写死占位符 `${AGENT_API_KEY}` | 密钥不落库不落盘；详情接口无密钥字段（无需 mask）；独立运行同用该环境变量 |
| skills | skills\[] | 构建时**拷贝**所选技能到 agent workspace/skills/ | skills/ 原样拷出 | 见 2.1 拷贝策略 |
| mcp | mcps\[] | Toolkit 注册（McpSupport，Streamable→SSE 兜底） | 端点 URL 入包 | 失败降级告警不阻断（沿用现有模式） |
| 子 agent | subagents\[]（SubagentSpec 对象数组） | workspace/subagents/\*.md | subagents/ | 按定义生成 frontmatter .md（name/description/emoji/color + 人格 prompt）；数量上限 ≤3 |
| 工具 | tools\[]/httpTools\[] | 内置白名单实例（4 个）+ HttpToolAdapter | 白名单名 + HTTP 声明 | 白名单：graph-query/chart-plugin/disjoint-rules/agent-builder；导出后不依赖业务 Java 类，是独立运行的前提 |
| 开场白 | greeting | `GET /api/agents/for-chat` 清单携带，前端渲染首条气泡 | agent.json / application.yml | 纯展示字段，**不注入 sysPrompt**，不入消息历史 |

***

## 二、核心设计决策（3 个）

### 2.1 技能采用"拷贝"而非"引用"（导出可行性的地基）

`HarnessAgent.Builder.projectGlobalSkillsDir(Path)` 只支持**单目录**挂载，无法按 agent 挂不同技能子集。而框架固定约定：`<workspace>/skills/` 会被自动识别加载（见 [AgentScopeConfig.java](../../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java) 中 SKILLS_DIR 注释：加载前有 isDirectory 检查，必须预先创建才会被识别）。

**决策**：创建智能体时，把用户勾选的平台技能（源：平台技能库目录 `app.agent.skills-dir`，当前配置为 `skills`）**整目录拷贝**到 `data/agents/{agentId}/workspace/skills/{skillId}/`。运行时框架自动加载；导出时 workspace/skills 就是包内 skills/，一份拷贝同时解决"多 agent 各选各的技能"与"导出完整性"两个问题。

- 技能目录结构不变（SKILL.md + scripts/），与 Anthropic Agent Skills 标准同构；
- 平台技能库后续升级时，agent 内是旧拷贝——不自动同步；编辑智能体时 workspace 重建重放（AgentWorkspaceService.prepare 清空两目录后按新定义重拷），即同步时机为"编辑时覆盖"。

### 2.2 装配逻辑抽成 AgentBootstrapper（落在共享 runtime 模块）

装配函数落地为独立 Maven 模块 `ontology-agent-runtime` 的静态类 `com.conca.agentkit.AgentBootstrapper`（零 Spring 依赖，pom 自述"平台与独立 runner 共享"）：

```
build(def, workspace, stateStore, apiKey, extraTools, middlewares, envLookup)
  → OpenAIChatModel（apiKey 外部注入；baseUrl 经 ${VAR} 占位符解析）
  → Toolkit（extraTools 实例 + HttpToolAdapter（endpoint 占位符解析）+ MCP 注册）
  → HarnessAgent.Builder（sysPrompt/model/workspace/stateStore/maxIters/中间件/disableDynamicSubagents）
  → BuiltAgent（聚合 agent 与 MCP 连接，统一 close）
```

- 平台内：[AgentRegistry.java](../../ontology-server/src/main/java/com/conca/ontology/builder/AgentRegistry.java) 调它——apiKey 取环境变量 AGENT_API_KEY，extraTools 按白名单实例化（业务 Service 依赖由 Registry 组装后传入），middlewares 挂 ContentSecurityMiddleware，envLookup 传 null（占位符原样保留）；
- 独立运行（L2 导出工程）：同一 Bootstrapper 被导出工程调用，envLookup 从环境变量解析 `${VAR}` 占位符（model.baseUrl 与 httpTools endpoint 均支持）；
- **主智能体未迁移**：原计划"把 AgentScopeConfig 装配逻辑抽出、主智能体也改经 Bootstrapper"未实施——[AgentScopeConfig.java](../../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java) 仍独立装配主智能体（主问答链路零改动，回归风险未引入），Bootstrapper 仅服务自建智能体与导出工程；
- Bootstrapper 不依赖任何 Spring Bean（纯静态参数传入），可在无 Spring 容器环境运行——原设计目标达成。

### 2.3 独立交付形态：L1 导出包 + L2 导出工程（shade jar 追加方案未采用）

原设计的"预构建 runtime jar（shade 平铺）+ 导出时 zip 追加 agent-package.zip"**未实施**。实际采用双层导出，均为模板渲染/纯 zip 读写（秒级完成，无现场 Maven 打包，也规避了 spring-boot loader 嵌套 jar STORED 存储与 zip 重写互相破坏的问题）：

| 层 | 端点 | 产物 | 定位 |
| -- | -- | -- | -- |
| L1 导出包 | `GET /api/agents/{id}/export` | `{id}.zip`：agent.json（定义导出视图）+ skills/ + subagents/ + MANIFEST.json（sha256 清单） | 定义与工作区的"可搬运"形态，读取侧带 zip-slip 防护与完整性校验（为未来 runner 导入/冒烟自检预留） |
| L2 导出工程 | `GET /api/agents/{id}/export-project` | `<projectName>.zip`：AgentScope 2.0 原生 Spring Boot 源码工程 | 解压即 `mvnw spring-boot:run` + 环境变量 AGENT_API_KEY 独立运行，可深度二次开发（当下即可独立运行） |

- L2 的关键实现（[AgentProjectExportService.java](../../ontology-server/src/main/java/com/conca/ontology/builder/service/AgentProjectExportService.java)）：classpath 模板树 `export-template/` 占位符渲染（{{PROJECT_NAME}}/{{PACKAGE_NAME}}/{{PACKAGE_PATH}}/{{AGENTSCOPE_VERSION}}，maven-wrapper.jar 等二进制跳过渲染）；application.yml 不走模板、由定义槽位现场生成（提示词块标量 + 列表配置化，密钥 `${AGENT_API_KEY:}` 占位）；workspace 原样携带；
- 共享件（定义模型/Bootstrapper/编解码）沉淀在 `ontology-agent-runtime` 模块，未来若要恢复"单 jar"形态可基于它补 runner main。

***

## 三、实现步骤与关键点

### 阶段 A：平台内运行（P0）

#### 步骤 1：数据模型与存储

- **做什么**：定义持久化到 MySQL `agent_definition` 表（[V9__p5_agent_definition.sql](../../ontology-server/src/main/resources/db/migration/V9__p5_agent_definition.sql)，P5b 完成；存量 `data/agents/{id}/agent.json` 已由 `AgentDefinitionMigrator` 迁移入表并归档 .migrated）。访问层四件套：`AgentDefinitionEntity`（22 列拆列映射）+ `AgentDefinitionMapper` + `AgentDefinitionConverter`（record↔行实体互转）+ [AgentDefinitionStore.java](../../ontology-server/src/main/java/com/conca/ontology/builder/model/AgentDefinitionStore.java)（save 行级 upsert / find / list 按 updated_at 倒序 / delete 删行+清目录）。workspace（skills、subagents）目录仍归文件系统（框架按路径加载）。
- **关键点**：
  - 密钥**不存**：无密钥库、无加密存储——运行时统一经环境变量 `AGENT_API_KEY` 注入（`AgentRegistry.requireEnvApiKey`，未设置给 503 确定性中文报错）；
  - id 生成后不可变（workspace 目录与其绑定）；
  - 行内 version 为每智能体独立定义版本，编辑 +1，驱使注册表驱逐重建（导出包兼容校验也用它）；
  - 会话状态不在 `data/agents/{id}/state/`，而在 MySQL `agent_state_{agentId}` 表（P8，官方 MysqlAgentStateStore，经 `MySqlAgentStateStores` 按表名隔离）。

#### 步骤 2：workspace 布局与技能/子 agent 落盘

- **做什么**：创建智能体时生成 `data/agents/{id}/workspace/`（`skills/`、`subagents/`），按 2.1 拷贝技能；子 agent 按定义中的 SubagentSpec 数组生成 frontmatter .md（沿用现有动态子 agent 文件格式：name/description/emoji/color + 人格 prompt，参考 [subagent-registry-expansion.md](../遗留问题/subagent-registry-expansion.md) 中实测的文件格式）——由 [AgentWorkspaceService.java](../../ontology-server/src/main/java/com/conca/ontology/builder/service/AgentWorkspaceService.java) 统一落盘。
- **关键点**：
  - `workspace/skills` 目录必须**预先创建**否则框架不识别（Bootstrapper.prepareWorkspaceSkeleton 已内置创建）；编辑语义为清空 skills/、subagents/ 两目录后按新定义重放，避免残留勾选；
  - 子 agent 数量上限 ≤3，且 Builder 调 `disableDynamicSubagents()`——只允许声明的子 agent，禁止运行时模型自主 spawn（防注册表膨胀，历史教训）；
  - 删除智能体 = DB 删行（定义即刻不存在）+ DROP 会话状态表 + 整目录清理（后两者 best-effort，失败仅告警不阻断）。

#### 步骤 3：AgentBootstrapper

- **做什么**：2.2 的装配函数，落在 `ontology-agent-runtime` 模块（[AgentBootstrapper.java](../../ontology-agent-runtime/src/main/java/com/conca/agentkit/AgentBootstrapper.java)）。**主智能体未迁移**：AgentScopeConfig 仍独立装配主智能体，Bootstrapper 仅服务自建智能体与导出工程（原"先重构主智能体、回归主问答链路再开发新功能"的高风险路径未走，主链路零改动）。
- **关键点**：
  - MCP 注册复用现有 Streamable→SSE 兜底与降级告警逻辑（`McpSupport.register`，握手失败不阻断装配，工具静默缺失在对话中暴露）；
  - `HttpToolAdapter`：按 `httpTools[]` 声明（name/description/endpoint/method/pathPrefix）生成通用 HTTP 工具（→ function calling 描述），这是"工具数据化"的最小实现；
  - `tools[]` 白名单映射表（`AgentRegistry.BUILTIN_TOOLS`，4 个）：`graph-query`→HttpRequestOntologyQueryTool（回连本平台 llmBaseUrl）、`chart-plugin`→ChartPluginTool、`disjoint-rules`→DisjointRulesTool、`agent-builder`→AgentBuilderTool；白名单中不存在即 400 拒绝（保存与装配双重校验）；
  - Bootstrapper 内不做任何 Spring 注入（runtime 模块零 Spring 依赖）。

#### 步骤 4：AgentRegistry（多实例工厂）

- **做什么**：[AgentRegistry.java](../../ontology-server/src/main/java/com/conca/ontology/builder/AgentRegistry.java)——`acquire(agentId)` → 查定义 → Bootstrapper 装配 → 缓存（agentId → BuiltAgent + inUse 引用计数 + lastAccess）；调用方流结束 `release(agentId)`；`evict(id)` 统一 close agent 与 MCP 连接（BuiltAgent 聚合，agent.close 不保证关 MCP）；服务关闭 @PreDestroy 全量驱逐。另提供 `stateStoreOf(agentId)`——不构建实例的轻量 MySQL 会话读写（列表/历史/删除/重命名）。
- **关键点**：
  - **并发**：build 按 agentId 加锁双检（synchronized(lockFor(id)) + cache 双查），避免同 agent 并发建双实例；
  - **驱逐策略**：每 5 分钟清点，空闲 30 分钟（`app.agents.idle-minutes` 可配）且 inUse=0 才驱逐（自建 agent 聊天频度低，常驻浪费模型连接与内存）；进行中流以引用计数保护——chat 流 acquire 时 +1、doFinally release 时 -1，驱逐只针对无进行中流的实例；
  - stateStore 隔离：MySQL `agent_state_{agentId}` 表（MySqlAgentStateStores），会话恢复互不串扰；
  - 定义更新（version+1）→ 编辑时立即驱逐旧实例，下次访问重建生效；
  - Studio 对接仅主智能体保留，自建 agent 不注册（StudioManager 是全局单例，避免 trace 混流）。

#### 步骤 5：CRUD API 与校验

- **做什么**：[AgentBuilderController.java](../../ontology-server/src/main/java/com/conca/ontology/builder/api/AgentBuilderController.java)（`/api/agents`，管理面仅 ADMIN；for-chat 登录即可）：
  - `GET /api/agents`（管理列表，含实例存活 live 诊断位）、`GET /api/agents/options`（向导选项面：平台技能清单 + 内置工具白名单）、`GET /api/agents/for-chat`（chat 页可选清单，仅 active，携带开场白）；
  - `POST /api/agents`（创建，id 服务端生成）、`GET/PUT/DELETE /api/agents/{id}`（详情/编辑/删除）；
  - `POST /api/agents/test-model`、`POST /api/agents/test-mcp`（连通性测试端点；test-model 的 key 取环境变量 AGENT_API_KEY，非表单输入）；
  - `GET /api/agents/{id}/export`、`GET /api/agents/{id}/export-project`（导出，见步骤 9-11）。
- **关键点**：
  - 校验顺序：本地静态校验（\[参数错误]）→ 连通性测试（可选）→ 保存；
  - test-model：向 baseUrl 发一条最小对话请求（max_tokens=1），区分"地址不存在或模型名错（404）/key 无效或无权限（401/403）/连接失败"三种确定性中文文案；
  - 列表/详情接口**不含密钥字段**（密钥本就不进定义，无需 mask 回显）；
  - 删除不做活跃会话检查（直接驱逐实例 → 删定义行/状态表/目录）。

#### 步骤 6：搭建向导前端（agents.html）

- **做什么**：管理页 `static/pages/agents.html`（与其他管理页同一交互风格：静态页 + fetch API）——表单涵盖基本信息（名称/描述/开场白/status）、模型（baseUrl/modelName + 测试连接）、提示词、技能多选、工具白名单勾选、MCP 行编辑、子 agent 编辑器、导出工程名/包名；列表页提供编辑/试运行/导出包/导出工程操作。**对话式 builder 亦已实现**：`AgentBuilderTool` 注册为内置工具 `agent-builder`（可被主智能体调用），支持创建/编辑/删除与变更字段合并语义——原"P0 简化不做对话式"计划未走，表单与对话式两路并存。
- **关键点**：
  - 向导选项面来自 `GET /api/agents/options`：技能清单扫描平台技能目录 SKILL.md frontmatter（技能 ID = 目录名），工具白名单即 BUILTIN_TOOLS；
  - 表单不出现 key 输入框（密钥统一环境变量 AGENT_API_KEY，服务端配置）；
  - 每步即时校验（400 \[参数错误] 文案回显），最终提交 `AgentSaveRequest`（POST 创建 / PUT 编辑 version+1）。

#### 步骤 7：chat 接入与开场白

- **做什么**：chat 页智能体选择清单来自 `GET /api/agents/for-chat`（登录即可，仅 active，携带 greeting）；发消息请求带 agentId（空=主智能体，非空=自建——`AgentChatController` 先过 `requireChatAllowed` 再交 `AgentChatService` 路由到 `AgentRegistry.acquire`，流结束 release）。
- **关键点**：
  - **开场白不入消息历史**——由 for-chat 清单携带，前端渲染为首条气泡；若入历史会污染上下文（模型会"记得自己说过"）；
  - 会话与 agent 的绑定关系天然隔离：会话状态在 `agent_state_{agentId}` 表，会话列表/历史/删除/重命名均带 agentId 参数（切换 agent 不复用旧会话）；
  - 自建 agent 仅挂 ContentSecurityMiddleware（提示词与回答审查）；RolePermissionMiddleware 仍只属于主智能体（自建 agent 无角色禁用语义）；
  - 权限门槛：active 所有人可用；test 仅管理员（403"该智能体未上架，仅管理员可试运行"）；
  - 消息事件流协议与主智能体一致（AgentEventCodec 复用，前端 chat 页零改动渲染）。

#### 步骤 8：试运行（status=test 语义）

- **做什么**：实际实现以 `status` 字段承载试运行语义：创建默认 test——不出现在 for-chat 清单，管理员在管理页"试运行"对话框（SSE，与 AI 问答同事件契约）直接对话验证，满意后编辑为 active 上架。
- **关键点**：
  - test 期间的会话均为临时试运行：编辑/发布时 `clearState` 清空 `agent_state_{agentId}` 整表，智能体从零开始（用户期望新 agent 从零开始）；active 期间的真实用户会话在后续编辑中保留（不打断在线对话记忆）；
  - 实例走同一 Registry 缓存（无"临时实例不进缓存"机制），编辑时 evict 重建；
  - 未配置 AGENT_API_KEY（503）/MCP 不通（工具缺失）等报错在试运行对话中直接暴露。

### 阶段 B：导出与独立运行（P1）

#### 步骤 9：导出 agent-package.zip（L1）

- **做什么**：`GET /api/agents/{id}/export` → `{id}.zip`（临时文件流式下载后即删）：

```
agent-package.zip
├── agent.json          # AgentDefinition.forExport()：apiKeyRef 写死 ${AGENT_API_KEY}
├── MANIFEST.json       # 包格式版本 / 导出方 / 最低 harness 版本 / 文件清单 + sha256
├── skills/             # workspace/skills 原样
└── subagents/          # workspace/subagents 原样
```

- **关键点**：
  - 打包只收集 skills/ 与 subagents/ 两子树（[AgentPackageCodec.java](../../ontology-agent-runtime/src/main/java/com/conca/agentkit/AgentPackageCodec.java)），密钥、状态与会话日志**绝不入包**；zip 内统一 UTF-8（Windows 编码坑）；
  - 读取侧（AgentPackageCodec.read）带 zip-slip 防护与 sha256 完整性校验（为 runner 导入/冒烟自检预留）；
  - 导出前置校验：本地必须已设置环境变量 AGENT_API_KEY（缺失给 400 确定性报错）；未实现 sysPrompt 引用完整性的静态校验；
  - httpTools endpoint（与 model.baseUrl）支持 `${VAR}` 占位符，独立运行侧经 envLookup 解析覆盖（如 `${GRAPH_API}` 指向兼容服务）。

#### 步骤 10：独立交付（L2 导出工程；runner 模块未建）

- **做什么**：原设计的 `ontology-agent-runner` 模块（AgentRunnerMain/shade/JDK HttpServer）**未实施**。实际交付为 **L2 导出工程**（[AgentProjectExportService.java](../../ontology-server/src/main/java/com/conca/ontology/builder/service/AgentProjectExportService.java)）：classpath 模板树 `export-template/` → 占位符渲染（{{PROJECT_NAME}}/{{PACKAGE_NAME}}/{{PACKAGE_PATH}}/{{AGENTSCOPE_VERSION}}，maven-wrapper.jar 等二进制跳过）→ application.yml 由定义槽位现场生成（提示词块标量 + 列表配置化，密钥 `${AGENT_API_KEY:}` 占位）→ workspace（skills、subagents）原样拷入 `<project>/workspace/`。产物为 AgentScope 2.0 原生 Spring Boot 源码工程：自带 mvnw，`mvnw spring-boot:run` + AGENT_API_KEY 即可独立运行，且可深度二次开发。
- **关键点**：
  - 共享件沉淀在新 Maven 模块 `ontology-agent-runtime`（零 Spring 依赖）：AgentDefinition 定义模型 + AgentBootstrapper 装配器 + AgentPackageCodec/AgentEventCodec 编解码 + HttpToolAdapter + McpSupport——平台与导出工程共用同一装配逻辑；
  - 导出工程的 AgentScope 版本经 `app.agents.export.agentscope-version` 配置（默认 2.0.3）注入模板；
  - 不注册 Studio、不启用动态子 agent（Bootstrapper 内置 disableDynamicSubagents）、不提供 execute/shell——最小化开放面（原设计约束保留）；
  - 原"单 jar（java -jar 直接跑）"形态未落地：如需恢复，可基于 ontology-agent-runtime 补 runner main（读取侧 sha256 校验已就绪），并在文档中明确内置工具（graph-query 等）需网络可达原平台或改指向兼容服务。

#### 步骤 11：导出器（export-project 端点串联）

- **做什么**：`GET /api/agents/{id}/export-project` → `<projectName>.zip`（projectName 留空回退 `agent-<id>-project`；packageName 留空回退 `com.agentexport`）。流程：AGENT_API_KEY 前置检查 → 模板渲染 + application.yml 生成 + workspace 拷贝 → 临时 zip 经 StreamingResponseBody 流式下载（边写边传、写完即删，不整包进内存）。
- **关键点**：
  - projectName 仅英文字母（Maven artifactId 兼容）、packageName 小写段式，均在保存时静态校验；
  - 产物是可编译源码工程而非"一个单独的 jar"：`mvnw spring-boot:run` + 环境变量 AGENT_API_KEY 即可运行；
  - 密钥经 `${AGENT_API_KEY:}` 注入，不随工程携带（与 L1 同语义）。

#### 步骤 12：导入（P2，可选，未实现）

- 平台导入 agent-package.zip：MANIFEST 版本校验 → sha256 校验 → 建 AgentDefinition 行 + 拷贝 workspace。与导出对称，代码量小，优先级最低（截至修正日无导入端点）。

***

## 四、实施顺序与工作量分布（实际落地复盘）

| 顺序 | 步骤                         | 依赖      | 备注                        |
| -- | -------------------------- | ------- | ------------------------- |
| 1  | 步骤 1-2（模型/存储/落盘）           | 无       | 定义入 MySQL（V9）+ workspace 落盘，先行                   |
| 2  | 步骤 3（Bootstrapper）      | 步骤 1    | 落在共享 runtime 模块；**主智能体未迁移**（原高风险重构路径未走，主链路零改动） |
| 3  | 步骤 4-5（Registry + API）     | 步骤 3    | 并发与生命周期是测试重点              |
| 4  | 步骤 6-8（向导/chat/试运行）        | 步骤 5    | 前端工作量大头              |
| 5  | 步骤 9-10（L1 zip 包 + L2 导出工程） | 步骤 3    | 导出与前端可并行开发           |
| 6  | 步骤 11（export-project 端点）                 | 步骤 9-10 | 最后串联                      |
| 7  | 步骤 12（导入）                  | 步骤 9    | P2，未实现        |

## 五、风险清单（实施时必须盯住）

| 风险                                              | 影响               | 对策                                            |
| ----------------------------------------------- | ---------------- | --------------------------------------------- |
| harness 低于 2.0.3 的 Windows 缺陷（GBK/单引号死锁） | 自建 agent 试运行直接失败 | 母 pom 锁定 2.0.3（已发布 Maven Central）；MANIFEST 记录 minHarnessVersion    |
| Bootstrapper 动主智能体装配                           | 现有功能回归           | **已规避**：主智能体未迁移，AgentScopeConfig 原装配保持不动                      |
| L2 导出工程与 AgentScope 版本耦合          | 导出工程编译失败      | 版本经 `app.agents.export.agentscope-version`（默认 2.0.3）集中注入模板 |
| 导出包内容损坏/被篡改                                    | 导出产物不可运行         | MANIFEST.json sha256 清单 + 读取侧完整性校验（AgentPackageCodec.read）                      |
| MCP 端点在独立运行环境不可达                                | 工具静默缺失           | 装配时握手失败降级告警不阻断；工具缺失在对话中暴露，导出文档明确"需网络可达原平台或改指向兼容服务"                     |
| 子 agent 运行时滥建                                   | 注册表膨胀（历史事故）      | `disableDynamicSubagents()` + 上限 ≤3 + 声明式 .md |
| 密钥泄漏                                            | 安全事故             | 三重防线：不落库不落盘（apiKeyRef 恒空串）、导出写死占位符、详情接口无密钥字段；运行时统一环境变量注入                  |
| 试运行会话/临时文件残留                                 | 磁盘膨胀与记忆污染             | test 状态编辑/发布时 clearState 清空 MySQL 会话表；导出临时 zip 流式下载后即删                             |

## 六、交付物

1. 平台内：agents 管理页（`static/pages/agents.html`：表单向导 + 列表 + 试运行对话框）、`/api/agents` CRUD（含 options/for-chat/test-model/test-mcp）、chat 页多智能体选择与开场白（for-chat 清单）。
2. 导出：`GET /api/agents/{id}/export`（L1 zip：agent.json + skills/ + subagents/ + MANIFEST.json）与 `GET /api/agents/{id}/export-project`（L2：AgentScope 2.0 原生 Spring Boot 源码工程 zip）。
3. 独立运行：L2 工程解压后 `mvnw spring-boot:run` + 环境变量 `AGENT_API_KEY` 即可跑，支持深度二次开发；原"单 jar"交付未实现（可基于 ontology-agent-runtime 补 runner main）。
4. 模块变更：`ontology-server`（builder 包：Store/Registry/Controller/WorkspaceService/ProjectExportService/BuilderTool 等）+ 新模块 `ontology-agent-runtime`（共享定义模型/Bootstrapper/编解码/HttpToolAdapter/McpSupport，零 Spring 依赖）。

