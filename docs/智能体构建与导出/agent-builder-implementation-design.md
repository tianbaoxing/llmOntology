# 方案 1 落地设计：引导式搭建智能体 + 平台运行 + 导出独立 jar

> 日期：2026-09-24
> 前置文档：[agent-builder-and-export-design.md](agent-builder-and-export-design.md)（方案选型与业界调研）
> 需求：用户输入 9 个槽位（提示词、大模型地址、模型名字、key、skills、mcp、子 agent、工具、开场白），搭建的智能体既能在平台内运行，也能导出为一个单独的 jar 独立运行。
> 实施前提：agentscope-harness 使用 2.0.3-SNAPSHOT+（修复 Windows GBK 解码与 working\_directory 单引号死锁，见项目记忆）。

***

## 一、需求槽位与数据模型

### 1.1 AgentDefinition（智能体定义，JSON 持久化）

```json
{
  "id": "agent_xxx",
  "name": "文物问答助手",
  "description": "面向国博藏品的问答智能体",
  "sysPrompt": "你是「文物问答助手」……",
  "greeting": "你好，我可以帮你查询馆藏文物，请问想了解什么？",
  "model": {
    "baseUrl": "https://openrouter.ai/api/v1",
    "modelName": "deepseek/deepseek-v4-flash",
    "apiKeyRef": "vault:agent_xxx"
  },
  "maxIters": 6,
  "skills": ["ontology-query", "xxx"],
  "tools": ["chart-plugin", "graph-query"],
  "httpTools": [
    { "name": "weather", "endpoint": "http://host:port/api/x", "method": "POST",
      "pathWhitelist": ["/api/x/**"], "paramSchema": {} }
  ],
  "mcps": [
    { "name": "weather", "url": "http://localhost:8766/mcp", "transport": "streamable|sse" }
  ],
  "subagents": ["academic-historian"],
  "version": 1,
  "runtime": { "minHarnessVersion": "2.0.3" },
  "createdAt": "...", "owner": "conca"
}
```

### 1.2 槽位映射表（9 项需求逐一落点）

| 用户输入    | 字段                    | 平台内运行载体                                      | 导出包载体                           | 关键点                                                    |
| ------- | --------------------- | -------------------------------------------- | ------------------------------- | ------------------------------------------------------ |
| 提示词     | sysPrompt             | Builder.sysPrompt()                          | agent.yaml                      | 过 ContentSecurityMiddleware 审查；不入开场白逻辑                 |
| 大模型地址   | model.baseUrl         | OpenAIChatModel.baseUrl                      | agent.yaml                      | 必须 OpenAI 兼容协议；保存时连通性测试                                |
| 模型名字    | model.modelName       | OpenAIChatModel.modelName                    | agent.yaml                      | —                                                      |
| key     | model.apiKeyRef       | 密文引用，**不存明文、不回显**（mask 显示）                   | **不入包**，写 `${AGENT_API_KEY}` 占位 | 独立运行时从环境变量注入，缺失给确定性中文报错（沿用 \[连接失败]/\[参数错误]/\[服务错误] 分层） |
| skills  | skills\[]             | 构建时**拷贝**所选技能到 agent workspace/skills/       | skills/ 原样拷出                    | 见 2.1 拷贝策略                                             |
| mcp     | mcps\[]               | Toolkit.registerMcpClient（Streamable→SSE 兜底） | 端点 URL 入 yaml                   | 失败降级告警不阻断（沿用现有模式）                                      |
| 子 agent | subagents\[]          | workspace/subagents/\*.md                    | subagents/                      | 前端按 frontmatter 模板生成 .md；数量上限 ≤3                       |
| 工具      | tools\[]/httpTools\[] | 内置白名单类 + HttpToolAdapter                     | 白名单名 + HTTP 声明                  | 导出后不依赖业务 Java 类，是独立运行的前提                               |
| 开场白     | greeting              | 会话创建接口返回，前端渲染首条消息                            | agent.yaml                      | 纯展示字段，**不注入 sysPrompt**，不影响模型行为                        |

***

## 二、核心设计决策（3 个）

