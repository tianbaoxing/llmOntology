# llmOntology 评测器设计方案

> 版本：v2（2026-08-26）
> 参照项目：`C:\me\kaiyuan\agenttrace\OpenJudge-main`（Python 版 LLM/Agent 评测框架）
> 本文档是完整设计方案，含一期/二期验收标准与 65 个内置评测器选型清单，供决策后分阶段实现。

---

## 1. 背景与目标

llmOntology 的 AI 问答（/chat 页面，AgentScope HarnessAgent + 5 个图谱工具）目前没有任何量化评测手段，回答质量只能靠人工点开看。本方案在项目内构建一套 **Java 版迷你评测框架**，实现：

1. 用数据集（图谱问题 + 期望答案）批量驱动 agent 回答；
2. 用多种评测器（规则判分 / LLM 判分 / Agent 过程判分）对回答打分；
3. 页面可视化运行进度与结果，指标可对比、可回归。

**不引入 Python 依赖**，纯 Java 实现，架构对齐 OpenJudge，工程模式复用项目已有的 BackupService 异步任务模式。

---

## 2. OpenJudge 评测器实现分析

### 2.1 核心架构（三层）

```
Grader（评测器）─── 判分单元
  BaseGrader 抽象基类（base_grader.py）
    ├── FunctionGrader   规则/函数式：用户传入确定性判分函数
    ├── LLMGrader        LLM-as-judge：模板渲染 prompt → LLM → 结构化输出 {score, reason}
    └── AgenticGrader    工具增强：预构建 ReActAgent 自主调工具取证后判分
  统一入口 aevaluate()：deepcopy 隔离状态 → 资源执行器 → 套用评测策略
  子类只实现 _aevaluate()，返回 GraderScore / GraderRank / GraderError

Runner（执行引擎）─── 并发跑批
  GradingRunner + GraderConfig（评测器 + mapper 字段映射）
  信号量并发（默认 32），单条异常包装为 GraderError 不中断整批
  Aggregator 多指标加权汇总

EvaluationStrategy（评测策略）─── 对抗 LLM 随机性
  Direct 单次 / Average 多次平均 / Voting 多数投票
```

### 2.2 关键数据结构

| 结构 | 字段 | 说明 |
|---|---|---|
| GraderMode | POINTWISE / LISTWISE | 单样本打分 / 多样本排名（本项目只用 POINTWISE） |
| GraderScore | name, score, reason, metadata | 统一结果；LLM 类多为 1-5 分，规则类为 0-1 |
| GraderError | name, error, reason | 单条失败不中断 |
| dataset | `List[dict]`（query/response/reference...） | mapper 做字段名映射后喂给评测器 |

### 2.3 评测器总量

文件系统实测 **65 个内置评测器**，分 17 个子类目（详见第 5 章清单）。实现类型分两类：
- **Code-Based（规则类）**：算法判分，快、免费、确定性；
- **LLM-Based（模型类）**：LLM 按评分细则打分，适合主观质量。

---

## 3. 总体设计（Java 移植）

### 3.1 包结构

```
com.conca.ontology.eval
├── grader
│   ├── Grader.java            # 接口：String name(); String description();
│   │                          #        GraderScore evaluate(EvalSample s, AgentRecord r);
│   ├── GraderScore.java       # record：name/score/reason/error/metadata
│   ├── RuleGrader.java        # 规则评测器基类（≈FunctionGrader，Code-Based）
│   ├── LlmJudgeGrader.java    # LLM 评测器基类（≈LLMGrader，二期）
│   └── builtin/               # 内置评测器实现（一期规则子集 → 二期 LLM 子集）
├── runner
│   ├── EvalTask.java          # 异步评测任务（对齐 BackupService 任务模式：单任务 409、进度、先 .tmp 后 rename）
│   └── EvalRunner.java        # 遍历 dataset × graders，单条异常捕获为 error 结果
└── EvalService.java           # 编排：加载数据集 → 逐条驱动 agent → 判分 → 汇总落盘
```

### 3.2 核心数据流

```
data/eval/datasets/*.jsonl（数据集）
        │  逐条
        ▼
EvalService ──► 内部调用 AgentChatService 发起会话
        │        （复用现有 agent 装配；不走前端 SSE，服务端直接收集事件流）
        ▼
AgentRecord：最终 answer + 完整轨迹（thinking / tool 调用与返回 / 轮次 / tokens / 时长）
        │
        ▼
Grader 列表逐个判分（RuleGrader 本地判定；LlmJudgeGrader 二期走 OpenRouter）
        │
        ▼
data/eval/results/eval-yyyyMMdd-HHmmss.json（每样本 × 每评测器的 score/reason/metadata + 汇总指标）
        │
        ▼
/eval 页面轮询展示
```

