# 方案A：压缩历史恢复显示（双源合并渲染）设计文档

> 日期：2026-09-07
> 关联问题：会话触发 Compaction 压缩后，聊天页不再显示压缩之前的聊天记录
> 分析样例：会话 `17bd1b11-6186-40c9-8a3a-300a1cf5baf5`（admin 用户）

---

## 1. 背景与根因

### 1.1 压缩机制回顾

agentscope-harness 框架在每次 LLM 推理前由 `CompactionMiddleware` 检查，满足任一条件即触发压缩：

- 消息数 ≥ `triggerMessages`（默认 50 条）
- 或 Token 数 ≥ 动态阈值 `模型上下文窗口 - 20000`（模型不报告窗口则回退 160000）

压缩流程：确定 cutoff → 完整原始消息 offload 落盘 → 一次 LLM 调用生成 summary → agent 工作上下文执行 `clear()` 后只放回 `[summaryMsg] + preservedTail`。

### 1.2 根因：页面读的是"另一个文件"

项目里存在**两套独立持久化**，压缩后走了不同路径：

| 持久化 | 路径 | 压缩后内容 | 用途 |
|:---|:---|:---|:---|
| Agent 工作状态 | `data\agent-state\{userId}\{sessionId}\agent_state.json` | 仅 36 条：`[summary] + 保留尾部` | **聊天页历史展示的唯一数据源** |
| 会话转录 JSONL | `data\agent-workspace\{userId}\agents\ontology-assistant\sessions\{sessionId}.log.jsonl` | **66 条全量**（append-only，永不压缩） | 压缩时 offload 落盘，当前无任何接口消费 |

展示链路：

```
chat.html loadMessages()
  → GET /api/agent/sessions/{id}/messages
  → AgentSessionService.messages()          // 只读 agent-state
  → mapMessages(state.getContext())          // 压缩前消息已不在 context 中 → 页面看不到
```

`AgentSessionService` 类注释（L28-32）明确："workspace 侧 jsonl 日志为无害残留，不清理"——即转录文件从未被用于页面展示，但**完整历史恰恰全部保留在那里**。

### 1.3 附带受损点

- **会话标题**：`titleOf()` 取首条 USER 消息，压缩后首条 USER 是 summary（内容为英文 "You are in the middle of a conversation..."）→ 会话列表标题被污染
- **messageCount**：取 `state.getContext().size()`（36），与实际对话规模（66+）不符

---

## 2. 数据源实测（会话 17bd1b11）

### 2.1 agent_state.json 的 context 结构

```
context[0]      USER    name=__compaction_summary__  id=7ede988f-0066-4854-b4a3-532db02348e6
context[1..21]  ASSISTANT/TOOL  泰山/太行山轮次（保留尾部，结构化：ThinkingBlock/ToolUseBlock/ChatUsage）
context[22]     USER    历史事件有哪些？
context[23..29] ASSISTANT/TOOL  历史事件轮次
context[30..35] ASSISTANT/TOOL  三家分晋轮次
```

压缩前的"农民与土地""秦始皇"轮次以及"泰山"的用户提问本身都不在 context 中。

### 2.2 log.jsonl 的行结构（append-only 时序）

每行一个 JSON 对象，`type=message`，字段：`id`（与 state 中消息 id 同源）、`parentId`、`timestamp`、`role`（USER/ASSISTANT/TOOL）、`content`（**扁平文本**）、`toolCallId`。

```
第 1-30 行    农民与土地轮次 + 秦始皇轮次          ← 压缩前，仅存在于转录
第 31 行      USER  泰山，太行山有出土的文物吗？     ← 压缩前（该提问被摘要掉了）
第 32-51 行   泰山轮次工具调用与回答                 ← 与 state context[1..21] 重叠！
第 52 行      USER  历史事件有哪些？                 ← 与 state context[22] 重叠！
第 53 行      USER  summary（id=7ede988f…）         ← 锚点行
第 54-66 行   压缩后续轮次                          ← 与 state context[23..] 重叠！
```

**关键发现：state 保留尾部（tail）的消息在 log.jsonl 中位于锚点行之前**（转录按真实发生顺序追加：先记录了用户消息与压缩前的推理，压缩发生在轮中，summary 行追加在后）。

**转录文本的局限**：
- tool_call 渲染为 `[tool_call: name({json})]` 文本行
- 无 thinking 字段、无 usage（token 数）
- 工具输出截断（约 1000 字符）

### 2.3 框架 API 可用性（已对 harness/core 2.0.0 jar 反编译验证）

