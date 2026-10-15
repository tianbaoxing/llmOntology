# llmOntology 评测器设计方案

> 版本：v3（2026-10-15，按实现现状修订）
> 参照项目：`C:\me\kaiyuan\agenttrace\OpenJudge-main`（Python 版 LLM/Agent 评测框架）
> 实现状态：一期（规则评测）、二期（LLM 评测）、三期模块一/二/三（Agentic 评测 / Run Diff / 数据集管理）均已交付；三期模块四（驾驶舱集成）未实施。另交付两个计划外通道：人工评测（human_review）与自定义评测器（custom-graders）。
> 存储架构（P6 起已 DB 化）：数据集、评测结果、自定义评测器均存 MySQL（`dataset` / `dataset_sample` / `eval_run` / `eval_run_sample` / `custom_grader` 五表，V10-V12 迁移脚本），原 JSONL/JSON 文件由 Migrator 迁入后归档 `.migrated`。本文已按 DB 化后的实际实现修订。

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

### 3.1 包结构（实际实现）

```
com.conca.ontology.eval
├── Grader.java            # 接口：String name(); String description();
│                          #        String type()（rule|llm|human|agentic，缺省 rule）;
│                          #        GraderScore evaluate(GraderInput input);
├── JudgeClient.java       # LLM judge 通道：经 JudgeAgentHolder 的纯 LLM HarnessAgent 调用
│                          # （无工具/技能/子代理），失败重试 1 次（3s 退避）
├── JudgeAgentHolder.java  # judge 专用 agent 装配（会话留痕落 agent_state_judge 表）
├── AgenticJudgeHolder.java# Agentic 取证评测 agent 装配（带只读图谱工具，maxIters=5）
├── EvalProxyTool.java     # 评测专用只读工具（/api/llm/ 只读平面代理，取证次数上限强制）
├── graders/
│   ├── rule/match/        # string_exact、string_contains、string_regex、number_accuracy、
│   │                      # format_json、syntax_checker
│   ├── rule/text/         # text_similarity、response_repetition
│   ├── rule/tool/         # tool_call_success、action_loop、observation_information_gain
│   ├── llm/               # LlmJudgeGrader 基类（模板渲染 → JudgeClient → 解析 {score, reason}）
│   ├── llm/answer/        # 8 个回答质量类（correctness/hallucination/relevance/completeness/...）
│   ├── llm/tool/          # 5 个工具调用类（tool_call_accuracy/tool_selection/trajectory_accuracy/...）
│   ├── llm/dialogue/      # 4 个多轮对话类（context_memory/anaphora_resolution/...）
│   ├── human/             # HumanReviewGrader（human_review，占位分待人工补评）
│   ├── custom/            # CustomLlmGrader（自定义 LLM 评测器，criteria/template 两种模式）
│   └── agent/             # AgenticGroundednessGrader（agentic_groundedness，judge 自主取证判分）
├── model/                 # EvalSample、AgentRecord、GraderInput、GraderScore、EvalTask、
│                          # CustomGraderDefinition
├── entity/ + mapper/      # MyBatis-Plus 存储层（五张 MySQL 表）
├── dto/                   # request（run/sample/custom-grader 等）/ result（FromChatSampleResult）
├── api/EvalController.java# REST 端点（23 个，见 3.4）
└── service/               # EvalRunService（任务编排+判分+落库）、EvalDatasetService（数据集 CRUD）、
                           # EvalCustomGraderService、EvalGraderRegistry、CustomGraderStore
```

### 3.2 核心数据流

```
MySQL dataset + dataset_sample 表（数据集，每非空行一样本）
        │  逐条（loadSamples 按 id 升序 = 原文件行序）
        ▼
EvalRunService ──► 内部调用 HarnessAgent.streamEvents 收集事件流
        │        （复用现有 agent 装配；不走前端 SSE，服务端直接收集；
        │          多轮样本逐轮驱动，轨迹含每轮 query/answer）
        ▼
AgentRecord：最终 answer + 完整轨迹（工具调用名称/入参/返回摘要/轮次/tokens/时长）
        │
        ▼
Grader 列表逐个判分（规则类本地判定；LLM 类走 JudgeClient；
        │            human 类占位待补；agentic 类 judge 自主取证）
        ▼
MySQL eval_run + eval_run_sample 表（start 时 INSERT running 行，
        │  进度节流 UPDATE，完成时样本批量 INSERT + 任务行 UPDATE 单事务；
        │  含 human_review 时终态 awaiting_human，人工补评后翻 success）
        ▼
/eval 页面轮询展示；下载按原结果 JSON 结构从库重建导出
```

### 3.3 数据集格式（MySQL 存储，语义对齐 OpenJudge 扁平数据集）