### 2.1 技能采用"拷贝"而非"引用"（导出可行性的地基）

`HarnessAgent.Builder.projectGlobalSkillsDir(Path)` 只支持**单目录**挂载，无法按 agent 挂不同技能子集。而框架固定约定：`<workspace>/skills/` 会被自动识别加载（见 [AgentScopeConfig.java](../../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java) L74-L85 的注释）。

**决策**：创建智能体时，把用户勾选的平台技能（源：`ontology-server/skills/`）**整目录拷贝**到 `data/agents/{agentId}/workspace/skills/{skillId}/`。运行时框架自动加载；导出时 workspace/skills 就是包内 skills/，一份拷贝同时解决"多 agent 各选各的技能"与"导出完整性"两个问题。

- 技能目录结构不变（SKILL.md + scripts/），与 Anthropic Agent Skills 标准同构；
- 平台技能库后续升级时，agent 内是旧拷贝——提供"技能同步"操作（覆盖拷贝），不自动同步。

### 2.2 装配逻辑抽成 AgentBootstrapper（平台与 runner 共用）

当前 [AgentScopeConfig.java](../../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java) 的 Builder 装配是为主智能体写死的单例。把"从 AgentDefinition 装配 HarnessAgent"的纯函数逻辑抽到独立类 `AgentBootstrapper.build(AgentDefinition, Path packageDir)`：

```
AgentDefinition + packageDir（workspace 根）
  → OpenAIChatModel（占位符解析：平台=密钥服务，独立=env）
  → Toolkit（白名单工具实例 + HttpToolAdapter + MCP 注册）
  → HarnessAgent.Builder（sysPrompt/model/workspace/stateStore/maxIters/中间件）
  → HarnessAgent
```

- 平台内：AgentRegistry 调它，传平台密钥服务，挂 ContentSecurity/RolePermission 中间件；
- 独立 jar：runner 调同一逻辑的**精简副本**（runner 不带 Neo4j/业务 Service，中间件仅保留安全审计空实现或省略）。
- 关键点：Bootstrapper 不依赖任何 Spring Bean（纯静态参数传入），才能在无 Spring 容器的 runner 里跑。

### 2.3 独立 jar 形态：预构建 runtime jar + zip 追加导出包（避免 maven 打包）

两种做法对比：

| 做法                                                                      | 问题                                                      |
| ----------------------------------------------------------------------- | ------------------------------------------------------- |
| 每次导出服务端现场 Maven 打 fat-jar                                               | 每次 1-3 分钟、需 maven 环境、.m2 依赖解析不稳定——不可接受                  |
| **预构建 runtime jar（shade 平铺依赖，一次构建）+ 导出时把 agent-package.zip 追加进 jar 副本** | 导出 = 复制 jar + zip 重写（秒级），产物即"一个单独的 jar"，`java -jar` 直接跑 |

**为什么用 shade 平铺而不用 spring-boot repackage**：spring-boot loader 要求嵌套 jar 以 STORED 方式存储，zip 追加/重写会破坏该结构导致 loader 失效；shade（依赖解压平铺）没有此约束，普通 zip 工具可安全追加资源。runner 不用 Spring Boot 全家桶，只用 HarnessAgent + OpenAIChatModel + JDK HttpServer，依赖面小。

***

## 三、实现步骤与关键点

### 阶段 A：平台内运行（P0）

#### 步骤 1：数据模型与存储

- **做什么**：`AgentDefinition` POJO + `data/agents/{id}/agent.json` + `AgentDefinitionStore`（读写/列表，文件系统即可，与现有 data/\*.json 风格一致）。
- **关键点**：
  - apiKey 存引用不存明文；key 在创建时经一次加密（如 AES，密钥来自平台配置）存 `data/agent-keys/`，`apiKeyRef` 指向；
  - id 生成后不可变（workspace 目录与其绑定）；
  - 定义变更走 version 递增（导出包兼容校验要用）。

#### 步骤 2：workspace 布局与技能/子 agent 落盘

