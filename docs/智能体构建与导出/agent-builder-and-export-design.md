# 自然语言构建智能体：方案对比与导出独立运行可行性分析

> 日期：2026-09-24
> 背景：本平台（Spring Boot 3.2.5 + AgentScope 2.0 Harness）计划增加"用户输入自然语言，引导式搭建智能体"的能力，并希望搭建好的智能体可导出、独立运行。
> 结论先行：**两个想法不是二选一——推荐"想法 1（引导式配置）为主路线，想法 2（工作流）作为后期外接扩展"。导出独立运行可行，但需把"工具"从 Java 类数据化为 HTTP/MCP 声明，这是最大技术难点。**

---

## 一、现状盘点：本平台的一个"智能体"由什么构成

这是评估两个方案和导出可行性的地基。当前主智能体在 [AgentScopeConfig.java](../../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java) 中由 `HarnessAgent.Builder` 装配（单例 Spring Bean，参数来自 application.yml）：

| 组成部分 | 载体 | 形态 | 可打包性 |
|---|---|---|---|
| 装配配置 | name / sysPrompt / model(baseUrl,apiKey,modelName) / maxIters | application.yml → Builder | 纯数据，天然可打包 |
| 技能 | `skills/<id>/`：SKILL.md（frontmatter）+ scripts/ + 资源 | 纯文件夹 | 天然可打包（与 Anthropic Agent Skills 文件夹标准同构） |
| 子智能体 | `data/agent-workspace/subagents/*.md`（frontmatter: name/description/emoji/color + 人格 prompt），模型可经 AgentSpawnTool 自建、DynamicSubagentsMiddleware 懒加载 | 纯文件 | 天然可打包（与 Claude Code subagents 机制同构） |
| 工具 | Java 类注册进 Toolkit：HttpRequestOntologyQueryTool（哑 HTTP 管道，/api/llm/ 白名单）、DisjointRulesTool、ChartPluginTool；可选 MCP 客户端（Streamable HTTP→SSE 兜底） | **Java 代码** | **不可随数据包走——导出的核心难点** |
| 中间件 | ContentSecurityMiddleware、RolePermissionMiddleware | **Java 代码** | 平台属性，随运行时走 |
| 状态 | JsonFileAgentStateStore（data/agent-state/）+ workspace 目录骨架 | 运行时生成 | 不随包走，导入时重建 |

**关键改造点**：当前是"启动时装配 1 个单例"。要做多用户自建智能体，需新增 **AgentRegistry（多实例工厂）**：智能体定义持久化为 JSON/DB 记录，按需 build/销毁 HarnessAgent 实例，workspace 按智能体（或 userId）隔离。`HarnessAgent.Builder` 天然支持每次 build 一个实例，改造成本低。

---

## 二、两个候选方案对比

### 想法 1：AgentScope 2.0 Java 骨架 + 引导式配置

先澄清"骨架"的两种解读：

- 解读 a：**为每个智能体生成一个新 Java 工程**（mvn archetype + 编译 + 部署）——不推荐。用户写的只是提示词/选项，为纯数据生成整个工程，构建慢、不可动态运维、版本漂移不可控，业界没有任何主流产品这么做。
- 解读 b：**平台即运行时（骨架），智能体 = 配置 + 文件包**——推荐。用户填的槽位（提示词、skills、工具、MCP、子智能体）全部落成数据，平台运行时动态装配。业界同构先例：OpenAI GPTs、Dify App、Claude Code subagents。

推荐流程（对话式 + 槽位确认的混合范式，同 Copilot Studio agentic builder + M365 Agent Builder）：

```
用户自然语言描述需求
  → builder-agent（一个内建智能体）对话式澄清，抽取槽位
  → 生成槽位表单供用户确认/修改：
      名称/描述、系统提示词（含改写建议）、
      模型（OpenAI 兼容 baseUrl+model）、maxIters、
      技能勾选（平台技能库白名单，或引导用户粘贴 SKILL.md）、
      工具勾选（白名单：HttpProxy 通道端点、内置工具）、
      MCP 端点（URL + 传输方式）、
      子智能体（引导写 .md 人格提示词，设数量上限）
  → 沙盒试运行（隔离 workspace，预置几条测试问题）
  → 保存入库 → AgentRegistry 按需实例化 → 发布到 chat 页可选智能体列表
```