数据集存 `dataset`（每数据集一行，name = 原 .jsonl 文件名）+ `dataset_sample`（每非空行一样本）两表。样本字段以文件行保真为原则：原行无此键 → 列 NULL，回读省略键。

```json
{"id":"q001","query":"定窑白釉莲纹大碗是什么朝代的？",
 "reference_response":["北宋"]}
{"id":"q002","query":"图谱里一共有多少件唐代文物？",
 "reference_response":223,
 "tolerance":0}
{"id":"q003","query":"介绍一下青花釉里红云龙纹天球瓶",
 "reference_response":["清","乾隆","青花"],
 "rubric":"答案应包含朝代、釉彩、器型信息，不得编造图谱中不存在的细节"}
{"id":"q004","query":"定窑白釉莲纹大碗是什么朝代的？",
 "response":"定窑白釉莲纹大碗是北宋时期的器物，由定窑烧制。",
 "reference_response":["北宋","定窑"]}
{"id":"q005","turns":[{"query":"介绍一下青花釉里红云龙纹天球瓶"},
                      {"query":"它是什么朝代的？"},
                      {"query":"那个朝代还有哪些代表性瓷器？"}]}
{"id":"q006","query":"定窑白釉莲纹大碗是哪个朝代的？",
 "skills":["ontology-query"],"tools":["graph_query"]}
```

- 核心字段对齐 OpenJudge：`query`（问题）/ `response`（预置回答，可选）/ `reference_response`（参考答案：字符串/数字/数组，各评测器自行解释）
- `response` 预置时跳过 agent 直接判分（OpenJudge 纯判分模式）；未预置时驱动 agent 现场作答
- `tolerance`：数字比对容差（可选，缺省 0）
- `rubric`：LLM 评测器的补充评分细则
- `turns`：多轮对话脚本（至少 2 轮，每轮 `{query, response?}`；轮内预置 response 则该轮跳过 agent）——多轮评测器（context_memory 等）的数据基础
- `skills` / `tools` / `mcps`：期望调用的技能/工具清单（喂给工具选择类评测器的 expected_skills/expected_tools/expected_mcps）
- 评测器勾选不在样本级：创建评测任务时通过 `POST /api/eval/runs` 的 graders 参数指定（对齐 OpenJudge grader_configs 的运行级等价物）
- 字段映射（mapper，对齐 OpenJudge grader_configs.mapper）：评测器经 GraderInput 参数包按名取值（query/response/reference_response/tolerance/rubric/judge_runs/expected_*），不感知存储 schema
- v2 的 `expected` 嵌套结构（type/value/cypher）已废弃；`cypher_match`（图谱事实核对）由三期 Agentic 评测器承接（见 7.1）

### 3.4 REST API（实际 23 个端点，对齐现有 URL 风格与 DTO 规范）

| 分组 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 数据集 | GET | `/api/eval/datasets` | 数据集列表（查 dataset 表，返回名称+样本数） |
| | GET | `/api/eval/datasets/{name}/file` | 数据集内容预览（前 N 行，坏行标记 valid=false） |
| | POST | `/api/eval/datasets` | 新建数据集（同名 409） |
| | GET | `/api/eval/datasets/{name}/samples` | 样本分页列表（seq = 行序，供编辑/删除定位；page 越界自动收敛） |
| | POST | `/api/eval/datasets/{name}/samples` | 新增样本（query 必填、id 唯一；多轮至少 2 轮） |
| | PUT | `/api/eval/datasets/{name}/samples/{seq}` | 编辑样本（整行覆写，支持清空回写） |
| | DELETE | `/api/eval/datasets/{name}/samples/{seq}` | 删除样本（行删，后行自然前移） |
| 聊天沉淀 | GET | `/api/eval/samples/fromchat` | 提取指定会话第 msgIndex 条 AI 回答及对应提问，预填评测样本表单（不落盘；仅自己创建的会话） |
| 评测器 | GET | `/api/eval/graders` | 已注册评测器清单（名称/描述/类型 rule\|llm\|human\|agentic，含自定义） |
| 自定义评测器 | GET | `/api/eval/custom-graders` | 自定义 LLM 评测器清单（附 mode/更新时间，不含 prompt 控载荷） |
| | GET | `/api/eval/custom-graders/{name}` | 详情（含 prompt 全文，编辑回填用） |
| | POST | `/api/eval/custom-graders` | 创建（mode=criteria/template；与内置重名 400） |
| | PUT | `/api/eval/custom-graders/{name}` | 更新（热替换注册表实例，同次运行即生效；名称不可改） |
| | DELETE | `/api/eval/custom-graders/{name}` | 删除（被数据集引用时 409 并列出引用位置） |
| | POST | `/api/eval/custom-graders/{name}/test` | 试跑（单次判分即时返回，不落盘不产生任务） |
| 运行 | POST | `/api/eval/runs` | 创建评测任务（body：dataset + graders 清单 + judgeRuns 1-3；运行中 409；agent/judge 未装配 409） |
| | GET | `/api/eval/runs` | 历史任务列表（查 eval_run 表按 run_id 倒序，含各任务汇总指标） |
| | GET | `/api/eval/runs/diff` | 回归对比（base/target 两次 run 三级对比 + 翻转矩阵，见 7.2） |
| | GET | `/api/eval/runs/{id}` | 任务进度/详情（运行中实时进度；完成后完整结果） |
| | POST | `/api/eval/runs/{id}/humanscores` | 人工评分（human_review 任务逐样本 1-5 星打分，可跳过，见第 6 章） |
| | GET | `/api/eval/runs/{id}/file` | 下载完整结果 JSON（从库重建导出，保持 attachment 文件名） |
| | DELETE | `/api/eval/runs/{id}` | 删除评测记录（两表事务删除；仅 ADMIN） |