- **做什么**：创建智能体时生成 `data/agents/{id}/workspace/`（`skills/`、`subagents/`），按 2.1 拷贝技能；子 agent 按前端表单生成 frontmatter .md（沿用现有动态子 agent 文件格式：name/description/emoji/color + 人格 prompt，参考 [subagent-registry-expansion.md](../遗留问题/subagent-registry-expansion.md) 中实测的文件格式）。
- **关键点**：
  - `workspace/skills` 目录必须**预先创建**否则框架不识别（AgentScopeConfig 注释已踩坑）；
  - 子 agent 数量上限 ≤3，且 Builder 上调 `disableDynamicSubagents()`——只允许声明的子 agent，禁止运行时模型自主 spawn（防注册表膨胀，历史教训）；
  - 删除智能体时整目录清理。

#### 步骤 3：AgentBootstrapper

- **做什么**：2.2 的装配函数。先重构：把 AgentScopeConfig 中主智能体装配逻辑中可复用的部分（模型构建、Toolkit 组装、MCP 注册）抽出，主智能体改为也经 Bootstrapper 装配（回归测试主问答链路不破坏）。
- **关键点**：
  - MCP 注册复用现有 Streamable→SSE 兜底与降级告警逻辑（AgentScopeConfig L162-L204 原样迁移）；
  - `HttpToolAdapter`：按 `httpTools[]` 声明生成通用 HTTP 工具（name/description/endpoint/参数 schema → OpenAI function calling 描述），这是"工具数据化"的最小实现；
  - `tools[]` 白名单映射表：`chart-plugin`→ChartPluginTool、`graph-query`→HttpProxyTool(指向本平台) 等；白名单中不存在即拒绝保存；
  - Bootstrapper 内不做任何 Spring 注入。

#### 步骤 4：AgentRegistry（多实例工厂）

- **做什么**：`build(agentId)` → 查定义 → Bootstrapper 装配 → 缓存（agentId → HarnessAgent 实例 + 空闲时间戳）；`evict(id)` 调 `close()`（HarnessAgent 有 close，@Bean destroyMethod 已证实）；服务关闭时全部 close。
- **关键点**：
  - **并发**：build 需按 agentId 加锁（synchronized/putIfAbsent 双检），避免同 agent 并发建双实例；
  - **驱逐策略**：空闲 30 分钟驱逐（自建 agent 聊天频度低，常驻浪费模型连接与内存）；驱逐时正在进行的会话需等待完成（引用计数或简单跳过驱逐）；
  - stateStore 隔离：`data/agents/{id}/state/`，会话恢复互不串扰；
  - 定义更新（version 变化）→ 立即驱逐旧实例，下次 build 生效；
  - Studio 对接仅主智能体保留，自建 agent 不注册（StudioManager 是全局单例，避免 trace 混流）。

#### 步骤 5：CRUD API 与校验

- **做什么**：`POST/GET/PUT/DELETE /api/agents` + `POST /api/agents/{id}/test-model`、`/test-mcp`（连通性测试端点）。
- **关键点**：
  - 校验顺序：本地静态校验（\[参数错误]）→ 连通性测试（\[连接失败]）→ 保存；
  - test-model：向 baseUrl 发一条最小对话请求（1 token 级），区分"地址错/key 错/模型名错"三种报错文案；
  - 列表/详情接口 apiKey 永远 mask（`sk-***`）；
  - 删除前检查是否有活跃会话。

#### 步骤 6：搭建向导前端（新页面 agents.html 或并入现有管理页）

- **做什么**：分步表单——基本信息（名称/描述/开场白）→ 模型（baseUrl/modelName/key + "测试连接"按钮）→ 提示词（大文本框 + "AI 优化"按钮调主 agent 改写建议）→ 技能（平台技能库多选，来源为 skills/ 目录扫描 + SKILL.md frontmatter 展示）→ 工具（白名单勾选）→ MCP（行编辑：名称/URL/传输方式 + 测试）→ 子 agent（卡片编辑器：name/description/emoji/color/人格 prompt，模板生成 .md 预览）→ 预览汇总。
- **关键点**：
  - 每步即时校验，最后汇总生成 AgentDefinition JSON 供用户确认；
  - 可选 P0 简化：不做对话式 builder-agent，纯表单即可满足"用户输入"需求；对话式抽取放 P1；
  - 与 dedup-rules.html 等现有页面保持同一交互风格（静态页 + fetch API）。

