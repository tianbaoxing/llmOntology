# 子 Agent 注册表膨胀问题分析与处理方案

> 现象：服务重启后 `GET /api/agent/subagents` 显示 1 个子 agent；发送一条聊天消息后再调用，变成 7 个。
> 日期：2026-09-03

## 一、根因分析

**1 → 7 的膨胀不是内存泄漏，是「动态子 agent 持久化 + 注册表懒加载」机制的结果：**

### 1. 重启后显示的 1 个：静态默认子 agent

harness 框架（agentscope-harness 2.0.0）在 `HarnessAgentBuilderSupport.buildSubagentEntries` 中静态注册了一个默认子 agent：

- agentId：`general-purpose`
- description："General-purpose subagent with same capabilities as the main agent. Use for any isolated task that can be fully delegated."

`AgentScopeConfig` 中 `disableSubagents()` / `disableDynamicSubagents()` 均被注释（最小化开放面但未启用），所以子 agent 能力全开。

### 2. 磁盘上已存在 6 个动态声明文件

`data/agent-workspace/subagents/` 下有 6 个模型自主创建并落盘的 .md 声明文件（frontmatter 含 name/description/emoji/color + 完整人格 prompt）：

| 文件 | 内容 |
|---|---|
| academic-historian.md | 历史学家人格 |
| academic-geographer.md | 地理学家人格 |
| academic-anthropologist.md | 人类学家人格 |
| marketing-wechat-operator.md | 微信运营人格 |
| marketing-weibo-strategist.md | 微博策略人格 |
| marketing-xiaohongshu-operator.md | 小红书运营人格 |

这些是模型在聊天过程中通过 `AgentSpawnTool`（动态子 agent 创建工具）自主创建、持久化到 workspace 的。

### 3. 发一条消息后变 7 个：注册表懒加载时序

`DynamicSubagentsMiddleware.onReasoning` 的执行逻辑（反编译确认）：

```
每次模型推理前
  → reloadEntries(ctx)
    → 扫描 <workspace>/subagents/*.md（SUBAGENTS_DIR="subagents", GLOB="*.md"）
    → agentManager.replaceAgents(静态 + 文件加载的)   // 全量替换
```

而 `AgentChatService.listSubagents()` 读的是**内存注册表**（`getAgentFactories()`）。注册表只在模型推理时才与磁盘同步，因此接口呈现"重启 1 个 → 聊天后 7 个"的时间差。

### 4. 副作用（为什么要处理）

- 7 个子 agent 清单会注入模型 prompt，持续膨胀消耗 tokens 并干扰模型决策
- `subagents/` 目录在共享 workspace 根下，跨用户可见（admin/conca/evalbot 等共用）
- 模型可能在闲聊中滥建 agent，无数量上限

## 二、处理方案

### 方案 A（推荐）：业务不需要子 agent，整个关掉

本项目是图谱问答服务，e2e 链路一直是单 agent + 技能跑通的。在 `AgentScopeConfig.java` 的 builder 链上取消注释：

```java
.disableSubagents()          // 连静态 general-purpose 一起关，/api/agent/subagents 返回空
```

若只想关"模型自主创建"、保留静态 general-purpose，则改用：

```java
.disableDynamicSubagents()
```

注意：
- **不能**调 `disableDynamicSkills()`——它会跳过 `HarnessSkillMiddleware` 装配，导致技能既不注册工具也不注入 prompt（项目已踩过的坑）
- `disableSubagents` / `disableDynamicSubagents` 是独立开关，不影响技能装配

最后清理磁盘：删除 `data/agent-workspace/subagents/` 目录下全部 6 个 .md。

### 方案 B：保留动态能力，修接口一致性

改 `AgentChatService.listSubagents()`：不读内存注册表，改为直接解析 `<workspace>/subagents/*.md` 的 frontmatter（name/description）与静态清单合并。保证"页面展示 = 模型可见"不受懒加载时序影响。改动仅一处。

### 方案 C：保留能力 + 治理膨胀

- 声明目录按用户隔离：`workspace/{userId}/subagents/`，避免跨用户污染
- 在 `AgentSpawnTool` 外包一层上限（如 ≤3 个）
- 会话结束或定期清理动态声明

### 自愈特性（无论选哪个方案都要知道）

`replaceAgents` 是全量替换语义：即使不重启服务，删掉目录里的 .md 后，下次发消息注册表就缩回静态那 1 个。但只要 `AgentSpawnTool` 还开着，模型后续仍可能再建——所以方案 A 才是治本。

## 三、结论建议

选 **方案 A**（`disableSubagents()` + 删除 `data/agent-workspace/subagents/` 目录）：一行配置 + 一次文件清理，与项目"最小化开放面"的装配原则一致。