### 3.3 数据集格式（JSONL）

```json
{"id":"q001","query":"定窑白釉莲纹大碗是什么朝代的？",
 "expected":{"type":"contains","value":["北宋"]},
 "graders":["string_contains","number_accuracy"]}
{"id":"q002","query":"图谱里一共有多少件唐代文物？",
 "expected":{"type":"cypher","cypher":"MATCH (a:CulturalRelic)-[:ERA]->(e:Era) WHERE e.name CONTAINS '唐' RETURN count(a)","value":123},
 "graders":["cypher_match"]}
{"id":"q003","query":"介绍一下青花釉里红云龙纹天球瓶",
 "expected":{"type":"contains","value":["清","乾隆","青花"]},
 "rubric":"答案应包含朝代、釉彩、器型信息，不得编造图谱中不存在的细节",
 "graders":["string_contains","llm_correctness","llm_hallucination"]}
```

- `expected.type`：`exact`（整串等值）/ `contains`（含全部关键词）/ `number`（文本中数字比对，容差可配）/ `cypher`（附带一条期望结果的标准查询，判分器直连 Neo4j 只读通道比对）
- `graders`：该样本启用哪些评测器，缺省 = 全部已启用的
- `rubric`：LLM 评测器的补充评分细则（二期使用）

### 3.4 REST API（对齐现有 URL 风格与 DTO 规范）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/eval/datasets` | 数据集列表（目录扫描，返回文件名+条数） |
| GET | `/api/eval/datasets/{name}/file` | 数据集内容预览（前 N 条） |
| GET | `/api/eval/graders` | 可用评测器清单（名称/描述/类型 规则|LLM） |
| POST | `/api/eval/runs` | 创建评测任务（body：dataset 名 + 评测器清单）；已有任务运行中返回 409 |
| GET | `/api/eval/runs` | 历史任务列表 |
| GET | `/api/eval/runs/{id}` | 任务进度/详情（轮询） |
| GET | `/api/eval/runs/{id}/file` | 下载完整结果 JSON |

权限：登录用户（AuthInterceptor 默认拦截）；不进 `/api/llm/**` 冻结平面。Controller 进 admin 分组，@Tag/@Operation/@Schema 注解齐全，Swagger 可见。

### 3.5 页面（eval.html，复用 common.css/common.js）

导航栏新增「评测中心」，页面三区块：
1. **运行区**：数据集下拉 + 评测器勾选（按 规则/LLM 分组显示）+ 运行按钮；
2. **进度区**：复制备份页轮询模式，显示 `已完成 n / 总数 N`、当前样本 query、各阶段耗时；
3. **结果区**：指标卡（总分、pass_rate、error 数、平均耗时、二期：tokens）+ 明细表（每行一个样本：query / answer 摘要 / 各评测器得分徽章 / reason 点击展开 / 轨迹折叠查看）。

---

## 4. 一期：规则评测通道（完整可验收）

### 4.1 一期范围

| 模块 | 内容 |
|---|---|
| 框架 | Grader 接口、GraderScore、RuleGrader 基类、EvalRunner、EvalService（异步任务+进度+落盘） |
| 规则评测器（5 个） | `string_exact`（整串等值）、`string_contains`（关键词全包含，缺一按比例扣分）、`number_accuracy`（文本数字按序比对+容差，对齐 OpenJudge NumberAccuracyGrader 语义）、`cypher_match`（标准查询结果比对，走 GraphQueryService 只读通道+红线正则）、`format_json`（回答若应为 JSON 时校验语法，可一期先空实现占位） |
| 轨迹采集 | AgentRecord：answer + 工具调用序列（名称/入参/返回摘要）+ 轮次 + tokens + 时长，随结果落盘（为二期 Agent 类评测器备料） |
| API | 3.4 全部 7 个端点 |
| 页面 | eval.html 三区块 + 导航入口 |
| 示例数据 | 内置 `demo.jsonl`：10 条国博图谱问题，覆盖 exact/contains/number/cypher 四种判分 |