| 维度 | 评价 |
|---|---|
| 与现有架构契合度 | 极高：Builder 的每个参数/目录约定就是现成槽位；builder-agent 本身就是一个 HarnessAgent + 一个新技能（agent-builder 技能，负责澄清与抽取），复用现有 ReAct + 事件流 + Studio 追踪 |
| 表达力 | 覆盖"单 agent + 工具 + 技能 + 子 agent 委派"，足够 90% 问答/分析类场景；复杂确定性流程（固定多步审批流）表达弱 |
| 治理 | 好：槽位全部白名单化，提示词有内容安全中间件把关，子 agent 有数量上限（吸取 subagents 目录膨胀的教训，见 subagent-registry-expansion.md） |
| 工作量 | 小：1 个技能 + 智能体 CRUD API + 前端向导页 + AgentRegistry |

### 想法 2：工作流引擎，节点连线编排

| 维度 | 评价 |
|---|---|
| 表达力 | 强：条件分支、并行、人工介入（HITL）、固定流程直观可见 |
| 与现有架构契合度 | **低**：agentscope-harness 是 ReAct 单 agent + 动态子 agent 模式，Java 侧无原生工作流引擎；引入工作流 = 引入第二运行时，双份状态管理、双套会话模型，与"查询经由 skills"的最小化开放面原则冲突 |
| 维护成本 | 高：Dify 体量大且许可有附加条款；自研 DAG 引擎是另一个项目 |

**开源工作流选项**（若要上）：

| 引擎 | 许可 | 集成方式 |
|---|---|---|
| n8n | fair-code（内部使用免费、禁转售） | 不嵌入，REST/Webhook 外接：n8n AI Agent 节点调本平台 chat API；或本平台某技能/工具触发 n8n webhook。生产自动化连接器生态最强 |
| Flowise | Apache 2.0（最宽松） | 可二开或 iframe 侧挂，LangChain.js 系 |
| Dify | Apache 2.0 + 禁止竞争性托管附加条款 | 平台级引入过重，不推荐 |
| AutoGen Studio | MIT（注意：微软主线已转 Microsoft Agent Framework，AutoGen 进入维护模式，选型需谨慎） | 不推荐新增依赖 |

### 结论

- **主路线 = 想法 1**（解读 b）：先做平台内引导式搭建 + 试运行 + 发布。
- **想法 2 降级为 P2 外接扩展**：不自研、不嵌入引擎，需要固定流程时经 n8n webhook 互调。
- 这与业界趋势一致：OpenAI Agent Builder 与 LangGraph 双向打通"可视化 + 代码"，但底层始终是**一份可序列化的智能体定义**，工作流只是定义的一种视图。

---

## 三、导出独立运行：可行性与技术难点

### 可行性：可行，分三个层级（对标业界三种做法）