| API | 所属 | 作用 | 2.0.0 是否存在 |
|:---|:---|:---|:---|
| `HarnessAgent.workspaceFor(userId, sessionId)` | HarnessAgent | 取按用户隔离的 WorkspaceManager | ✅ |
| `WorkspaceManager.resolveSessionLogFile(rc, agentId, sessionId)` | WorkspaceManager | 返回 `{id}.log.jsonl` 绝对路径 | ✅ |
| `Msg.getName()` | agentscope-core | 读取消息 name（识别 `__compaction_summary__`） | ✅ |
| `RuntimeContext.builder().userId().sessionId().build()` | core | 构造运行时上下文（AgentChatService L307-310 同款） | ✅ |

agentId 固定为 `ontology-assistant`（AgentScopeConfig L98 `.name("ontology-assistant")`）。

---

## 3. 方案设计：双源合并渲染

### 3.1 总体思路

`GET /api/agent/sessions/{id}/messages` **接口契约不变**，内部改为双源合并：

```
若会话未被压缩（state 中无 __compaction_summary__ 消息）：
    完全走现状逻辑，不碰 log.jsonl（零回归风险）

若会话被压缩过：
    返回 = [压缩前消息（来自 log.jsonl，扁平渲染）]
         + [压缩摘要卡片]
         + [压缩后消息（来自 agent_state，结构化渲染——现状逻辑）]
```

### 3.2 合并算法（核心：去重切割）

```
输入：state（agent_state.json）、logPath（log.jsonl）

1. 在 state.context 中查找 name == "__compaction_summary__" 的消息 S
   （防御：找不到 → 未压缩会话，走现状；找到多条取最早一条）
2. tailStartId = S 之后第一条消息的 id
   （样例中 = context[1].id = gen-1788744729-…，即泰山轮次首个 assistant 消息）
3. 逐行解析 log.jsonl，定位 id == tailStartId 的行，其之前的所有
   type=message 行即为「压缩前消息」prefix
   （天然涵盖链式压缩：更早轮次的 summary 行也在 prefix 内，渲染为摘要卡片）
4. 返回消息序列：
      flatMap(prefix 行)            // 压缩前：扁平渲染
      + [{role: "compaction", …}]   // 摘要卡片（取 state 中 S 的完整文本）
      + mapMessages(state, 跳过 S)  // 压缩后：现有结构化渲染
```

**为什么用 tailStartId 切割而不是锚点行（summary 行）切割**：state 保留尾部的消息（泰山轮次、历史事件提问）在 log.jsonl 中位于锚点行**之前**，若以锚点行切割会把尾部消息重复渲染两遍。以 tailStartId 切割正好避开重叠区。

**时间线正确性**：合并后完整顺序 = 农民/土地 → 秦始皇 → 泰山提问 → [压缩卡片] → 泰山工具与回答 → 历史事件 → 三家分晋。压缩卡片出现的位置 = 它在 log.jsonl 中被追加的位置（历史事件提问之后），但展示上放在 prefix 末尾、tail 之前，即"压缩发生点"，语义正确。

### 3.3 压缩前消息的扁平渲染（flatMap）

| 转录行 role | 映射 | 说明 |
|:---|:---|:---|
| USER | `{role:"user", content, ts}` | 与现有 user 消息同构 |
| ASSISTANT | `{role:"assistant", ts, thinking:"", answer, tools:[…], flat:true}` | content 中的 `[tool_call: name({args})]` 行用单条正则提取为 `tools[{name, input}]`；剩余文本为 answer；无 usage 字段 |
| TOOL | 回填到**上一条** assistant 消息对应 tool 的 `output` | 按顺序就近配对（转录中 TOOL 行紧跟发起它的 ASSISTANT 行）；输出沿用现有 `summarizeOutput` 截断 |
| 非 message 类型行（compaction/summary 条目等） | 渲染为 `{role:"compaction", content}` 卡片 | 防御式处理链式压缩的历史痕迹 |

- 所有扁平消息带 `flat:true` 标记，前端据此显示「压缩前记录」角标（提示：无思考过程、无 token 统计）
- 单行解析失败（畸形 JSON）→ 跳过该行并 log.warn，不中断整体加载

### 3.4 摘要卡片

```json
{
  "role": "compaction",
  "ts": "2026-09-07 09:35:00.085",
  "content": "You are in the middle of a conversation ... <summary>…</summary>",
  "logFile": "data/agent-workspace/admin/agents/ontology-assistant/sessions/17bd1b11-….log.jsonl"
}
```

前端渲染为独立样式的「🧠 记忆压缩」卡片：默认折叠只显示一行说明（"此处发生过记忆压缩，之前的 N 条对话被摘要"），点击展开显示 summary 全文与转录文件相对路径。

### 3.5 后端改动点（仅 `AgentSessionService.java`）