### 4.2 一期验收标准（逐条可操作）

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| A1 | 编译 | `mvnw compile` | BUILD SUCCESS，无新警告 |
| A2 | 页面入口 | 登录后看导航栏 | 出现「评测中心」，点击进入 /eval，三区块可见 |
| A3 | 数据集接口 | `GET /api/eval/datasets` | 返回 demo.jsonl，条数=10 |
| A4 | 评测器清单 | `GET /api/eval/graders` | 返回 5 个规则评测器，type=rule |
| A5 | 运行任务 | 页面勾选 demo + 全部评测器，点运行 | 创建成功返回任务 id；进度轮询从 0% 单调推进到 100%；无卡死 |
| A6 | 结果明细 | 任务完成后看结果表 | 10 行；每行每评测器有 0-1 分数与 reason；answer 摘要与轨迹可展开 |
| A7 | 判分正确性抽查 | 取 q001（contains 北宋）等 3 条 | 期望命中的样本 contains=1.0；人为构造答错的期望值时得分<1.0（可临时改 demo 验证） |
| A8 | cypher 判分 | q002 类样本 | 判分器执行期望查询返回数值，与 agent 回答中的数字比对；Neo4j 停机时该条记 error 不影响其它样本 |
| A9 | 异常隔离 | 数据集中手工掺 1 条坏样本（如非法 JSON 字段） | 该行显示 error，整批正常完成，指标中 error 数=1 |
| A10 | 并发互斥 | 任务运行中再点运行 | 返回 409，提示已有任务 |
| A11 | 结果落盘 | 查看 `data/eval/results/` | 生成 `eval-*.json`，内容与页面一致（query/answer/各分数/reason/汇总） |
| A12 | 权限 | 未登录直接 `GET /api/eval/runs` | 401 |
| A13 | Swagger | 打开 /swagger-ui/index.html admin 分组 | 出现 eval 标签的 7 个端点，含 @Operation 描述 |
| A14 | 只读红线 | 检查 cypher_match 评测器 | 仅允许只读查询（复用红线正则），数据集里写 DROP 等语句直接判参数错误 |

---

## 5. 二期：LLM 评测通道 + 内置评测器移植（待选型）

### 5.1 二期框架内容

| 模块 | 内容 |
|---|---|
| LlmJudgeGrader 基类 | 模板渲染（query/answer/context/rubric）→ OpenRouter 调用（复用 `.env` 的 OPENROUTER_API_KEY 与 `app.agent.base-url`）→ 解析结构化 JSON `{score, reason}`；失败重试 1 次，仍失败记 GraderError |
| 分数归一化 | OpenJudge 的 LLM 评测器多为 1-5 分制，统一换算为 0-1（(score-1)/4），页面同时显示原始分 |
| context 注入 | 对 hallucination/groundedness 类评测器，把该样本 agent 的图谱查询返回（轨迹中提取）作为上下文喂给 judge，实现「以图谱为证据」的事实核查 |
| 多次评测策略 | （可选开关）LLM 评测器支持 run=2 取平均（对齐 OpenJudge AverageEvaluationStrategy），降噪 |
| 成本统计 | 结果中累计 judge tokens、模型名、时长；任务级汇总 |
| 页面增强 | 评测器勾选分组（规则 / LLM）；结果表区分来源 tag；指标卡增加 tokens |

### 5.2 OpenJudge 65 个内置评测器完整清单

> 标注说明：**适用度** ★★★=强烈推荐移植 / ★★=可选 / ★=边缘 / ☆=不适用（附原因）。
> 「实现」列：规则=Java 本地算法（免费、毫秒级）；LLM=judge 模型调用；Agent=LLM+轨迹数据。
> 一期已含的等价物单独注明，无需重复开发。

#### A. 通用质量类（common，6 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 1 | CorrectnessGrader | 对照参考答案判正确性 | LLM | 1-5 | ★★★ | 二期首选。朝代/窑口/出土地等事实核对 |
| 2 | HallucinationGrader | 检测编造信息（以 context 为证据） | LLM | 1-5 | ★★★ | context=图谱查询结果，核查回答是否编造 |
| 3 | RelevanceGrader | 回答与问题的相关性 | LLM | 1-5 | ★★★ | 检测跑题、答非所问 |
| 4 | InstructionFollowingGrader | 是否遵循指令（格式/约束） | LLM | 1-5 | ★★ | 若数据集要求"仅回答朝代"等约束时有用 |
| 5 | HarmfulnessGrader | 有害内容检测 | LLM | 1-5 | ★ | 本地封闭文博系统，安全风险低 |
| 6 | SearchCorrectnessGrader | 搜索结果正确性 | LLM | 1-5 | ★ | 本项目是图谱查询不是搜索引擎，语义部分被 cypher_match 覆盖 |