权限：登录用户（AuthInterceptor 默认拦截）；删除评测记录需 ADMIN。不进 `/api/llm/**` 冻结平面。Controller 进 admin 分组，@Tag/@Operation/@Schema 注解齐全，Swagger 可见。

### 3.5 页面（eval.html，复用 common.css/common.js）

导航栏「评测中心」，页面五 Tab + 三个功能视图：
1. **运行评测**：数据集下拉 + 评测器勾选（按 规则/LLM/人工/Agentic 分组显示）+ judgeRuns 次数 + 运行按钮；历史任务列表（含僵尸任务 interrupted 标记、删除入口）；结果明细表（每行一个样本：query / answer 摘要 / 各评测器得分徽章 / reason 点击展开 / 轨迹折叠查看）；
2. **评测报告**：完成后可视化报告（综合总览指标卡、Tokens 成本分析、错误分布、综合得分分布、人工评分分布、评测器维度分析、单样本维度分析、耗时分析）；
3. **数据集管理**：列表 + 样本表格化编辑（单轮/多轮切换、reference 类型化输入 string|number|array）+ 新建数据集 + 聊天记录沉淀入口；
4. **自定义评测器**：清单 + 创建/编辑（criteria/template 两模式）+ 试跑；
5. **Skills 管理**：技能分析管理（调用 `/api/skills` 系列，属技能体系功能，挂在评测中心下）。

另含**人工评分**视图（awaiting_human 任务的逐样本打分面板）与**回归对比（Run Diff）**视图（选基线/目标 run 后展示三级 diff，回归最严重置顶）。

---

## 4. 一期：规则评测通道（已实现）

### 4.1 一期范围（实际交付）

| 模块 | 内容 |
|---|---|
| 框架 | Grader 接口、GraderScore、GraderInput、EvalTask（单任务 409、进度节流落库）、EvalRunService 异步执行 |
| 规则评测器（11 个） | match 类：`string_exact`（整串等值）、`string_contains`（关键词全包含，缺一按比例扣分）、`string_regex`（正则匹配）、`number_accuracy`（文本数字按序逐位比对+容差，对齐 OpenJudge NumberAccuracyGrader 语义）、`format_json`（校验回答为合法 JSON）、`syntax_checker`（语法检查）；text 类：`text_similarity`（中文 n-gram 相似度）、`response_repetition`（n-gram 重复率）；tool 类：`tool_call_success`（检测工具返回是否 [参数错误]/[服务错误] 前缀）、`action_loop`（连续相同工具+相同参数判循环）、`observation_information_gain`（重复观察扣分）；`cypher_match` 未单独实现（由三期 Agentic 评测器承接） |
| 轨迹采集 | AgentRecord：answer + 工具调用序列（名称/入参/返回摘要，超 500 字符截断）+ 轮次 + tokens + 时长，随结果落库（为 Agent 类评测器供料） |
| API | 见 3.4（随二期/三期扩展至 23 个端点） |
| 页面 | eval.html 五 Tab（见 3.5） |
| 示例数据 | 原内置 demo.jsonl 已随数据集维护演替；当前库内为 `llm_and_guize.jsonl`、`llm_anaphora_resolution.jsonl`、`llm_context_memory.jsonl` 三个数据集（规则+LLM 混合、多轮对话专用），存量已由 Migrator 迁入 MySQL |

