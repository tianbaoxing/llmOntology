# ontology-query 技能双通道设计方案

| 项 | 内容 |
|---|---|
| 日期 | 2026-08-24 |
| 状态 | 设计稿（待评审） |
| 范围 | skills/ontology-query/SKILL.md 改造 + 新增 HttpProxyTool 工具（仅本项目） |
| 不改动 | /api/llm/** 接口平面（冻结区）、外部 Qwen CLI 既有使用方式 |

---

## 1. 背景与问题

### 1.1 事故回顾（2026-08-24）

会话 `0e69897c`（"景县出土的有宝贝吗？"）中，agent 7 次 `execute` 调用全部失败，最终凭 agent 记忆作答并错误归因为"服务连接失败"。根因：

- agentscope-harness 2.0.0 的 `execute` 工具存在**参数死锁**：`working_directory` 为必填（不传被参数校验拒绝），但传入后又被拼成 `cd '路径' &&` 传给 cmd.exe（单引号不识别，必然失败）；
- 子进程输出按 UTF-8 硬解码，Windows 中文系统 cmd 的 GBK 错误信息全部乱码，模型无法自诊断。

结论：本项目 agent 环境下 **execute 路线结构性不可用**，与命令内容无关。

### 1.2 新增约束（本方案的前提）

SKILL.md 是**共享资产**，存在两类调用方：

1. **本项目 agent**（ontology-assistant，harness 2.0.0）：有（可新增的）`http_request_ontology_query` 工具，execute 不可用；
2. **外部 Qwen CLI 调用方**：没有 `http_request_ontology_query` 工具，只有正常的 shell 执行能力，**按现有 execute + curl 逻辑使用本技能，行为不能变**。

### 1.3 目标

一份 SKILL.md 同时服务两类调用方：

- 本项目 agent → 走 **通道 A（http_request_ontology_query 哑管道）**，绕开 execute 死锁；
- 外部 Qwen CLI → 走 **通道 B（execute + curl，冻结现状）**，行为与今天完全一致；
- 业务协议（查询主线、只读约束、证据规范）**共享一份**，不因通道不同而漂移。

---

## 2. 调用方环境分析

| 维度 | 调用方 1：本项目 agent | 调用方 2：外部 Qwen CLI |
|---|---|---|
| 运行时 | agentscope-harness 2.0.0 | Qwen CLI 自带 agent 运行时 |
| shell 执行 | execute（**死锁，不可用**） | 正常 shell 工具 |
| http_request_ontology_query 工具 | 改造后**有** | **无** |
| 传输通道 | A：http_request_ontology_query | B：execute + curl |
| 文件工具 | read_file / grep_files / glob_files / list_files | 同名或等价工具（现有技能已在用） |
| SKILL.md 来源 | `llmOntology/skills/ontology-query/`（app.agent.skills-dir） | 同一文件（仓库内路径） |

关键事实：**模型的工具定义始终注入在其上下文中**，因此模型可以零成本地"看见自己有哪些工具"——这是双通道自选的可靠性基础。

另一个关键事实：本项目 agent 的工具清单里 **execute 依然注册着**（存在但坏）。所以通道探测规则必须是**优先级判定**而非存在性判定（见 §4）。

---

## 3. 总体设计：一份文件，三层结构

```
SKILL.md
├── 共享协议层（通道无关，单一事实源）
│   ├── 固定执行主线：schema → 对齐 → cypher → query → files 兜底 → profile 兜底
│   ├── 只读红线与证据规范（graph / file / profile / none）
│   ├── 熔断规则（连续 2 次失败停止）
│   └── 回答组织模板
├── 通道 A：http_request_ontology_query 调用规范（本项目 agent 专用）
│   └── 端点速查表 + 调用示例
└── 通道 B：execute + curl 调用规范（外部 Qwen CLI，冻结现状）
    └── 现有全部环境约定（规则 0 环境探测、Windows/cmd 约束、curl 示例、shell 错误分类）
```

设计原则：

1. **单一事实源**：只维护 `llmOntology/skills/ontology-query/SKILL.md` 一份。历史上 workspace 目录副本（`data/agent-workspace/skills/`）曾与主副本漂移导致事故，该目录现为空壳（启动时自动重建的空仓库），**禁止**再往里放技能副本。
2. **通道 B 冻结**：现有 execute + curl 章节内容**原样保留、一字不改**（包括为 harness 编写的"禁传 working_directory"约束——对 Qwen CLI 无害，因为它是"建议不传"，不是"必须传"）。冻结原则：不为不可见的外部调用方做任何"顺手改进"。
3. **通道差异只体现在"传输指令怎么发"**：同一个业务动作（如查 schema）在两个通道各有一个等价示例，业务语义（何时查、查什么、怎么解读）只写一份。

---

## 4. 通道探测机制（Step 0）

SKILL.md 固定主线前置一步"Step 0 通道探测"，规则：

> 检查你的工具清单：
> - **有 `http_request_ontology_query`** → 使用**通道 A**，且本次会话**禁止调用 execute**（本项目环境中 execute 存在但已损坏，调用必然失败）；
> - **没有 `http_request_ontology_query`** → 使用**通道 B**（execute + curl，含全部环境探测与 Windows 约定）。

要点：

- 判定条件是 `http_request_ontology_query` 的**存在性**，且**优先级高于** execute 的存在性——因为本项目 agent 两者都有，但只有前者可用；
- 工具名刻意带 `_ontology_query` 后缀避免与通用名 `http_request` 撞车：若外部调用方运行时恰好自带名为 `http_request` 的工具（参数契约未必相同），以通用名做探测条件会误判进通道 A；专属名保证探测条件全局唯一、零歧义；
- 明示"禁止调用 execute"而不是"建议不用"：事故中模型曾在 7 次失败后仍继续盲试，提示词约束必须是禁令级；
- 探测零成本：无需任何工具调用，模型读自己的上下文即可完成。

---

## 5. 通道 A：http_request_ontology_query（本项目专属）

### 5.1 工具定义

新建 `agent/HttpProxyTool.java`，注册为单一工具 `http_request_ontology_query`，`readOnly=true`（硬约束）。

**定位**：无业务语义的哑 HTTP 管道，与 read_file / grep_files 同级的基础设施。不知道 schema、不认识 cypher、不预置端点——**不加载 skill 就无法正确使用**，"查询经由 skills"由形式合规变为实质必然（这是相比直接恢复 OntologyHttpTools 五件套的根本区别）。

**参数**：

| 参数 | 类型 | 必填 | 约束 |
|---|---|---|---|
| path | string | 是 | 以 `/` 开头，必须落在 `/api/llm/` 前缀内 |
| method | string | 否 | `GET`（默认）/ `POST` |
| params | object | 否 | GET 查询参数键值对 |
| body | object | 否 | POST 的 JSON 请求体，≤16KB |

**返回契约**：

- 成功：`HTTP 200` + 响应体原文（UTF-8，无 shell 编码环节）；超 8000 字符截断并注明"[响应已截断，共 N 字符]"；
- 失败按前缀分层（沿用工程约定）：
  - `[连接失败]` 服务未响应（附 base-url）——连接拒绝/超时；
  - `[参数错误]` path 越界 / method 非法 / body 超限——本地校验拒绝；
  - `[服务错误]` HTTP 404/500 + 服务端 message——透传。
- 超时 15s；空结果返回明确的"未查询到"文案而非空串。

**base-url**：服务端固定，agent 只传 path。解析优先级沿用现状：`app.agent.ontology-base-url` → 环境变量 `ONTOLOGY_BASE_URL` → `http://127.0.0.1:{server.port}`。

### 5.2 安全边界

| 风险 | 对策 |
|---|---|
| SSRF | base-url 服务端固定，agent 只能传 path |
| 越权访问管理面 | path 前缀白名单 `/api/llm/**`（该平面本就 AuthInterceptor 放行、全只读、无写端点）；`/api/users`、`/api/backups` 等管理面完全不可达 |
| 写操作 | 方法白名单 GET/POST；cypher 写语句由服务端红线正则（既有防线）兜底拦截——工具层不做 cypher 预检（做了就沾染业务语义，回到五件套方案） |
| token 爆炸 | body ≤16KB、响应 ≤8000 字符 |

取舍说明：五件套方案中的"rows>20 截断、无 LIMIT 自动补"属于业务逻辑，本方案舍弃，改由 SKILL.md 约束模型主动加 LIMIT（8000 字符兜底仍在）。纵深防御的代价是：一条 CREATE 要等服务端 400 才被拒，可接受。

### 5.3 服务端改动清单

1. 新建 `src/main/java/com/conca/ontology/agent/HttpProxyTool.java`（可参照现存 OntologyHttpTools.java 的 JDK HttpClient 基建与错误分层实现，收敛为单方法 + 参数校验）；
2. [AgentScopeConfig.java] 取消 toolkit 注册注释，改为注册 HttpProxyTool，并更新注释说明架构理由："工具层仅提供无业务语义的 HTTP 管道，查询协议由 skill 承载，外部 CLI 调用方走 execute+curl 通道"；
3. OntologyHttpTools.java 处置：删除（其职责已被 HttpProxyTool + SKILL.md 协议表取代，避免留下两个相似的 HTTP 工具造成混淆）。

---

## 6. 通道 B：execute + curl（外部 Qwen CLI，冻结现状）

- 现有 SKILL.md 中与 execute / curl / 环境探测相关的**全部章节原样迁移**进"通道 B"章节，内容不改：
  - 规则 0（必须最先执行 `echo %OS%` 环境探测）；
  - Windows 下 execute 强约束（禁传 working_directory、cmd 兼容语法、URL/JSON 双引号、连续 2 次失败熔断）；
  - curl 调用方式与示例；
  - shell 层 / HTTP 层错误分类表。
- 通道 B 的熔断与错误分类同样指向共享协议层的"证据规范"与"回答组织"，无需重复。

---

## 7. 共享协议层（单一事实源）

与通道无关，两个调用方遵守同一套业务规则：

1. **固定执行主线**：schema → 对齐类/属性/关系/keyProp → 组装只读 Cypher → query → 图谱无果时 files 兜底（grep_files/read_file）→ 背景类问题 profile 兜底 → 仍未命中则明确说明"未查询到"；
2. **只读红线**：Cypher 仅允许 MATCH / OPTIONAL MATCH / WHERE / WITH / RETURN / ORDER BY / LIMIT，名称必须来自 schema，默认加 LIMIT；
3. **证据规范**：证据来源只能标注 graph / file / profile / none，**禁止把 agent 记忆（memory）冒充本次查询证据**——2026-08-24 事故的直接教训；记忆只能作补充且必须显式标注 memory；
4. **熔断**：同一通道连续 2 次失败后必须停止，先检查是否违反环境约束，禁止盲试相似调用；`[连接失败]` 前缀直接如实报告；
5. **回答组织**：先结论、再证据来源、未查到时明确说"不存在"，不猜测补全。

### 7.1 端点速查表（SKILL.md 内，通道 A/B 共用）

| 业务动作 | HTTP | 端点 | 请求体/参数 |
|---|---|---|---|
| 概念层全量 | GET | /api/llm/schema | - |
| 单个类详情 | GET | /api/llm/schema/classes/{name} | - |
| 关系列表 | GET | /api/llm/schema/relations | - |
| 执行只读 Cypher | POST | /api/llm/ontology-query/query | {question, cypher, params} |
| 上传文件列表 | GET | /api/llm/files | - |
| 构建背景 | GET | /api/llm/profile | - |

表中每个业务动作在通道 A 章节（http_request_ontology_query 参数写法）与通道 B 章节（curl 命令写法）各给一个等价示例。

---

## 8. SKILL.md 目标结构（改写蓝图）

```
frontmatter（name/description 不变）
# Ontology Query（本体查询）
  适用范围 / 服务地址（默认 http://localhost:9876，可被用户给定地址或 ONTOLOGY_BASE_URL 覆盖）

# Step 0 通道探测
  有 http_request_ontology_query → 通道 A（禁用 execute）
  无 http_request_ontology_query → 通道 B

# 通道 A：http_request_ontology_query 调用规范
  工具用法（参数表）+ 端点速查表的 http_request_ontology_query 写法示例
  错误前缀三层分类

# 通道 B：execute + curl 调用规范（现状冻结）
  规则 0 环境探测 / execute 强约束 / curl 示例 / shell 与 HTTP 错误分类

# 固定执行主线（共享）
  Step 1 理解问题 → Step 2 查概念层 → Step 3 组装 Cypher
  → Step 4 执行查询 → Step 5 profile 兜底 → Step 6 文件兜底
  （每个 Step 只描述"做什么、判断什么"，"怎么发指令"指向 A/B 两章）

# 规则（共享）
  只读红线 / 证据来源规范（含 memory 禁令）/ 熔断

# 回答组织（共享）
  graph / file / profile / none 四类模板
```

篇幅控制：目标 300 行以内。通道 B 章节是现有内容的搬运（不减），通道 A 是新增（约 60 行），共享层比现状精简（删除与 execute 强绑定的表述）。

---

## 9. 迭代预算（本项目，maxIters=8）

`load_skill`(1) → Step 0 探测(0，纯推理) → `http_request_ontology_query schema`(2) → `http_request_ontology_query query`(3) → 需要时 `http_request_ontology_query files`(4) → grep/read 兜底(5~7) → 回答(8)。单查询主线 3 次工具迭代，8 次内富余；文件兜底路径贴边但可行，实测不足再调 maxIters。

---

## 10. 实施步骤

| 步骤 | 内容 | 依赖 |
|---|---|---|
| 1 | 新建 HttpProxyTool.java（哑管道 + 参数校验 + 错误分层） | 无 |
| 2 | AgentScopeConfig 注册；删除 OntologyHttpTools.java | 步骤 1 |
| 3 | 按 §8 蓝图改写 SKILL.md（通道 B 内容从现文件原样迁移） | 步骤 1（端点表需与工具参数对齐） |
| 4 | 编译、重启服务 | 步骤 1~3 |
| 5 | e2e 验证（见 §11） | 步骤 4 |

---

## 11. 验收标准

### 通道 A（本项目，可自动化验证）

1. chat 页重放"景县出土的有宝贝吗？"：`load_skill` → `http_request_ontology_query /api/llm/schema` → `http_request_ontology_query /api/llm/ontology-query/query` → 基于本次响应回答"青釉仰覆莲花尊"；
2. 判据：**零 execute 调用、零乱码、证据来源=本次 HTTP 响应（非 memory）、工具迭代 ≤8**；
3. 防御线：path 传 `/api/users` → `[参数错误]`；cypher 传 `CREATE` → 服务端红线拦截，返回 `[服务错误]`；
4. 服务停止时：agent 收到 `[连接失败]` → 熔断 → 如实报告，不编造。

### 通道 B（外部 Qwen CLI，本机无法直接复现）

1. git diff 确认通道 B 章节内容与改写前**逐字一致**（冻结验证）；
2. 本机手工等价验证：按通道 B 的 curl 命令逐条执行，确认端点全部可达、行为符合描述；
3. 外部调用方的实际回归由使用方自行确认（改动对其透明）。

---

## 12. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 模型无视 Step 0，本项目环境仍调 execute | 中 | 禁令级措辞 + 通道 A 命名置顶；即使误调，execute 失败结果会引导回 http_request_ontology_query |
| 外部 CLI 工具名与 execute 不一致（如 bash） | 低 | 现状既有情况，冻结不改；速查表按 HTTP 语义描述，工具名映射留给调用方运行时 |
| SKILL.md 变长后模型漏读通道 B 约束（外部调用方） | 低 | 通道 B 是其唯一可选路径，Step 0 判定后无关章节注意力权重天然低 |
| 端点速查表与实际接口漂移 | 中 | /api/llm 平面已冻结（DTO 化改造 28 项基线验证），速查表为唯一权威来源 |
| http_request_ontology_query 被注入教唆滥用 | 低 | 只读平面封闭，最坏结果是查询数据；无写入能力、无管理面访问权 |

---

## 13. 与既有方案的关系

- **否决：直接恢复 OntologyHttpTools 五件套** —— 工具自带业务语义，agent 不读 skill 也能用，违背"经由 skills"决策；且无法服务外部 Qwen CLI。
- **否决：inbox/outbox 文件桥** —— 依赖 write_file 落盘语义（从未验证），迭代开销大（写+读两次往返）。
- **否决：升级 harness 2.0.2-subagent-bugfix** —— 已实测编译启动通过，但其修复范围不含本项目两个 Windows 缺陷的确定性验证，且不解决"协议必须在 skill 里"的诉求。
- **本方案 = 五件套的"传输能力" + 文件桥的"协议上移"**：工具层哑化到只剩 HTTP 管道，协议全部沉淀在 SKILL.md，一份文件双通道服务两类调用方。