#### B. 文本规则类（text，3 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 7 | StringMatchGrader | 9 种串匹配（exact/prefix/suffix/regex/contains_all/any/词重叠/字符重叠） | 规则 | 0-1 | ★★★ | **一期 string_exact/string_contains 已含核心**；二期可补 regex/重叠率模式 |
| 8 | NumberAccuracyGrader | 文本数字按序比对+容差 | 规则 | 0-1 | ★★★ | **一期已含**（对齐其正确数/总数语义） |
| 9 | SimilarityGrader | 15+ 相似度算法（BLEU/ROUGE/F1/编辑距离/Jaccard…） | 规则 | 0-1 | ★★ | 开放式回答与参考简介的覆盖度；中文分词需简化（按字符 n-gram），建议只移植 rouge1/rougeL/f1/jaccard/edit_distance 5 种 |

#### C. Agent 工具调用类（agent/tool，7 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 10 | ToolCallAccuracyGrader | 工具调用正确性（含参数） | LLM | 1-5 | ★★★ | 评估 agent 选的工具和参数对不对（如该用 graph_query 却用了 ontology_ask） |
| 11 | ToolSelectionGrader | 工具选择合理性 | LLM | 1-5 | ★★★ | 与 10 互补，侧重"该不该用这个工具" |
| 12 | ToolParameterCheckGrader | 参数抽取准确性（防编参数） | LLM | 0/1 | ★★ | 检查 Cypher 入参是否来自问题本身 |
| 13 | ToolCallSuccessGrader | 工具调用是否成功执行 | LLM | 0/1 | ★★ | 项目轨迹已含工具返回，可先做**规则版**：检测返回是否 [参数错误]/[服务错误] 前缀（对齐项目错误分层约定），更准更省 |
| 14 | ToolCallStepSequenceMatchGrader | 多步工具序列与参考序列对齐比对 | 规则 | 0-1 | ★★ | 需要人工写"标准工具序列"，成本高 |
| 15 | ToolCallPrecisionRecallMatchGrader | 平铺工具调用的 P/R 匹配 | 规则 | 0-1 | ★★ | 同上，需参考序列 |
| 16 | ToolUsageEfficiencyGrader | 工具使用效率（是否过度调用） | LLM | 1-5 | ★★ | maxIters=8 场景下检测绕圈查询 |

#### D. Agent 轨迹类（agent/trajectory，5 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 17 | TrajectoryAccuracyGrader | 整条轨迹是否达成目标（3=高效达成/2=达成但绕路/1=失败） | LLM | 1-3 | ★★★ | 二期核心，直接评"这次问答全程干得好不好" |
| 18 | TrajectoryComprehensiveGrader | 轨迹多维综合评分（贡献/相关/准确/效率） | LLM | 0-1 | ★★ | 17 的加强版，judge 成本更高 |
| 19 | TrajectoryErrorRecoveryGrader | 出错后能否自我恢复 | LLM | - | ★ | 依赖失败注入，工程复杂 |
| 20 | TrajectoryStepEfficiencyGrader | 单步效率 | 规则 | 0-1 | ★ | 检测多余步骤 |
| 21 | ObservationInformationGainGrader | 观察序列信息增益（重复观察扣分） | 规则 | 0-1 | ★★ | 可用工具返回文本相似度实现，检测重复查询 |

#### E. Agent 推理/回答/记忆/规划/反思/行动类（agent 其余，16 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 22 | ReasoningGroundednessGrader | 推理是否以观察/数据为据 | LLM | 1-5 | ★★★ | 检测 thinking 是否基于图谱返回而非臆测 |
| 23 | ReasoningCoherenceGrader | 推理连贯性 | LLM | 1-5 | ★★ | |
| 24 | ResponseCompletenessGrader | 回答完整性（该说的说全没） | LLM | 1-5 | ★★★ | "介绍文物"类开放问题的必备指标 |
| 25 | ResponseHelpfulnessGrader | 回答有用性 | LLM | 1-5 | ★★★ | 与 24 成对 |
| 26 | ActionAlignmentGrader | 行动与计划一致性 | LLM | 0/1 | ★ | 需要 plan/action 结构，本项目轨迹弱化此结构 |
| 27 | ActionLoopDetectionGrader | 重复行为死循环检测 | 规则 | 0-1 | ★★ | **建议规则版**：连续相同工具+相同参数即判循环，实现极简 |
| 28 | MemoryAccuracyGrader | 记忆内容事实性 | LLM | 0/1 | ★ | 评 agent 记忆系统（MEMORY.md），与问答质量间接相关，三期后考虑 |
| 29 | MemoryDetailPreservationGrader | 记忆细节保留 | LLM | 0/1 | ★ | 同上 |
| 30 | MemoryRetrievalEffectivenessGrader | 记忆检索有效性 | LLM | 0/1 | ★ | 同上 |
| 31 | PlanDecompositionGrader | 计划分解质量 | LLM | - | ★ | 本项目 agent 无显式计划步骤 |
| 32 | PlanFeasibilityGrader | 计划可行性 | LLM | 0/1 | ★ | 同上 |
| 33 | ReflectionAccuracyGrader | 反思准确性 | LLM | 0/1 | ★ | 本项目 agent 无反思模块 |
| 34 | ReflectionOutcomeUnderstandingGrader | 结果理解准确性 | LLM | 0/1 | ★ | 同上 |
| 35 | ReflectionProgressAwarenessGrader | 进度感知准确性 | LLM | 0/1 | ★ | 同上 |
| 36 | ObservationInformationGain（已列 21） | - | - | - | - | - |