### 4.2 一期验收标准（历史记录，已通过；A3/A11 按 P6 DB 化后的现状注明）

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| A1 | 编译 | `mvnw compile` | BUILD SUCCESS，无新警告 |
| A2 | 页面入口 | 登录后看导航栏 | 出现「评测中心」，点击进入 /eval，三区块可见 |
| A3 | 数据集接口 | `GET /api/eval/datasets` | 返回数据集清单与条数（原 demo.jsonl 12 条；现为库内 3 个数据集） |
| A4 | 评测器清单 | `GET /api/eval/graders` | 返回规则评测器，type=rule |
| A5 | 运行任务 | 页面勾选数据集 + 评测器，点运行 | 创建成功返回任务 id；进度轮询从 0% 单调推进到 100%；无卡死 |
| A6 | 结果明细 | 任务完成后看结果表 | 每行每评测器有分数与 reason；answer 摘要与轨迹可展开 |
| A7 | 判分正确性抽查 | 取 contains 样本 | 期望命中的样本 contains=1.0；人为构造答错的期望值时得分<1.0 |
| A8 | 纯判分模式 | 预置 response 样本 | 跳过 agent 直接判分，结果行 responseSource=preset；全部预置的数据集无 OPENROUTER_API_KEY 也可运行 |
| A9 | 异常隔离 | 掺 1 条坏样本 | 该行显示 error，整批正常完成，指标中 error 数=1 |
| A10 | 并发互斥 | 任务运行中再点运行 | 返回 409，提示已有任务 |
| A11 | 结果落盘 | ~~查看 `data/eval/results/`~~（P6 起落库） | 结果写 `eval_run` + `eval_run_sample` 表，页面与下载内容一致；原「生成 eval-*.json 文件」语义由 exportResult 从库重建导出承接 |
| A12 | 权限 | 未登录直接 `GET /api/eval/runs` | 401 |
| A13 | Swagger | 打开 /swagger-ui/index.html admin 分组 | 出现 eval 标签端点，含 @Operation 描述 |

---

## 5. 二期：LLM 评测通道 + 内置评测器移植（已实现）

### 5.1 二期框架内容（实际交付）

| 模块 | 内容 |
|---|---|
| LlmJudgeGrader 基类 | 模板渲染（query/answer/context/rubric）→ JudgeClient 判分 → 解析结构化 JSON `{score, reason}`（容忍 markdown 围栏，截取首个 { 到末个 }；分数越界收敛 [1,5]）；失败/不可解析重试 1 次（3s 退避），仍失败记 GraderError，token 按全部尝试累计 |
| judge 通道 | JudgeClient 经 JudgeAgentHolder 持有的**judge 专用 HarnessAgent**（纯 LLM 管道：无工具/技能/子代理）调用，每次判分独立 sessionId（userId=eval-judge），会话留痕落 `agent_state_judge` 表；配置 `app.eval.judge.*`（base-url/api-key/model 缺省回落 `app.agent.*`） |
| 分数归一化 | LLM 评测器原始 1-5 分制，统一换算为 0-1（(score-1)/4），页面同时显示原始分 |
| context 注入 | 对 hallucination/groundedness 类评测器，把该样本 agent 的图谱查询返回（轨迹中提取）作为上下文喂给 judge，实现「以图谱为证据」的事实核查 |
| 多次评测策略 | judgeRuns 运行参数 1-3（`POST /runs` 传参或 `app.eval.judge.runs` 缺省），>1 时多次判分取平均降噪（对齐 OpenJudge AverageEvaluationStrategy），metadata 记录多次原始分 |
| 成本统计 | summary 累计 judge model/calls/promptTokens/completionTokens；样本级 durationMs/tokens |
| 页面增强 | 评测器勾选分组；评测报告 Tab（Tokens 成本分析等，见 3.5） |

### 5.2 OpenJudge 65 个内置评测器完整清单（选型参考）

> 标注说明：**适用度** ★★★=强烈推荐移植 / ★★=可选 / ★=边缘 / ☆=不适用（附原因）。
> 「实现」列：规则=Java 本地算法（免费、毫秒级）；LLM=judge 模型调用；Agent=LLM+轨迹数据。
> 本清单为二期选型时的参考分析；**实际落地清单见 5.3**（部分 ★★ 项如 tool_call_success 规则版、action_loop、observation_information_gain、text_similarity、response_repetition 规则版、多轮对话 4 件套等也已在实现中交付）。

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

### 5.3 实际实现清单（内置 30 个 + 自定义通道）

二期未按「默认集 12 个」裁剪，而是全量交付了选型清单中的推荐项与可选项，另加人工评测通道（原「可再选」的多轮对话 4 件套、规则补强项均已落地）：