1. **`messages()`**：加载 state 后检测 summary 消息；存在则调用新私有方法 `transcriptPrefix(userId, sessionId, tailStartId)` 读取并扁平映射 log.jsonl 前缀，拼装 `prefix + [card] + mapMessages(去 summary 的 state)`
2. **`transcriptPrefix()`**（新增）：
   - `agentProvider.getIfAvailable()` 判空（agent 未装配时维持现状）
   - `agent.workspaceFor(userId, sessionId)` → `resolveSessionLogFile(rc, "ontology-assistant", sessionId)` 取路径（框架 API，用户命名空间隔离，无手工拼路径）
   - `Files.readAllLines`（UTF-8）逐行 Jackson 解析
3. **`mapMessages()`**：跳过 `name == __compaction_summary__` 的消息（由卡片替代，不再渲染为用户气泡）
4. **`titleOf()`**：跳过 `__compaction_summary__` 消息后再取首条 USER（修复标题被英文 summary 污染的问题；驾驶舱统计复用处同步受益）

**降级策略**（任何一步失败均不报错，log.warn 后维持现状展示）：
- log.jsonl 文件不存在 / 空文件
- tailStartId 在转录中找不到（格式不符）
- agent 未装配（503 路径不变）

### 3.6 前端改动点（仅 `chat.html`）

`addStaticAgentMsg`（历史渲染入口）新增两个分支：

1. `m.role == "compaction"` → 摘要卡片（折叠/展开，独立底色 + 左侧图标）
2. `m.flat == true` → 复用现有 assistant/user 气泡，附加「压缩前记录」小角标；thinking 区域不渲染（后端已给空串）；tools 渲染复用现有工具行样式（无 token 徽章）

不改：发送流程、SSE 实时渲染、会话列表加载逻辑。

### 3.7 明确不改的部分

- **messageCount**（会话列表）：保持 state 口径，不为此在 list() 全量读转录（性能考虑，标注为已知差异）
- **`.jsonl`（context 转录）**：不用它做数据源，它与压缩语义耦合；只用 `.log.jsonl`（append-only 保证）
- **框架代码、API 契约、数据库、配置**：零改动

### 3.8 设计决策记录（含已接受的取舍）

| 决策 | 理由 |
|:---|:---|
| 压缩前消息无 token 统计，页面顶部"总计 tokens"只累计压缩后部分 | log.jsonl 不含 usage；补齐需重放全量请求，成本不可接受 |
| 压缩前工具调用按 `[tool_call]` 正则提取渲染，无 thinking | 转录本身无 thinking 字段 |
| 未压缩会话完全走现状 | 零回归风险 |
| 读转录用框架 `resolveSessionLogFile` 而非手工拼路径 | 跟随框架升级，路径规则变化自动兼容 |
| 解析失败降级而非报错 | 历史会话的展示不应因转录文件损坏而不可用 |

### 3.9 安全与并发

- **路径安全**：sessionId 已有 `^[a-zA-Z0-9_\-.]+$` 白名单校验（现有 validate），log 路径经 `workspaceFor(userId, …)` 命名空间隔离，无路径穿越面
- **权限**：沿用 Controller 现有登录态校验，转录路径天然限定在 `{userId}/` 下，无越权读取
- **并发**：log.jsonl append-only，messages() 只读快照；会话进行中加载历史，最多少显示正在生成的最后几行，无一致性风险
- **性能**：单会话一次文件读 + 逐行解析，样例 66 行 70KB，毫秒级；仅被压缩会话才触发

---

## 4. 验收标准

| # | 场景 | 预期 |
|:---|:---|:---|
| 1 | 打开会话 17bd1b11 | 完整时间线：农民/土地 → 秦始皇 → 泰山提问 → 压缩卡片（折叠）→ 泰山工具与回答 → 历史事件 → 三家分晋；压缩前消息带「压缩前记录」角标 |
| 2 | 打开任一**未压缩**历史会话 | 输出与改造前逐字段一致（抓基线对比） |
| 3 | 临时移走 log.jsonl 后打开 17bd1b11 | 降级为现状展示（压缩卡片 + 尾部消息），无报错 |
| 4 | 会话列表 | 17bd1b11 标题不再是 "You are in the middle…"，而是压缩后首条真实提问（"历史事件有哪些？"） |
| 5 | 摘要卡片展开 | 显示 summary 全文 + 转录文件相对路径 |
| 6 | 顶部 token 统计 | 仅累计压缩后消息（与改造前数值一致，不因合并而虚增） |
| 7 | 在压缩后继续对话再触发一次压缩（链式） | 新 summary 卡片出现在正确位置，旧 prefix 不重复渲染 |

## 5. 改动清单

| 文件 | 改动 | 预估规模 |
|:---|:---|:---|
| `src/main/java/com/conca/ontology/service/AgentSessionService.java` | messages() 合并逻辑 + transcriptPrefix() 新增 + mapMessages()/titleOf() 跳过 summary | +120 行左右 |
| `src/main/resources/static/pages/chat.html` | addStaticAgentMsg 增 compaction 卡片分支与 flat 角标 | +50 行左右 |