（注：#36 为占位，E 组实际 15 个独立评测器，#21 已在 D 组列出。）

#### F. 多轮对话类（multi_turn，7 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 37 | ContextMemoryGrader | 跨轮上下文记忆 | LLM | 1-5 | ★★ | chat 是多轮的；需把数据集升级为多轮脚本（二期数据集格式扩展） |
| 38 | AnaphoraResolutionGrader | 指代消解（"它/这件"） | LLM | 1-5 | ★★ | "它是什么朝代的？"典型场景 |
| 39 | TopicSwitchGrader | 话题切换应对 | LLM | 1-5 | ★ | |
| 40 | SelfCorrectionGrader | 根据反馈纠错 | LLM | 1-5 | ★ | |
| 41 | InstructionClarificationGrader | 主动澄清模糊问题 | LLM | 1-5 | ★★ | "那件碗"指代不明时该追问 |
| 42 | ProactiveInteractionGrader | 主动交互意识 | LLM | 1-5 | ★ | |
| 43 | ResponseRepetitionGrader | 回答重复检测 | LLM | 1-5 | ★ | 可先用规则版 n-gram 重复率 |

#### G. 格式类（format，6 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 44 | JsonValidatorGrader | JSON 语法校验 | 规则 | 0/1 | ★★ | 若未来要求 agent 输出结构化 JSON |
| 45 | JsonMatchGrader | JSON 结构深度比对 | 规则 | 0/1 | ★ | |
| 46 | LengthPenaltyGrader | 过短/过长惩罚 | 规则 | ≤0 | ★★ | 检测"未查询到"式敷衍与刷屏 |
| 47 | NgramRepetitionPenaltyGrader | n-gram 重复惩罚 | 规则 | ≤0 | ★ | |
| 48 | ReasoningFormatGrader | think/answer 标签格式 | 规则 | 0/1 | ☆ | 项目自有事件流，无此标签约定 |
| 49 | ReasoningToolCallFormatGrader | 工具调用格式校验 | 规则 | 0/1 | ☆ | 工具调用由 harness 结构化产生，格式天然合法 |

#### H. 代码/数学类（code + math，8 个）

| # | 评测器 | 功能 | 实现 | 适用度 | 说明 |
|---|---|---|---|---|---|
| 50 | CodeExecutionGrader | 跑测试用例判代码 | 规则 | ☆ | 项目无代码生成需求 |
| 51 | SyntaxCheckGrader | Python AST 语法检查 | 规则 | ☆ | 同上 |
| 52 | CodeStyleGrader | 代码风格 | 规则 | ☆ | 同上 |
| 53 | PatchSimilarityGrader | 补丁相似度 | 规则 | ☆ | 同上 |
| 54 | CodeBugDetectionGrader | 代码 bug 检测 | LLM | ☆ | 同上 |
| 55 | CodeComplexityGrader | 代码复杂度 | 规则 | ☆ | 同上 |
| 56 | CodeSecurityGrader | 代码安全 | LLM | ☆ | 同上 |
| 57 | MathExpressionVerifyGrader | 数学表达式等价验证 | 规则 | ☆ | 图谱问答无数学推导；数量题由 number_accuracy 覆盖 |

#### I. 多模态类（multimodal，3 个）

| # | 评测器 | 功能 | 实现 | 适用度 | 说明 |
|---|---|---|---|---|---|
| 58 | ImageCoherenceGrader | 图文一致性 | LLM | ☆ | 图谱仅存官网原图 URL，无生图/看图问答 |
| 59 | ImageHelpfulnessGrader | 图片有用性 | LLM | ☆ | 同上 |
| 60 | TextToImageGrader | 文生图质量 | LLM | ☆ | 同上 |