| 通道 | 评测器（name()） | 数量 |
|---|---|---|
| 规则（rule） | string_exact、string_contains、string_regex、number_accuracy、format_json、syntax_checker、text_similarity、response_repetition、tool_call_success、action_loop、observation_information_gain | 11 |
| LLM 回答类 | llm_correctness、llm_hallucination、llm_relevance、llm_response_completeness、llm_response_helpfulness、llm_instruction_following、llm_harmfulness、llm_reasoning_groundedness | 8 |
| LLM 工具类 | llm_tool_call_accuracy、llm_tool_selection、llm_tool_parameter_check、llm_tool_usage_efficiency、llm_trajectory_accuracy | 5 |
| LLM 多轮对话类 | llm_context_memory、llm_anaphora_resolution、llm_instruction_clarification、llm_response_repetition | 4 |
| 人工（human） | human_review（占位分待人工补评，见第 6 章） | 1 |
| Agentic（三期） | agentic_groundedness（judge 自主取证判分，见 7.1） | 1 |
| 自定义 | CustomLlmGrader 实例（用户经 custom-graders API 创建，数量不限，见第 6 章） | - |

**未做**（与选型结论一致）：代码/数学（50-57）、多模态（58-60）、反思/计划/记忆类（28-35）、技能包类（61-65）、SimilarityGrader 的 BLEU/ROUGE 全家（text_similarity 只做中文可用子集）。

### 5.4 二期验收标准（历史记录，已通过）

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| B1 | 评测器清单 | `GET /api/eval/graders` | LLM 类评测器出现，type=llm，含 description |
| B2 | LLM 判分运行 | demo 数据集勾选 correctness + hallucination 运行 | 每样本两个 0-1 分（附原始 1-5 分）+ 中文 reason；抽查 3 条 reason 与答案实际质量相符 |
| B3 | 证据注入 | 对 q003（带图谱查询轨迹的样本）看 hallucination 的 reason | reason 中能引用图谱查询结果作为依据（metadata 含 context 截断串） |
| B4 | 轨迹类评测 | 勾选 trajectory_accuracy 运行 | 得分反映该次问答全程质量；工具调用序列在 reason 中被提及 |
| B5 | 失败重试与降级 | 临时改错 API key 运行 | judge 重试 1 次后该评测器记 error，其余规则评测器得分不受影响，整批完成 |
| B6 | 成本统计 | 任务完成后看指标卡与结果（eval_run 行 summary） | 显示 judge 总 tokens、调用次数、模型名、总时长 |
| B7 | 未配置 key | 删 .env 重启后创建含 LLM 评测器的任务 | 409 + 文案「AI 服务未配置（缺少 OPENROUTER_API_KEY）」（对齐现有约定） |
| B8 | 平均策略开关 | 运行参数 judgeRuns=2（或 `app.eval.judge.runs` 配置） | 同样本两次 judge 取平均，结果 metadata 记录多次原始分 |
| B9 | 归一化正确性 | 任一 LLM 评测器原始分 5 → 页面 1.0；原始 3 → 0.5 | 换算无误，明细同时展示两种分制 |
| B10 | 页面分组 | 评测器勾选区 | 「规则评测」「LLM 评测」两组分区展示，结果表得分徽章带来源 tag |

---

## 6. 人工评测与自定义评测器通道（计划外新增，已实现）

实现期间在 OpenJudge 三类评测器（Function/LLM/Agentic）之外补充交付了两个通道，用于覆盖 LLM judge 的可信度校准与业务定制判分需求。

### 6.1 人工评测通道（human_review）

**定位**：LLM judge 分数只能相对比较，缺少绝对基准；人工抽评作为「金标准」锚点，同时服务开放式回答（介绍类问题）这类难以规则判分的场景。

| 项 | 实现 |
|---|---|
| 评测器 | HumanReviewGrader，name=`human_review`，type=human；evaluate 时返回 pending 占位分（不产生 judge 调用） |
| 任务状态 | 含 human_review 的任务跑完置 `awaiting_human`（非 success）；summary 增加 humanPending/humanScored/humanSkipped 三计数 |
| 评分入口 | `POST /api/eval/runs/{id}/humanscores`：逐样本 1-5 星（rawScore），归一化 (rawScore-1)/4；可 skip 跳过（score=null，计 skipped）；可附 comment |
| 覆盖与留痕 | 可重复提交覆盖修改；metadata.human 留 grader（评分人 userId）/scoredAt/comment 留痕 |
| 状态翻转 | 每次评分后重算 summary，humanPending 归零时任务翻 success；样本行 scores 就地改写 + 任务行汇总更新（单事务） |
| 页面 | /eval 人工评分视图：awaiting_human 任务逐样本打分面板；评测报告含人工评分分布 |

### 6.2 自定义评测器通道（custom-graders）

**定位**：把「评分标准」从代码里解放出来——用户用自然语言写评分细则即可注册一个 LLM 评测器，无需改代码重启；适合业务定制判分（如「回答须引用藏品出土信息」）。

