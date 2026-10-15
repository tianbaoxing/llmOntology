# 技能激活探针设计方案（skill_checkin + load_skill_through_path 遮蔽）

> 版本：v1（2026-09-03）
> 目标：解决"load_skill_through_path 成功 ≠ 技能真正被使用"的判定问题，为技能加载后的真实遵循情况提供可自动分析的确定性证据。

## 一、背景与问题

会话轨迹中 `load_skill_through_path` 返回 success 只证明"技能文件加载了"，不证明：

1. 技能内容真正注入了模型上下文（框架问题）
2. 模型读到并遵循了技能规则（行为问题）

以会话 `08a19859`（/ontology-query 查询连镇文物信息）为例，验证技能真实激活需要三层证据手工对齐：

| 层 | 证据 | 判定 |
|---|---|---|
| 1. 结构 | tool_use.state=allowed + tool_result.state=success + 输出含 "Successfully loaded" | 加载调用成功 |
| 2. token | 加载后首轮 inputTokens 跳增（7948 → 11009，约 +3000） | 内容已注入上下文 |
| 3. 行为 | 后续工具序列符合技能规定主线（load → schema → cypher → files → grep） | 模型真实遵循 |

第 3 层最难自动化，本方案通过探针工具将其变为确定性信号。

## 二、约束（反编译已验证的事实）

| 事实 | 证据 | 对设计的影响 |
|---|---|---|
| `ToolRegistry.registerTool` 是 `Map.put`，同名覆盖后者生效 | core 2.0.0 字节码 | 理论上同名可覆盖 |
| **但** `SkillRuntime.install` 每次 middleware 处理都会检查 `load_skill_through_path`，非内置实例即 `removeTool` + 重装内置版 | harness 2.0.0 `SkillRuntime.install` 字节码 | 运行时注册同名实例会被自愈纠回，不可行 |
| `SkillRuntime` 构造函数 `new SkillLoadTool(catalogRef)` 按类名在运行时解析 | 同上 | **类路径遮蔽可行**：换类而非换实例 |
| `RuntimeContext.getSessionId()/getUserId()` 可用 | core 2.0.0 javap | 台账可落会话粒度 |
| `HarnessAgent.Builder` 无公开 middleware 挂点 | javap Builder | 不能从 middleware 层注入指令 |
| `SkillLoadTool` 依赖 `HarnessSkillEntry`/`SkillCatalog` 等 package-private 类 | javap | 遮蔽类必须同包名（`io.agentscope.harness.agent.skill.runtime`） |

项目已有同类先例：`src/main/java/io/agentscope/core/{studio,tracing}` 内嵌源码替代原依赖（见 pom.xml 注释）。

## 三、方案：类路径遮蔽 + 探针工具

### 3.1 遮蔽版 SkillLoadTool（核心）

新建 `src/main/java/io/agentscope/harness/agent/skill/runtime/SkillLoadTool.java`（FQN 与 jar 内完全一致），运行时 `target/classes` 优先于 jar，框架加载遮蔽版。

**为什么能绕开自愈拦截**：`SkillRuntime` 构造时 `new SkillLoadTool(...)` 解析到的就是遮蔽类实例，install 的实例相等检查（`if_acmpeq`）恒通过——自愈机制反而变成保护遮蔽版的机制。

内部结构 = 反编译完整还原原逻辑 + 3 处插桩：

1. `callAsync` 入口：slf4j 记 `[skill-load] skillId + sessionId`
2. `loadOne` 返回前：台账追加 `data/skill-usage.jsonl`
3. `formatSkillMarkdown` 末尾：**注入金丝雀指令段**（见 3.2）

### 3.2 金丝雀指令注入

在返回给模型的技能正文末尾追加：

```markdown
---
## 技能激活确认（系统注入，不可跳过）
本技能已加载成功。在执行任何后续动作之前，必须先调用 skill_checkin
工具报到（skillId 传 `<加载时的 skillId>`），
收到 ok 后再按上述内容执行。
```

相比 sysPrompt 全局规则的优势：

- 指令出现在技能内容正下方，上下文位置最强，遵循率最高
- 逐技能精确注入（skillId 已知）
- SKILL.md 文件零修改，不因监控需求污染业务定义