#### J. 技能包类（skills，5 个）

| # | 评测器 | 功能 | 实现 | 分制 | 适用度 | 说明 |
|---|---|---|---|---|---|---|
| 61 | SkillCompletenessGrader | SKILL.md 是否够详细可执行 | LLM | 1-3 | ★ | 评的是技能文件质量（ontology-query 等），不是问答质量；想优化技能文档时可单次使用 |
| 62 | SkillDeclarationAlignmentGrader | 声明与实际行为一致性 | LLM | 1-3 | ★ | |
| 63 | SkillRelevanceGrader | 技能与任务匹配度 | LLM | 1-3 | ★ | |
| 64 | SkillDesignGrader | 技能结构设计 7 维评分 | LLM | 1-5 | ★ | |
| 65 | SkillThreatAnalysisGrader | 技能安全威胁扫描 | LLM | 1-4 | ★ | |

### 5.3 二期推荐选型（默认集，共 12 个）

若不做裁剪决策，二期按以下默认集实现（兼顾价值与成本，judge 调用量 = 样本数 × 评测器数）：

| 来源 | 移植项 | 数量 |
|---|---|---|
| LLM 通用 | correctness、hallucination、relevance、response_completeness、response_helpfulness | 5 |
| LLM Agent | tool_call_accuracy、tool_selection、trajectory_accuracy、reasoning_groundedness | 4 |
| 规则补强 | string_match 补 regex/词重叠模式、similarity（5 种中文可用算法）、tool_call_success（规则版）、action_loop（规则版） | 3 组 |

**可再选**（checkbox，实现成本另计）：instruction_following、tool_parameter_check、tool_usage_efficiency、observation_information_gain、response_repetition（规则版）、length_penalty、多轮对话 4 件套（context_memory/anaphora_resolution/instruction_clarification/response_repetition LLM 版，需多轮数据集格式扩展）。

**明确不做**：代码/数学（50-57）、多模态（58-60）、反思/计划/记忆类（28-35，本项目 agent 无对应模块）、技能包类（61-65，与问答质量无关）。

### 5.4 二期验收标准

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| B1 | 评测器清单 | `GET /api/eval/graders` | LLM 类评测器出现，type=llm，含 description |
| B2 | LLM 判分运行 | demo 数据集勾选 correctness + hallucination 运行 | 每样本两个 0-1 分（附原始 1-5 分）+ 中文 reason；抽查 3 条 reason 与答案实际质量相符 |
| B3 | 证据注入 | 对 q003（带图谱查询轨迹的样本）看 hallucination 的 reason | reason 中能引用图谱查询结果作为依据（metadata 含 context 截断串） |
| B4 | 轨迹类评测 | 勾选 trajectory_accuracy 运行 | 得分反映该次问答全程质量；工具调用序列在 reason 中被提及 |
| B5 | 失败重试与降级 | 临时改错 API key 运行 | judge 重试 1 次后该评测器记 error，其余规则评测器得分不受影响，整批完成 |
| B6 | 成本统计 | 任务完成后看指标卡与结果文件 | 显示 judge 总 tokens、调用次数、模型名、总时长 |
| B7 | 未配置 key | 删 .env 重启后创建含 LLM 评测器的任务 | 409 + 文案「AI 服务未配置（缺少 OPENROUTER_API_KEY）」（对齐现有约定） |
| B8 | 平均策略开关 | 数据集或运行参数 run=2 | 同样本两次 judge 取平均，结果 metadata 记录两次原始分 |
| B9 | 归一化正确性 | 任一 LLM 评测器原始分 5 → 页面 1.0；原始 3 → 0.5 | 换算无误，明细同时展示两种分制 |
| B10 | 页面分组 | 评测器勾选区 | 「规则评测」「LLM 评测」两组分区展示，结果表得分徽章带来源 tag |

---

## 6. 三期：Agentic 评测 + 工程化闭环（可选，按需立项）

三期将评测系统从「打分工具」升级为「持续质量保障闭环」：引入 OpenJudge 最高阶的 AgenticGrader（工具增强评测），并补齐数据集管理、回归对比、驾驶舱集成等工程化能力。四个模块相互独立，可拆开单独立项。

### 6.1 模块一：AgenticGrader（Java 版，Agentic 评测通道）

**定位**：对应 OpenJudge 的 AgenticGrader——评测器本身就是一个带工具的 agent，自主决定查什么、查几次，拿到证据后再打分。与二期 LLM 评测器的本质区别：二期 judge 只看「喂给它的上下文」（静态），Agentic judge 自己去图谱取证（动态），能发现「回答正确但查证路径不存在」的深层问题。