| 项 | 实现 |
|---|---|
| 存储 | `custom_grader` 表（name 主键；description/mode/prompt/created_at/updated_at 拆列，V10 迁移） |
| 两种模式 | mode=criteria：prompt 仅写评分标准，样本内容由框架隔离拼接（防注入）；mode=template：prompt 为完整模板，占位符 `{{query}}`/`{{answer}}`/`{{reference}}`/`{{context}}`/`{{rubric}}` |
| 运行时注册 | EvalGraderRegistry 启动时（ApplicationReadyEvent，确保迁移 Runner 已跑完）从表加载注册 CustomLlmGrader；增改走 API 热替换，同一次运行即生效 |
| 防护 | 与内置评测器重名 400；删除前查数据集引用（全库搜索 `"名称"` 精确匹配），有引用 409 并列出「数据集名 第N行」引用位置，不静默破坏 |
| 试跑 | `POST /custom-graders/{name}/test`：单次判分即时返回 score/reason（不落盘不产生任务），先验证提示词有效再入库 |
| Diff 标注 | Run Diff 对比时检测两次 run 的自定义评测器 promptHash，变更则标注「定义已变更（旧 hash → 新 hash），对比仅供参考」 |
| 页面 | /eval 自定义评测器 Tab：清单 + 创建/编辑（两模式切换）+ 试跑 |

### 6.3 验收要点（已通过）

| # | 验收项 | 预期 |
|---|---|---|
| D1 | human_review 任务运行 | 终态 awaiting_human，humanPending=样本数；人工评分后递减，评完翻 success |
| D2 | 人工评分留痕 | 重复提交覆盖分数，metadata 保留评分人/时间/评论 |
| D3 | 自定义评测器创建/试跑/运行 | criteria 与 template 两模式均可判分；试跑即时返回；勾选运行结果落库可 Diff |
| D4 | 删除保护 | 被数据集引用的自定义评测器删除返回 409 并列出引用位置 |

---

## 7. 三期：Agentic 评测 + 工程化闭环（模块一/二/三已实现，模块四未实施）

三期将评测系统从「打分工具」升级为「持续质量保障闭环」：引入 OpenJudge 最高阶的 AgenticGrader（工具增强评测），并补齐数据集管理、回归对比、驾驶舱集成等工程化能力。四个模块相互独立，可拆开单独立项。当前模块一/二/三已交付，模块四未实施。

### 7.1 模块一：AgenticGrader（已实现，agentic_groundedness）

**定位**：对应 OpenJudge 的 AgenticGrader——评测器本身就是一个带工具的 agent，自主决定查什么、查几次，拿到证据后再打分。与二期 LLM 评测器的本质区别：二期 judge 只看「喂给它的上下文」（静态），Agentic judge 自己去图谱取证（动态），能发现「回答正确但查证路径不存在」的深层问题。

**设计**：

| 项 | 实现 |
|---|---|
| 装配 | AgenticJudgeHolder 装配**评测专用 agent**（刻意不注册为 Spring bean，避免与主问答 agent 形成 HarnessAgent 双候选）：同一 OpenRouter 模型通道，仅注册 EvalProxyTool 只读工具（/api/llm/ 只读平面代理，含端点速查内置 sysPrompt）；禁用 shell/文件/记忆/技能/子代理；maxIters=5（取证只需短链路）；会话留痕落 `agent_state_agentic_judge` 表（userId=eval-agentic，可回查每次取证完整轨迹） |
| 输入 | query（原问题）+ answer（被评测的回答）+ rubric（评分细则，可选） |
| 评测流程 | judge agent 收到「拆解事实断言、逐条用工具向图谱取证」任务 → 自主调只读接口核对每个断言（朝代/窑口/数量/出土地） → 输出结构化 JSON `{score, reason, evidence[]}` |
| 证据链 | 每条 evidence = {claim 断言原文, cypher 执行语句, summary 图谱返回摘要, confirmed 是否证实}，随 metadata 落库；queries 数组留痕实际取证调用（path/body/返回摘要）——**每分都带可复查的证据** |
| 确定性重算 | agent 自报 score 仅作参考；evidence 非空时按 confirmed/total 确定性重算（每分可由证据复查） |
| 成本控制 | 单样本取证查询上限 `app.eval.agent.max-queries`（缺省 8），EvalProxyTool 强制截断（确定性文案），reason 注明「取证截断：剩余断言按未证实计」；调用失败重试 1 次（3s 退避） |
| 配置 | `app.eval.agent.*`（base-url/api-key/model 缺省回落 `app.agent.*`；max-iters=5、max-queries=8、max-output-tokens=1200、timeout-seconds=60、workspace-dir）；未配置时 grader 前置校验 + 任务创建 409 同文案拦截 |

**判分语义**：score ∈ [0,1]，= 证实断言数 / 总断言数（agent 自行拆解断言，服务端按 evidence 重算）；reason 前缀「证据 N/M 条证实」。

### 7.2 模块二：回归对比（Run Diff，已实现）