#### 步骤 7：chat 接入与开场白

- **做什么**：chat 页会话创建时选择智能体（主智能体 + 自建列表）；`AgentChatService` 按 agentId 路由到 AgentRegistry 实例；会话创建响应携带 `greeting`。
- **关键点**：
  - **开场白不入消息历史**——作为会话元数据返回，前端渲染为首条气泡；若入历史会污染上下文（模型会"记得自己说过"）；
  - 会话与 agent 绑定关系要落库（会话记录加 agentId 字段），切换 agent 不复用旧会话；
  - 自建 agent 默认挂 ContentSecurityMiddleware（提示词与回答审查）；RolePermissionMiddleware 仅在 owner 外可见时启用；
  - 消息事件流协议与主智能体一致（前端 chat 页零改动渲染）。

#### 步骤 8：沙盒试运行

- **做什么**：向导最后一步"试运行"：临时 build 实例（同一 workspace），用户输入 2-3 条测试问题走正常 chat 流程，满意后保存发布。
- **关键点**：
  - 试运行产生的 state 记为临时会话，保存时清空（用户期望新 agent 从零开始）；
  - 试运行实例用完即弃（不进 Registry 缓存）；
  - 试运行捕获的报错（key 无效/MCP 不通）要回显到对应表单步骤（向导步骤回跳）。

### 阶段 B：导出与独立运行（P1）

#### 步骤 9：导出 agent-package.zip

- **做什么**：`POST /api/agents/{id}/export` → 生成 zip：

```
agent-package/
├── agent.yaml          # AgentDefinition 的导出格式；apiKeyRef → "${AGENT_API_KEY}"
│                       # httpTools 的 baseUrl 可写占位符（如 ${GRAPH_API}）供运行时覆盖
├── skills/             # workspace/skills 原样
├── subagents/          # workspace/subagents 原样
└── MANIFEST.yaml       # 包格式版本、runtime.minHarnessVersion、导出平台版本、文件清单+sha256
```

- **关键点**：
  - 导出前**静态校验**：sysPrompt 中引用的技能/工具名都在包内或白名单；子 agent frontmatter 可解析；
  - 密钥与平台私有规则文件（content-security-rules.json、users.json 等）**绝不入包**；
  - zip 内 .md/yaml 统一 UTF-8（Windows 环境 zip 工具默认编码坑）。

#### 步骤 10：agent-runner 模块（独立运行时）

- **做什么**：新 Maven 模块 `ontology-agent-runner`（不依赖 ontology-server 任何业务代码），主类 `AgentRunnerMain`：
  1. 从 classpath 读内嵌 `agent-package.zip`，解压到临时目录；
  2. 校验 MANIFEST 与 classpath 内 harness 版本（不满足拒绝启动）；
  3. 调 Bootstrapper 精简版装配 HarnessAgent（占位符从环境变量解析）；
  4. JDK HttpServer 起服务：`POST /chat`（SSE 流式，协议对齐平台 chat 接口）+ `GET /`（极简聊天页，可直接体验）。
- **关键点**：
  - shade 打包、依赖平铺（见 2.3）；**构建期验证** harness 无 Spring 传递依赖（有的话排除或改为最小 Spring 上下文，先用 `dependency:analyze` 类思路在模块验证步骤确认）；
  - runner 不注册 Studio、不启用动态子 agent、不提供 execute/shell（最小化开放面）；
  - Windows 下 UTF-8 强制（`-Dfile.encoding=UTF-8` 写进启动脚本模板）；
  - 端口/包路径可用命令行参数覆盖：`--port 18080`；
  - HttpToolAdapter 的 endpoint 若指向导出原平台（如 graph-query），文档中明确"需网络可达原平台，或改指向兼容服务"。

#### 步骤 11：导出器（单 jar 交付）