**设计**：

| 项 | 方案 |
|---|---|
| 装配 | 仿照 AgentScopeConfig 装配**评测专用 agent**：同一 OpenRouter 模型通道，仅注册 graph_query + ontology_schema 两个只读工具（不带文件/记忆工具，避免评测被无关能力干扰）；maxIters=5（评测只需取证，不需长链路） |
| 输入 | query（原问题）+ answer（被评测的回答）+ rubric（评分细则） |
| 评测流程 | judge agent 收到「请验证以下回答是否与图谱事实一致」任务 → 自主调 graph_query 核对回答中的每个事实断言（朝代/窑口/数量/出土地） → 输出结构化 JSON `{score, reason, evidence[]}` |
| 证据链 | 每条 evidence = {断言原文, 执行的 Cypher, 图谱返回摘要, 是否证实}，随结果落盘，页面上可逐条展开——这是区别于 LLM 评测器的最大增值：**每分都带可复查的证据** |
| 成本控制 | 单样本取证调用次数上限（如 8 次 graph_query），超限即终止并按已获证据判分；reason 中注明取证截断 |
| 配置 | `app.eval.agent.*`（独立 base-url/model 可选，缺省复用 agent 配置）；未配置 OPENROUTER_API_KEY 时与二期同文案 409 |

**判分语义**：score ∈ [0,1]，= 证实断言数 / 总断言数（agent 自行拆解断言）；reason 必须引用证据编号。

### 6.2 模块二：回归对比（Run Diff）

**定位**：换模型、改提示词、改技能后，量化「变好还是变坏」。对应 OpenJudge 的 Analyzer 思路（其 validation/ 下有 accuracy/f1/precision/recall 等分析器）。

**设计**：

| 项 | 方案 |
|---|---|
| 数据结构 | 结果文件中记录 run 元信息：dataset 名 + 哈希（内容变更可检测）、模型名、评测器清单、时间；对比时校验两次 run 的 dataset 哈希一致，不一致提示「数据集已变更，对比仅供参考」 |
| 对比维度 | ① 总体：pass_rate/平均分/error 数/tokens 增减（带 ▲▼ 箭头与百分比）；② 按评测器：每个评测器两次的均值对比条形图；③ 按样本：同 id 逐行 diff，得分下降的样本标红置顶 |
| API | `GET /api/eval/runs/diff?base={id}&target={id}`，纯计算无落盘 |
| 页面 | /eval 结果区新增「与历史对比」按钮，选择基线 run 后跳转 diff 视图 |
| 统计口径 | 与 OpenJudge Analyzer 对齐：每评测器输出 accuracy（通过率）、平均分、以及样本级「翻转矩阵」（通过→失败 / 失败→通过），定位回归具体来自哪些样本 |

### 6.3 模块三：数据集管理（在线编辑 + 聊天记录沉淀）

**定位**：解决数据集维护靠手工改 JSONL 的痛点，并把真实聊天中发现的坏 case 一键变成回归用例。

**设计**：

| 项 | 方案 |
|---|---|
| 列表/详情 | /eval 新增「数据集」Tab：列表（名称/条数/最近修改）、点击进入条目编辑器（表格化展示 id/query/expected/graders，表单编辑非裸文本） |
| 新建/编辑/删除 | 表单校验（graders 必须存在于已注册清单；cypher 类型保存前走只读红线校验）；写回 JSONL；删除移入回收站文件（`data/eval/datasets/.trash/`）而非物理删除 |
| 聊天记录沉淀 | 新增 `GET /api/eval/samples/from-chat?sessionId=&messageId=`：拉取指定会话的 query 与最终 answer，预填到「新建样本」表单（expected 留空人工补）；chat 页历史消息旁加「存为评测样本」入口（仅 ADMIN 可见） |
| 生成辅助 | 「从图谱生成」按钮：给定 Cypher 模板批量生成 contains/number 型样本（如遍历 Era 节点自动出「XX 朝代有多少件文物」题 + 期望值），快速扩充数据集规模 |

### 6.4 模块四：驾驶舱集成

**定位**：让评测指标进入日常视野，与现有 /dashboard（用户驾驶舱）并列形成质量视角。

**设计**：