**定位**：换模型、改提示词、改技能后，量化「变好还是变坏」。对应 OpenJudge 的 Analyzer 思路（其 validation/ 下有 accuracy/f1/precision/recall 等分析器）。

**设计**：

| 项 | 实现 |
|---|---|
| 数据结构 | eval_run 行记录 dataset 名 + dataset_hash（样本序列化 SHA-256，内容变更可检测）+ 评测器清单 + 时间；对比时校验两次 run 的哈希，输出 match/mismatch/unknown（老 run 无哈希）三态，mismatch 提示「数据集已变更，对比仅供参考」 |
| 对比维度 | ① overall 总体：passRate/avgScore/errorCount/promptTokens/completionTokens/judgeTokens 及 delta；② byGrader 按评测器：两次均值对比 + 翻转矩阵（passToFail/failToPass）+ commonSamples；③ bySample 按样本：同 id 逐行 diff，得分下降最严重置顶，默认仅返回变化样本（all=true 返回全部），另标新增/删除样本（addedIds/removedIds） |
| 自定义评测器标注 | 检测两次 run 的自定义评测器 promptHash，变更则标注「定义已变更（旧 hash → 新 hash），对比仅供参考」；仅单侧有的评测器标 onlyIn |
| API | `GET /api/eval/runs/diff?base={id}&target={id}&all={bool}`，纯计算无落盘；base 与 target 不能相同 |
| 页面 | /eval 回归对比视图：选择基线/目标 run 后展示三级 diff |
| 统计口径 | pass 判定同汇总口径：样本均分 ≥ pass-threshold 且无 error；翻转矩阵定位回归具体来自哪些样本 |

### 7.3 模块三：数据集管理（已实现，在线编辑 + 聊天记录沉淀）

**定位**：解决数据集维护靠手工改 JSONL 的痛点，并把真实聊天中发现的坏 case 一键变成回归用例。

**设计**：

| 项 | 实现 |
|---|---|
| 列表/详情 | /eval「数据集管理」Tab：列表（名称/条数）+ 样本分页表格（seq/raw/id/sampleType 单轮\|多轮/query/response/referenceResponse/tolerance/skills/tools/mcps/valid，表单编辑非裸文本） |
| 新建/编辑/删除 | 新建数据集（`^[A-Za-z0-9._-]+\.jsonl$` 命名校验）；样本增/改/删均为 dataset_sample 行级操作（改整行覆写支持清空回写，删行后自然前移）；单轮/多轮切换（多轮至少 2 轮、每轮 query 必填），reference 类型化输入 string\|number\|array；id 唯一预检（含 line-{seq} 伪 id）409 |
| 聊天记录沉淀 | `GET /api/eval/samples/fromchat?sessionId=&msgIndex=`：拉取指定会话第 msgIndex 条 AI 回答及其对应提问，预填新建样本表单（reference 由人工补齐后走新增样本接口；不落盘，仅自己创建的会话） |
| 生成辅助 | 「从图谱生成」批量造题**未实现**（可后续按需补充） |
| 回收站 | 未实现——DB 化后删除即删行，无 .trash 归档（靠 MySQL 备份恢复） |

### 7.4 模块四：驾驶舱集成（未实施）

**定位**：让评测指标进入日常视野，与现有 /dashboard（用户驾驶舱）并列形成质量视角。

**设计**（如后续立项按此实施）：

| 项 | 方案 |
|---|---|
| 新端点 | `GET /api/dashboard/eval-stats`：最近 N 次运行的 pass_rate 趋势、各评测器均值、错误率；聚合逻辑放 EvalRunService（查 eval_run 表），DashboardController 仅暴露 |
| 页面 | /dashboard 新增「评测质量」卡片（复用现有卡片样式）：趋势折线 + 最近一次运行的指标摘要；点击卡片跳 /eval |
| 隔离 | 不动 DashboardService 现有用户统计逻辑，避免与并行开发冲突（历史教训：并行会话互相覆盖） |

### 7.5 三期验收标准（C1-C10 历史记录已通过；C11-C12 未实施）