### 3.3 探针工具 skill_checkin

```
名称:   skill_checkin（readOnly=true, concurrencySafe=true）
参数:   skillId (必填) —— 即 load_skill_through_path 的 skillId 入参
        step    (可选) —— 里程碑标记：schema_done / query_done / fallback_file 等
行为:   slf4j 记 [skill-checkin] 日志 + 追加 data/skill-usage.jsonl 台账
返回:   "skill_checkin ok: {skillId}。技能已确认激活，请按技能内容继续执行"
```

无副作用业务逻辑，纯标记。`step` 参数把探针从"激活确认"扩展为"执行里程碑埋点"。

### 3.4 台账格式（data/skill-usage.jsonl）

```json
{"ts":"2026-09-03 18:00:00.123","event":"load","skillId":"ontology-query_workspace-namespaced","step":null,"sessionId":"...","userId":"admin"}
{"ts":"2026-09-03 18:00:05.000","event":"checkin","skillId":"ontology-query_workspace-namespaced","step":null,"sessionId":"...","userId":"admin"}
{"ts":"2026-09-03 18:00:20.000","event":"checkin","skillId":"ontology-query_workspace-namespaced","step":"schema_done","sessionId":"...","userId":"admin"}
```

两类事件（load / checkin）同文件追加写，按 sessionId + skillId 配对分析。

## 四、判定逻辑（最终分析口径）

| load_skill | inputTokens 跳增 | skill_checkin | 诊断 |
|---|---|---|---|
| fail | – | – | 技能未加载（文件/路径问题） |
| success | 无 | 无 | 内容未注入上下文（框架问题） |
| success | 有 | **无** | 已注入但模型未遵循（prompt 问题）→ 需强化指令 |
| success | 有 | **有** | ✓ 技能真正激活 |

分析入口（互补）：

1. **台账**：`grep sessionId data/skill-usage.jsonl` → 3 秒出结果，无需解析轨迹
2. **轨迹**：session-detail 页工具统计中出现 `skill_checkin` 即激活（可视化）

## 五、改动清单

| 文件 | 改动 |
|---|---|
| `io/agentscope/harness/agent/skill/runtime/SkillLoadTool.java` | 新建遮蔽类（反编译还原 + 3 处插桩） |
| `agent/SkillCheckinTool.java` | 新建探针工具 |
| `config/AgentScopeConfig.java` | 注册探针 |

## 六、风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 反编译还原不完整，行为漂移 | 中 | 用 CFR/fernflower 完整反编译（javap 只有签名）；基线对比测试：改造前后同一问题跑两次，diff 轨迹输出 |
| harness 升级时遮蔽类失效/编译错 | 中 | 版本锁 2.0.0；类头注释标明"遮蔽 agentscope-harness 2.0.0 的 SkillLoadTool，升级需同步重做" |
| `ToolCallParam` 是否携带 sessionId | 待验证 | 实现时 javap 确认；若没有则 load 事件不记 sessionId（探针侧 RuntimeContext 兜底） |
| 模型偶尔跳过 checkin（遵循率非 100%） | 低 | 保留 token 跳增作旁证；统计遵循率而非单次判定 |
| 每次技能加载多一轮工具调用 | 低 | 约几百 token + 3~5s，可接受 |
| 后续维护者困惑 | 低 | 项目已有 studio 遮蔽先例 + 类头注释说明 |

## 七、验证计划（e2e）

1. 启动日志确认遮蔽生效（SkillLoadTool 加载来源为 target/classes 而非 jar）
2. 问 `/ontology-query 查询XX`，断言轨迹顺序：`load_skill_through_path → skill_checkin → http_request_ontology_query`
3. 断言技能返回内容含注入段、台账出现 load + checkin 记录
4. 基线对比：与旧会话（08a19859）轨迹 diff，确认加载行为无漂移

## 八、边界（不做的事）

- 不改 harness 依赖版本、不加 middleware
- 不改现有 2 个 SKILL.md
- 不做台账的 REST 查询接口（先跑起来，需要时再加）
- 不包装/运行时替换 load 工具实例（已被反编译证伪）