| 项 | 方案 |
|---|---|
| 新端点 | `GET /api/dashboard/eval-stats`：最近 N 次运行的 pass_rate 趋势、各评测器均值、错误率；聚合逻辑放 EvalService，DashboardController 仅暴露 |
| 页面 | /dashboard 新增「评测质量」卡片（复用现有卡片样式）：趋势折线 + 最近一次运行的指标摘要；点击卡片跳 /eval |
| 隔离 | 不动 DashboardService 现有用户统计逻辑，避免与并行开发冲突（历史教训：并行会话互相覆盖） |

### 6.5 三期验收标准

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| C1 | Agentic 评测运行 | demo 样本勾选 agentic_groundedness 运行 | 每样本得 0-1 分 + reason + evidence 数组；evidence 含断言/Cypher/返回摘要/证实标记 |
| C2 | 证据可核查 | 任取一条 evidence 的 Cypher 手工在 Neo4j 执行 | 返回与 evidence 摘要一致 |
| C3 | 编造检测 | 构造一条含错误事实的回答（如把北宋说成元代）直接评测（支持传既有 answer 离线评测） | 该断言 evidence 标记「未证实」，score 相应下降 |
| C4 | 取证上限 | 单样本回答含大量断言 | graph_query 调用 ≤ 上限即截断，reason 注明，任务正常完成不超时 |
| C5 | 回归对比 | 同一数据集先后跑两次（其间改坏一个 expected） | diff 页总体指标出现 ▼ 红色下降；样本级 diff 精确标出被改坏的那条 |
| C6 | 数据集哈希校验 | 对比两个不同数据集的 run | 提示「数据集已变更，对比仅供参考」 |
| C7 | 数据集在线编辑 | /eval 数据集 Tab 修改一条样本保存 | 写回 JSONL；重新运行该数据集生效 |
| C8 | 表单校验 | 编辑时填不存在的 grader 名 | 保存被拒并提示 |
| C9 | 聊天沉淀 | chat 页历史消息点「存为评测样本」 | 跳转新建样本表单，query/answer 预填，补 expected 保存成功 |
| C10 | 从图谱生成 | 点「从图谱生成」（选 Era 维度） | 批量生成 number 型样本，期望值与图谱实际一致（抽样 3 条 Cypher 核验） |
| C11 | 驾驶舱卡片 | 打开 /dashboard | 「评测质量」卡片出现，趋势与最近 run 指标正确，点击跳 /eval |
| C12 | 并行安全 | 检查 git diff | DashboardService/UserController 等既有文件零改动（仅新增 Controller 方法与页面区块） |

### 6.6 三期风险

| 风险 | 缓解 |
|---|---|
| Agentic 评测成本最高（每断言一次查询） | 取证次数上限 + 断言数量截断；仅对重点样本启用 |
| judge agent 工具误用（评测写操作） | 评测 agent 只注册只读工具，工具层与服务端红线双保险（同现有 graph_query 通道） |
| 数据集在线编辑破坏文件格式 | 保存前 JSON 序列化校验；回收站机制可恢复 |
| 驾驶舱并行开发冲突 | 模块四严格「只增不改」，最后实施 |

---

## 7. 风险与约束

| 风险 | 缓解 |
|---|---|
| LLM judge 成本与时长（样本×评测器次调用） | 默认集控制在 9 个 LLM 评测器以内；规则评测器零成本；demo 数据集仅 10 条 |
| judge 输出不稳定 | 结构化 JSON 解析 + 失败重试 1 次；可选 run=2 取平均 |
| prompt 注入（样本 query 混入指令） | judge 模板固定、样本内容放明确分隔的"待评估内容"区块；解析仅认 JSON schema |
| agentscope-harness Windows 缺陷（已知 Hard Constraints） | 评测驱动复用现有 AgentChatService 会话链路，不新增子进程路径 |
| 评测写操作破坏图谱 | 全链路只读：cypher_match 走红线正则 + GraphQueryService；评测任务不调用任何写接口 |
| 服务器重启丢任务进度 | 与备份任务同策略：任务态在内存，结果文件落盘后可从 /api/eval/runs 重新读取历史 |

---

## 8. 实施顺序

1. **一期**（框架 + 5 规则评测器 + API + 页面 + demo 数据集）→ 按 4.2 验收（A1-A14）；
2. **二期选型确认**：对 5.2 清单勾选（默认集见 5.3，可增删）；
3. **二期**（LlmJudgeGrader + 选定评测器 + 页面增强）→ 按 5.4 验收（B1-B10）；
4. **三期立项确认**：6.1-6.4 四个模块按需勾选（推荐优先级：回归对比 > AgenticGrader > 数据集管理 > 驾驶舱集成）；
5. **三期**（按勾选模块实施）→ 按 6.5 验收（C1-C12）。