| # | 验收项 | 操作 | 预期 | 状态 |
|---|---|---|---|---|
| C1 | Agentic 评测运行 | 勾选 agentic_groundedness 运行 | 每样本得 0-1 分 + reason + evidence 数组；evidence 含 claim/cypher/summary/confirmed | 已通过 |
| C2 | 证据可核查 | 任取一条 evidence 的 Cypher 手工在 Neo4j 执行 | 返回与 evidence 摘要一致 | 已通过 |
| C3 | 编造检测 | 构造含错误事实的回答直接评测（预置 response 离线评测） | 该断言 evidence 标记未证实，score 相应下降（服务端按 confirmed/total 重算） | 已通过 |
| C4 | 取证上限 | 单样本回答含大量断言 | 取证查询 ≤ max-queries 即截断，reason 注明，任务正常完成不超时 | 已通过 |
| C5 | 回归对比 | 同一数据集先后跑两次（其间改坏一个参考答案） | diff 总体指标出现下降；样本级 diff 精确标出被改坏的那条 | 已通过 |
| C6 | 数据集哈希校验 | 对比两个不同数据集的 run | hashMatch=mismatch，提示「数据集已变更，对比仅供参考」 | 已通过 |
| C7 | 数据集在线编辑 | /eval 数据集管理 Tab 修改一条样本保存 | 落 dataset_sample 行；重新运行该数据集生效 | 已通过 |
| C8 | 表单校验 | 编辑时填不存在的 grader 名 | 运行任务时 400「未知评测器」（评测器勾选为运行级参数） | 已通过 |
| C9 | 聊天沉淀 | 数据集管理 Tab 聊天沉淀入口（sessionId + msgIndex） | query/answer 预填，补 reference 保存成功 | 已通过 |
| C10 | 从图谱生成 | 点「从图谱生成」（选 Era 维度） | 批量生成 number 型样本 | 未实施 |
| C11 | 驾驶舱卡片 | 打开 /dashboard | 「评测质量」卡片出现 | 未实施 |
| C12 | 并行安全 | 检查 git diff | DashboardService 等既有文件零改动 | 未实施（随 C11） |

### 7.6 三期风险

| 风险 | 缓解 |
|---|---|
| Agentic 评测成本最高（每断言一次查询） | 取证次数上限（EvalProxyTool 强制）+ 截断按未证实计；仅对重点样本启用 |
| judge agent 工具误用（评测写操作） | 评测 agent 只注册只读工具（/api/llm/ 只读平面），工具层与服务端红线双保险 |
| 数据集在线编辑破坏数据 | 写路径全字段校验（sampleType/轮数/referenceType/id 唯一）；DB 事务行级操作，误删靠 MySQL 备份恢复 |
| 驾驶舱并行开发冲突 | 模块四严格「只增不改」，最后实施 |

---

## 8. 风险与约束

| 风险 | 缓解 |
|---|---|
| LLM judge 成本与时长（样本×评测器次调用） | judgeRuns 可控 1-3；规则评测器零成本；数据集规模自主可控；judge 用量随 summary 留痕可审计 |
| judge 输出不稳定 | 结构化 JSON 解析（容忍围栏）+ 失败重试 1 次（3s 退避）；judgeRuns>1 取平均；token 按全部尝试累计（真实成本口径） |
| prompt 注入（样本 query 混入指令） | judge 模板固定、样本内容放明确分隔的"待评估内容"区块；自定义评测器 criteria 模式由框架隔离拼接；解析仅认 JSON schema |
| agentscope-harness Windows 缺陷（已知 Hard Constraints，2.0.3 已修复） | 评测驱动走服务端 HarnessAgent 事件流收集，不新增子进程路径 |
| 评测写操作破坏图谱 | 全链路只读：Agentic 取证走 EvalProxyTool（/api/llm/ 只读平面）+ 服务端红线双保险；评测任务不调用任何写接口 |
| 服务重启丢任务进度 | P6 起运行态进度落库（eval_run 行节流 UPDATE）：重启后 listRuns 将遗留 running 行标记 interrupted（僵尸任务），再次 start 会 409 提示先处理；历史结果不受影响 |

---

## 9. 实施现状

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一期 | 规则评测框架 + 11 个规则评测器 + API + 页面 + 示例数据集（4.2 验收 A1-A13） | 已交付 |
| 二期 | LlmJudgeGrader + judge 专用 HarnessAgent 通道 + 17 个 LLM 评测器 + judgeRuns 多次判分 + 评测报告页（5.4 验收 B1-B10） | 已交付 |
| 计划外 | 人工评测通道（human_review）+ 自定义评测器通道（custom-graders，第 6 章） | 已交付 |
| 三期模块一 | AgenticGroundednessGrader（judge 自主取证 + evidence 证据链 + 确定性重算） | 已交付 |
| 三期模块二 | Run Diff 回归对比（总体/按评测器/按样本三级 + 翻转矩阵 + 哈希校验） | 已交付 |
| 三期模块三 | 数据集在线管理 + 聊天记录沉淀（「从图谱生成」与回收站未做） | 已交付 |
| 三期模块四 | 驾驶舱集成（/api/dashboard/eval-stats + 评测质量卡片） | 未实施 |
| 存储迁移（P6） | 数据集/评测结果/自定义评测器 JSON 文件 → MySQL 五表（V10-V12），存量已迁入归档 | 已交付 |

后续可选方向：三期模块四（驾驶舱集成）、「从图谱生成」批量造题、数据集删除/回收站。