| 层级 | 形态 | 业界对标 | 说明 |
|---|---|---|---|
| **L1 平台导出包** | `agent-package.zip`：agent.yaml（元数据/提示词/模型/工具清单/中间件开关 + harness 版本）+ skills/ + subagents/ | Dify DSL（yml 全量导出/导入，≤10MB，含版本兼容检查） | 导入方 = 本平台或任何跑 agentscope-harness 运行时的环境。**工作量最小，建议先做** |
| **L2 独立进程** | fat-jar：spring-boot + harness 依赖 + 内嵌导出包，`java -jar agent-x.jar` 即跑 | LangGraph `langgraph build` 打 Docker 镜像 → Agent Server 自托管 | 适合单机交付（离线环境/私有部署）。需处理数据源可达性（导出的图谱问答 agent 若依赖宿主 /api/llm/* 则必须连回服务或换 MCP） |
| **L3 服务化** | 智能体以 Agent Card + A2A（Linux 基金会 v0.3.0，有 Java SDK）/ REST 暴露为独立服务 | Google ADK：agent 打包为微服务经 A2A 互调 | 跨平台互操作的最优解，也解决"另一个平台的智能体调我的智能体" |

导出包结构建议（L1）：

```
agent-package/
├── agent.yaml        # name/description/sysPrompt/model(占位符)/maxIters/
│                     # tools(白名单名+HTTP端点声明)/mcp端点/中间件开关/
│                     # runtime: agentscope-harness 版本 / 导出平台版本
├── skills/           # 该智能体勾选的技能文件夹原样拷贝（SKILL.md + scripts）
├── subagents/        # 子智能体 .md 原样拷贝
└── assets/           # 可选：技能引用的静态资源
```

### 技术难点（按严重程度排序）

1. **工具可移植性（最大难点）**。工具是 Java 类，无法随数据包走。三种解法（可组合）：
   - **工具数据化**：把 HttpRequestOntologyQueryTool 的哑管道模式推广——agent.yaml 中声明 HTTP 工具（endpoint + path 白名单 + 参数 schema），运行时由通用 `HttpToolAdapter` 装配。导出包因此不依赖任何业务 Java 类；
   - **MCP 优先**：导出包内工具尽量写成 MCP 端点引用（本平台已有 Streamable HTTP/SSE 双通道注册能力），目标环境只需网络可达；
   - **内置白名单**：平台运行时自带工具（如 DisjointRulesTool）只在包里存名字，导入端不存在该名字则降级/禁用并提示。
2. **密钥外置**。apiKey 绝不入包：agent.yaml 用 `${OPENROUTER_API_KEY}` 式占位，导入端从环境变量/密管服务注入，缺失时给确定性中文报错（沿用本项目错误分层规范）。
3. **运行时版本兼容**。包内必须记录 agentscope-harness 版本；本项目已确认 2.0.0 存在 Windows 缺陷（GBK 解码、working_directory 单引号死锁），须 2.0.3-SNAPSHOT+。导入校验版本下限，不满足拒绝导入。
4. **中间件取舍**。内容安全/角色权限是"平台治理"还是"智能体属性"？建议：安全开关随包（声明需要的策略等级），策略实现与规则文件（content-security-rules.json 等）随运行时——避免导出包携带敏感规则。
5. **状态与工作区**。stateStore、workspace 骨架导入时重建（Dify 同样不迁会话日志/状态，只迁定义）；workspace 按 agent 隔离目录命名，防跨用户污染（subagents 目录膨胀教训）。
6. **引用完整性校验**。subagents/*.md 与 sysPrompt 里引用的技能 ID、工具名必须在包内或目标运行时存在；导入时做静态检查。
7. **MCP 网络可达性**。导出时记录端点连通性，导入端握手失败走既有降级告警（不阻断），但要在导入报告里显式列出。
8. **L2 体积与依赖**。fat-jar 约 50–100MB（Spring Boot + harness + otel）。折中方案："安装器模式"——导出包 + 一个固定 runner jar，runner 扫包装配，不必为每个智能体生成完整 jar。

### 与业界"可移植性标杆"的关系

Anthropic Agent Skills 被公认可移植性最好，原因是**纯文件夹、零 API 上传、渐进式加载**。本平台的技能（SKILL.md + scripts/）与子智能体（frontmatter .md）机制与之几乎同构——这是先天的架构优势，L1 导出包本质上是"Skills 文件夹 + Dify DSL 式 yaml 清单"的混合，业界已有成熟先例背书。目前业界尚无统一跨平台打包格式，"平台内 DSL 导出 + A2A 服务化 + Skills 文件夹"正是现实组合。

---

## 四、业界最先进的"自主搭建 Agent"方案（2026-09 时点）

| 方案 | 形态 | 核心机制 | 导出/独立运行 |
|---|---|---|---|
| **OpenAI AgentKit**（Agent Builder + ChatKit，2025-10 发布） | 商用 | 可视化画布与 Agents SDK 同底层 Responses API；2026 起 SDK 增加原生沙箱、文件系统工具、可配置记忆 | ✗ 深度绑定 OpenAI 平台，无独立导出 |
| **Anthropic Agent Skills + Claude Code subagents** | 开放标准 + 商用 | SKILL.md 文件夹、渐进式披露三级加载；subagents 用 `.claude/agents/*.md` 定义自动委派 | ✓ 纯文件夹即"包"，跨 claude.ai/API/Claude Code/Bedrock 可移植 |
| **Google ADK + A2A** | 开源 + 协议 | 代码定义 agent；A2A 已捐 Linux 基金会（v0.3.0），Agent Card 能力发现，官方含 Java SDK | ✓ agent 打包为独立微服务经 A2A 暴露 |
| **Microsoft Agent Framework / Copilot Studio** | 商用 + 开源 SDK | MAF 合并 AutoGen + Semantic Kernel（含 workflow、MCP、AG-UI）；Copilot Studio 有对话式 agentic builder（预览） | 部分：Copilot Studio 仅发布到 MS 渠道；MAF 代码可自托管（AutoGen 已维护模式） |
| **Dify** | 开源（许可有附加条款） | 可视化 Workflow/Chatflow | ✓ DSL(yml) 导出/导入，但仅能在 Dify 引擎内运行 |
| **Coze 扣子** | 商业 + 开源双轨 | 云端版无私有化；开源 Coze Studio（Open-Core）可自部署 | 商业版靠渠道/SDK 发布；开源版自托管 |
| **n8n / Flowise / FastGPT** | fair-code / Apache2.0 / 自有许可 | 生产自动化连接器生态 / 轻量 LangChain.js 可视化 / 知识库 + 国内渠道 | 均可自托管 + REST 对外 |

**四种搭建交互范式**（先进产品均为混合态）：

1. 表单/引导式槽位配置 —— 可控、治理友好，表达力受限；
2. 自然语言对话式生成（builder agent）—— 零门槛，黑盒性与回归验证是痛点，需配试运行+评测闭环（本平台已有 eval 能力可复用）；
3. 可视化工作流编排 —— 表达复杂分支直观，学习曲线陡；
4. 代码优先 + LLM 自编排 —— AgentScope 2.0 的方向（移除 Pipeline API，主 agent 经 agent_spawn 动态派生，模型自编排）。

**对本项目的定位判断**：AgentScope Java 2.0（2026-06 正式发布）正是为嵌入 Spring Boot 微服务设计——分布式会话、沙箱快照、多租户隔离（userId/sessionId 贯穿 workspace/存储/沙箱）、K8s 无状态横向扩展，Apache 2.0。**本项目已经站在这条路线上，自建引导式 builder 是补齐"最后一公里"的交互层，而非重造运行时。**

---

## 五、开源可集成方案盘点

### 可直接嵌入 Java/Spring Boot

| 方案 | 许可 | 与本项目关系 |
|---|---|---|
| **AgentScope Java 2.0** | Apache 2.0 | 现有运行时；其多租户隔离（userId/sessionId 贯穿）正是多用户自建智能体需要的底座，升级即可获得 |
| **A2A Java SDK**（a2a-java） | 开源 | 把任意智能体暴露为标准服务（L3 导出），Java 平台可直接做 A2A 客户端/服务端 |
| **MCP 官方 Java SDK / Spring AI MCP starter** | 开源 | 工具层标准化，配合"工具数据化"策略 |

### Python 系（经 REST/协议侧接，不推荐嵌入）

| 框架 | 许可 | 状态 |
|---|---|---|
| LangGraph | MIT | 图状态 + checkpoint + HITL，生产默认选择 |
| CrewAI | MIT | 角色制多 agent，原生 MCP/A2A |
| Agno | Apache 2.0 | 轻量 + AgentOS 控制面 |
| smolagents（HF） | Apache 2.0 | CodeAgent，代码即行动 |
| Pydantic AI | 开源 | 类型安全 |
| AG2（AutoGen 社区分支） | 开源 | AutoGen 维护模式后的活跃分支，含 AG-UI |

### 可视化/低代码引擎（二开需评估许可）

Flowise（Apache 2.0，最宽松）> n8n（fair-code，禁转售）> Dify（体量大 + 附加条款）> Coze Studio 开源版（Open-Core 子集）。

---

## 六、推荐路线图

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P0** | 智能体定义模型（DB/JSON：槽位持久化）+ AgentRegistry 多实例工厂（替代单例装配，workspace 按 agent 隔离）+ agent-builder 技能与对话向导（槽位确认式）+ 沙盒试运行 | 无新依赖 |
| **P1** | 导出 L1 包（zip：agent.yaml + skills/ + subagents/）与导入（版本/引用完整性/密钥占位校验）；工具数据化：HttpToolAdapter 按 yaml 声明装配 HTTP 工具，新增工具尽量走 MCP | P0 |
| **P2** | L3 服务化（A2A Java SDK 暴露 Agent Card）；L2 独立交付（固定 runner jar + 导出包的"安装器模式"，替代逐 agent fat-jar）；外接 n8n 满足固定流程场景 | P1 |

配套治理（从第一天就做）：子智能体数量上限、槽位白名单、提示词过 ContentSecurityMiddleware、试运行评测复用 eval 框架、导出包不含任何密钥与平台规则文件。

---

## 七、主要参考

- OpenAI Agent Platform / Agents SDK：https://openai.com/agent-platform/
- Anthropic Agent Skills：https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview ；Claude Code Subagents：https://code.claude.com/docs/en/sub-agents
- A2A Protocol v0.3.0：https://a2a-protocol.org/v0.3.0/
- AgentScope 2.0 文档：https://docs.agentscope.io/versions/2.0.2/en ；AgentScope Java 2.0：https://www.alibabacloud.com/blog/agentscope-java-2-0-building-a-distributed-enterprise-grade-foundation-for-ai-agents_603231
- Dify DSL（Key Concepts）：https://dify-6c0370d8.mintlify.app/en/learn/key-concepts
- LangGraph 自托管 Agent Server：https://docs.langchain.com/langsmith/deploy-standalone-server
- Microsoft Agent Framework：https://learn.microsoft.com/agent-framework/
- OSS Agent Frameworks 2026 对比：https://futureagi.com/blog/oss-agent-frameworks-2026/
- 本项目相关文档：[subagent-registry-expansion.md](../遗留问题/subagent-registry-expansion.md)（子 agent 注册表治理）、[STUDIO_INTEGRATION_GUIDE.md](../Studio集成/STUDIO_INTEGRATION_GUIDE.md)