- **做什么**：`POST /api/agents/{id}/export-jar`：
  1. 取平台预构建的 `agent-runner-shaded.jar`（随平台部署包携带，构建平台时产物拷入 `runtime/`）；
  2. 复制为 `agent-{name}.jar`；
  3. 用 java.util.zip 重写该 jar，追加条目 `BOOT-CLASS:agent-package.zip`（实际放 jar 根或 `resources/agent-package.zip`，runner 从 classpath 读，重写 40MB jar 为秒级操作）；
  4. 下载。
- **关键点**：
  - 追加后**冒烟自检**：解回包、校验 sha256 清单，防止 zip 重写损伤；
  - jar 体积预计 30-60MB（无 Spring Boot 全家桶），可接受；
  - 产物即用户要求的"一个单独的 jar"：`java -jar agent-x.jar` + 环境变量 `AGENT_API_KEY`（及可选 `GRAPH_API` 等占位符）即可运行。

#### 步骤 12：导入（P2，可选）

- 平台导入 agent-package.zip：MANIFEST 版本校验 → 引用完整性 → 建 AgentDefinition + 拷贝 workspace。与导出对称，代码量小，优先级最低。

***

## 四、实施顺序与工作量分布

| 顺序 | 步骤                         | 依赖      | 备注                        |
| -- | -------------------------- | ------- | ------------------------- |
| 1  | 步骤 1-2（模型/存储/落盘）           | 无       | 纯数据层，先行                   |
| 2  | 步骤 3（Bootstrapper 重构）      | 步骤 1    | **风险最高**：动主智能体装配，需回归主问答链路 |
| 3  | 步骤 4-5（Registry + API）     | 步骤 3    | 并发与生命周期是测试重点              |
| 4  | 步骤 6-8（向导/chat/试运行）        | 步骤 5    | 前端工作量大头                   |
| 5  | 步骤 9-10（zip 包 + runner 模块） | 步骤 3    | runner 可与前端并行开发           |
| 6  | 步骤 11（导出器）                 | 步骤 9-10 | 最后串联                      |
| 7  | 步骤 12（导入）                  | 步骤 9    | P2                        |

## 五、风险清单（实施时必须盯住）

| 风险                                              | 影响               | 对策                                            |
| ----------------------------------------------- | ---------------- | --------------------------------------------- |
| harness 2.0.3-SNAPSHOT 以下 Windows 缺陷（GBK/单引号死锁） | 自建 agent 试运行直接失败 | 母 pom 版本前置检查；MANIFEST 记录 minHarnessVersion    |
| Bootstrapper 重构破坏主智能体                           | 现有功能回归           | 主智能体先行迁移 + e2e 回归后再开发新功能                      |
| shade 后 harness 传递依赖冲突（Spring/日志/otel）          | runner 启动失败      | 步骤 10 构建期验证 + 最小依赖清单                          |
| zip 追加损伤 jar                                    | 导出产物不可运行         | 步骤 11 冒烟自检（解包 sha256 校验）                      |
| MCP 端点在独立运行环境不可达                                | 工具静默缺失           | 启动时握手失败即显式告警 + chat 页横幅提示                     |
| 子 agent 运行时滥建                                   | 注册表膨胀（历史事故）      | `disableDynamicSubagents()` + 上限 ≤3 + 声明式 .md |
| 密钥泄漏                                            | 安全事故             | 三重防线：明文不落定义、导出占位符、接口 mask 回显                  |
| 临时 workspace 残留                                 | 磁盘膨胀             | 试运行会话与状态在保存/放弃时清理                             |

## 六、交付物

1. 平台内：agents 管理页（向导 + 列表 + 试运行）、`/api/agents` CRUD、chat 页多智能体选择与开场白。
2. 导出：`POST /api/agents/{id}/export`（zip）与 `export-jar`（单 jar 下载）。
3. 独立运行：`agent-{name}.jar`，`java -jar` + 环境变量即可跑，内置极简聊天页与 SSE chat 接口。
4. 模块变更：`ontology-server`（Bootstrapper/Registry/Store/API/导出器）+ 新模块 `ontology-agent-runner`（shade runtime）。

