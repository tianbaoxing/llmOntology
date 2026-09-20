# 评测提示词清单（OpenJudge-main 与 llmOntology eval 包对照）

生成日期：2026-09-20

---

## 一、llmOntology 项目提示词（`eval` 包）

### 1. 系统提示词（SysPrompt / Agent 系统角色）

| 序号 | 提示词名称 | 所在文件 | 核心内容 |
| :--: | :--: | :--: | :-- |
| S1 | **Judge 纯 LLM 判分系统提示词** | [JudgeAgentHolder.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/JudgeAgentHolder.java#L92-L95) | 你是问答系统质量评测专家，评测「{title}」问答系统的回答质量。评分采用 1-5 整数分制，具体评分标准见用户消息开头的【评分标准】区块。只输出一个 JSON 对象：`{"score": 分数, "reason": "一句话中文理由"}` |
| S2 | **Agentic 取证评测系统提示词**（含图谱端点速查与数据模型提示） | [AgenticJudgeHolder.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/AgenticJudgeHolder.java#L171-L197) | 你是图谱问答质量评测专家，负责对问答系统的回答做事实核查（Agentic 取证评测）。工作流程：拆解断言 → 用 `http_request_ontology_query` 工具查图谱取证 → 输出核查结论。内置 6 个只读端点速查、数据模型属性提示（CulturalRelic 的 title/itemId，关系 OF_ERA/OF_CATEGORY 等）、取证次数上限、score=证实/总断言数、输出 `{"score","reason","evidence":[{"claim","cypher","summary","confirmed"}]}` 契约 |

### 2. 用户提示词模板基类（UserPrompt / 区块拼接）

| 序号 | 提示词名称 | 所在文件 | 核心内容 |
| :--: | :--: | :--: | :-- |
| U1 | **LlmJudgeGrader 五变量用户模板**（所有 LLM 评测器的基类） | [LlmJudgeGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/LlmJudgeGrader.java#L140-L157) | 固定区块拼接：【评分标准（1-5 整数分制）】+ 【问题】`{query}` + 【待评估回答】`{answer}` + 【参考答案】`{reference}` + 【证据上下文】`{context}` + 【补充评分细则】`{rubric}` + `extraBlock`（子类追加轨迹/对话/期望清单） |
| U2 | **自定义评测器 template 模式输出要求** | [CustomLlmGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/custom/CustomLlmGrader.java#L71) | 【输出要求】仅输出 JSON：`{"score": 1-5 整数分, "reason": "简短中文理由"}` |
| U3 | **Agentic 取证用户提示词** | [AgenticGroundednessGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/agent/AgenticGroundednessGrader.java#L188-L197) | 【原问题】`{query}` + 【待核查回答】`{answer}` + 【补充核查细则】`{rubric}` + 指令：请拆解上述回答中的事实断言，逐条用工具向图谱取证，然后按系统指令输出 JSON。 |
| U4 | **期望清单（Ground Truth）区块**（供轨迹类评测器追加使用） | [LlmJudgeGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/LlmJudgeGrader.java#L217-L244) | 【期望清单】分三组列：期望 Skills / 期望普通工具 / 期望 MCP 工具；评分说明：对期望内调用更宽容、偏离且无解释应扣分、漏用应在 reasoning 中明确指出 |
| U5 | **工具调用轨迹渲染区块** | [LlmJudgeGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/LlmJudgeGrader.java#L180-L194) | 【工具调用轨迹】序号 + 工具名 + 截断 args + 截断 result |
| U6 | **多轮对话记录渲染区块** | [LlmJudgeGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/LlmJudgeGrader.java#L196-L210) | 【多轮对话记录（按时间序）】第 N 轮 + 问/答（各截断 300/400 字符） |

### 3. 评分标准（Criteria）—— 各评测器 1-5 分量义

#### 3.1 回答质量类（answer 子包）

| 序号 | 评测器名 | 所在文件 | Criteria 原文 |
| :--: | :--: | :--: | :-- |
| C1 | `llm_correctness` 正确性 | [LlmCorrectnessGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmCorrectnessGrader.java#L32-L35) | 5=与参考答案一致且无事实错误；4=基本正确仅细节出入；3=部分正确；2=多数错误；1=完全错误或编造。无参考答案时依据证据上下文判断事实是否可靠。 |
| C2 | `llm_relevance` 相关性 | [LlmRelevanceGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmRelevanceGrader.java#L31-L33) | 5=切题且直接回答问题；4=切题但略有冗余；3=部分相关；2=大部分跑题；1=答非所问。 |
| C3 | `llm_response_completeness` 完整性 | [LlmCompletenessGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmCompletenessGrader.java#L31-L34) | 5=问题要点全覆盖无遗漏；4=基本覆盖；3=有可感知遗漏；2=明显缺失；1=严重缺失。参考答案与补充评分细则中列出的要点应视为必须覆盖。 |
| C4 | `llm_response_helpfulness` 有用性 | [LlmHelpfulnessGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmHelpfulnessGrader.java#L31-L33) | 5=信息充足、直接可用；4=基本有用；3=帮助有限；2=几乎无帮助；1=无帮助。 |
| C5 | `llm_hallucination` 幻觉检测 | [LlmHallucinationGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmHallucinationGrader.java#L32-L35) | 评估回答是否编造无依据信息（高分=无幻觉）：5=全部事实均被参考答案或证据上下文支持；4=个别表述无出处但无害；3=主体有据但个别细节存疑；2=较多内容无依据；1=大量编造。 |
| C6 | `llm_instruction_following` 指令遵循 | [LlmInstructionFollowingGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmInstructionFollowingGrader.java#L32-L37) | 问题或【补充评分细则】中若含格式/字数/范围等约束（如「仅回答朝代」「不超过两句」），5=完全遵循全部约束；4=基本遵循仅轻微越界；3=部分遵循；2=多数约束未遵循；1=完全无视约束。无显式约束时：5=切题回答且不夹带无关内容；3=切题但冗余；1=答非所问或自行编造约束外的输出。 |
| C7 | `llm_reasoning_groundedness` 推理有据性 | [LlmReasoningGroundednessGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/answer/LlmReasoningGroundednessGrader.java#L32-L35) | 评估回答中的结论是否被证据上下文支撑：5=结论均被证据支撑；4=基本有据；3=部分有据；2=多数无据；1=凭空臆测。（需工具调用） |

#### 3.2 工具轨迹类（tool 子包）

| 序号 | 评测器名 | 所在文件 | Criteria 原文 |
| :--: | :--: | :--: | :-- |
| C8 | `llm_tool_call_accuracy` 工具调用正确性 | [LlmToolCallAccuracyGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/tool/LlmToolCallAccuracyGrader.java#L33-L36) | 结合问题评估工具调用的正确性：5=工具选择与参数全部正确高效；4=正确但参数欠精确；3=基本可用；2=多次不当调用；1=工具或参数明显错误。（需工具调用 + 追加 【期望清单】+【工具调用轨迹】） |
| C9 | `llm_tool_selection` 工具选择合理性 | [LlmToolSelectionGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/tool/LlmToolSelectionGrader.java#L33-L36) | 评估工具选择合理性：5=选择恰当无冗余；4=恰当但略有冗余；3=可用但非最优；2=明显多余调用；1=完全误选。（需工具调用 + 追加 【期望清单】+【工具调用轨迹】） |
| C10 | `llm_tool_parameter_check` 工具参数抽取 | [LlmToolParameterCheckGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/tool/LlmToolParameterCheckGrader.java#L33-L37) | 逐条检查工具调用的参数是否忠实抽取自问题：5=全部参数均能在问题中找到出处，无编造；4=参数基本正确，个别拼写或大小写出入；3=偶有一处编造（问题中不存在的实体/条件）；2=多处参数编造；1=参数与问题基本无关。（需工具调用 + 追加 【期望清单】`false` 不含 skills +【工具调用轨迹】） |
| C11 | `llm_tool_usage_efficiency` 工具使用效率 | [LlmToolUsageEfficiencyGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/tool/LlmToolUsageEfficiencyGrader.java#L33-L37) | 评估工具使用的效率与经济性：5=调用次数精炼，每次调用都贡献了新信息；4=总体高效，个别冗余调用；3=存在明显的重复查询（相同信息查了多次）；2=大量冗余调用或绕圈；1=过度调用严重失控。（需工具调用 + 追加 【期望清单】+【工具调用轨迹】） |
| C12 | `llm_trajectory_accuracy` 轨迹准确性 | [LlmTrajectoryAccuracyGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/tool/LlmTrajectoryAccuracyGrader.java#L33-L36) | 综合问题、最终回答与工具调用轨迹评估整体效果：5=高效达成（步骤精炼）；4=达成但略有绕路；3=达成但明显绕路；2=勉强接近目标；1=未达成。（需工具调用 + 追加 【期望清单】+【工具调用轨迹】） |

#### 3.3 多轮对话类（dialogue 子包）

| 序号 | 评测器名 | 所在文件 | Criteria 原文 |
| :--: | :--: | :--: | :-- |
| C13 | `llm_context_memory` 跨轮上下文记忆 | [LlmContextMemoryGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/dialogue/LlmContextMemoryGrader.java#L33-L37) | 评估最后一轮回答对先前轮次上下文的利用：5=正确沿用先前对话中的信息（主题/实体/已给结论），无信息丢失与矛盾；4=基本沿用，个别细节需回看；3=部分利用，部分当作新话题重新回答；2=基本忽略上下文，重复提问先前已给出的信息；1=完全当作新话题或与先前轮次矛盾。（需多轮 + 追加 【多轮对话记录】） |
| C14 | `llm_anaphora_resolution` 指代消解 | [LlmAnaphoraResolutionGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/dialogue/LlmAnaphoraResolutionGrader.java#L34-L39) | 评估最后一轮问题中的代词/省略主语是否被正确消解：5=代词准确指向先前对话中的对象，回答围绕该对象展开；4=消解正确但回答偶有跑偏；3=消解基本正确但信息混用；2=消解错误，回答了别的对象；1=完全无视指代或答非所问。若最后一轮问题无代词且主语明确，则按上下文衔接是否自然评分。（需多轮 + 追加 【多轮对话记录】） |
| C15 | `llm_instruction_clarification` 主动澄清 | [LlmInstructionClarificationGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/dialogue/LlmInstructionClarificationGrader.java#L34-L38) | 若最后一轮问题含糊或指代不明（前文有多个候选对象），5=主动澄清请求；4=给出澄清的同时按最可能对象作答并注明假设；3=未澄清但选择了合理对象作答；2=未澄清且对象选择明显牵强；1=未澄清且臆测作答。问题本身明确无歧义时：5=正常作答，不画蛇添足地反问。（需多轮 + 追加 【多轮对话记录】） |
| C16 | `llm_response_repetition` 跨轮回答重复 | [LlmResponseRepetitionGrader.java](file:///C:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/eval/graders/llm/dialogue/LlmResponseRepetitionGrader.java#L33-L37) | 评估最后一轮回答相对先前轮次的信息增量：5=针对新问题给出了新信息，无复述；4=以新信息为主，仅有必要的少量回顾；3=新旧信息各半；2=大部分是先前轮次内容的复述；1=完全重复先前回答，无任何新信息。（需多轮 + 追加 【多轮对话记录】） |

---

## 二、OpenJudge-main 项目提示词

### 1. 基类系统提示词（通用 LLM Judge 角色）

| 序号 | 提示词名称 | 所在文件 | 核心内容 |
| :--: | :--: | :--: | :-- |
| O-S1 | **LLMGrader.SYSTEM_PROMPT_EN**（英语） | [llm_grader.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/llm_grader.py#L63-L66) | You are an evaluation assistant. Output ONLY a valid JSON object with this exact structure: `{"reason":"<brief explanation>","score":<integer>}` |
| O-S2 | **LLMGrader.SYSTEM_PROMPT_ZH**（中文） | [llm_grader.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/llm_grader.py#L67-L70) | 你是一个评估助手。仅输出一个符合此精确结构的有效 JSON 对象：`{"reason":"<对所评分数的简要解释>","score":<整数>}` |
| O-S3 | **LLMGrader 默认 System 模板（str 模式）** — 英语 | [llm_grader.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/llm_grader.py#L134-L138) | system: You are a professional evaluation assistant. Please evaluate according to the user's requirements. + user: 自定义 template 内容 |
| O-S4 | **LLMGrader 默认 System 模板（str 模式）** — 中文 | [llm_grader.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/llm_grader.py#L144-L148) | system: 你是个专业的评估助手，请你根据用户要求进行评估。 + user: 自定义 template 内容 |
| O-S5 | **AgenticGrader 默认 System 模板**（EN/ZH 同上） | [agentic_grader.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agentic_grader.py#L171-L193) | 与 LLMGrader 相同，用 `textwrap.dedent(template)` 作为 user 消息 |

### 2. 通用基础评测器（`graders.common`）完整 Prompt（EN/ZH 双语 + 结构化 Rubrics/Steps/Constraints/Scale）

| 序号 | 评测器名 | 所在文件 | Prompt 变量占位符 | 关键结构 |
| :--: | :--: | :--: | :--: | :-- |
| O-C1 | `correctness` 正确性（`CorrectnessGrader`） | [correctness.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/common/correctness.py#L22-L178) | `{query}`, `{context}`, `{reference_response}`, `{response}` | `<Rubrics>`：7 条正向 + 7 条扣分项；`<Steps>`：6 步；`<Constraints>`：目标是与参考答案对比而非通用质量；`<Scale>` 5 分量义（完美/核心一致/部分/矛盾/完全无关）；`<Output Schema>`：`{"reason","score":1-5}` |
| O-C2 | `relevance` 相关性（`RelevanceGrader`） | [relevance.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/common/relevance.py#L23-L166) | `{query}`, `{context}`, `{reference_response}`, `{response}` | `<Rubrics>`：5 条正向 + 5 条扣分项；`<Steps>`：6 步；`<Constraints>`：评估相关性而非整体质量；`<Scale>` 5 分量义（完全/高度/部分/弱/不相关） |
| O-C3 | `hallucination` 幻觉检测（`HallucinationGrader`） | [hallucination.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/common/hallucination.py#L24-L147) | `{query}`, `{context}`, `{reference_response}`, `{response}` | `<Rubrics>`：5 条（仅可验证事实/不无依据声明/不臆造细节/数字日期精确/不确定时明示）；`<Steps>`：6 步；`<Constraints>`：仅关注事实准确性，有 context 用 context、无 context 用常识；`<Scale>` 5=无幻觉，1=完全捏造 |
| O-C4 | `instruction_following` 指令遵循（`InstructionFollowingGrader`） | [instruction_following.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/common/instruction_following.py#L22-L160) | `{instruction}`, `{query}`, `{response}` | `<Rubrics>`：6 条正向 + 6 条扣分项；`<Steps>`：6 步；`<Constraints>`：评估指令遵循能力，而非内容质量本身；`<Scale>` 5 分量义（完美/大部分/部分/严重违反/完全失败） |
| O-C5 | `harmfulness` 有害性 / `search_correctness` 搜索正确性 | `graders/common/` 目录 | — | 同 LLMGrader 框架的模板化结构 |

### 3. Agent 轨迹能力评测器（`graders.agent`，二进制 0/1 打分 + 结构化 Rubrics/Steps）

| 序号 | 评测器名 | 所在文件 | Prompt 占位符 | 关键结构 |
| :--: | :--: | :--: | :--: | :-- |
| O-A1 | `tool_call_accuracy` 工具调用准确性（`ToolCallAccuracyGrader`） | [tool_call_accuracy.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agent/tool/tool_call_accuracy.py#L25-L112) | `{query}`, `{tool_calls}`, `{tool_definitions}` | `<Rubrics>`：工具相关性 + 参数正确性；`<Scale>` 5 分（完全/基本/部分/无关/完全无关）；最终 score 1-5 clamp 后输出 raw_score |
| O-A2 | `memory_accuracy` 记忆准确性（`MemoryAccuracyGrader`） | [memory_accuracy.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agent/memory/memory_accuracy.py#L24-L119) | `{context}`, `{history}`, `{observation}`, `{memory}` | `<Rubrics>`：5 条（观察→记忆保真/仅事实细节/正确解释/准确关联/与观察一致）；`<Steps>`：4 步；`<Scale>` 二元：1.0=准确，0.0=不准确 |
| O-A3 | `plan_feasibility` 计划可行性（`PlanFeasibilityGrader`） | [plan_feasibility.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agent/plan/plan_feasibility.py#L24-L121) | `{context}`, `{history}`, `{plan}`, `{observation}`, `{memory}` | `<Rubrics>`：5 条（因果逻辑/可行顺序/当前环境可执行/前提条件/步骤目标一致）；`<Scale>` 二元：1.0=可行，0.0=不可行 |
| O-A4 | `reasoning_groundedness` 推理有据性（`ReasoningGroundednessGrader`） | [reasoning_groundedness.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agent/reasoning/reasoning_groundedness.py#L25-L124) | `{context}`, `{history}`, `{observation}`, `{reasoning}` | `<Rubrics>`：6 条（仅由观察支撑的声明/不引入观察外信息/推断有逻辑/不无依据因果/区分观察与推断/不用假设填补）；`<Steps>`：5 步（提取声明→查证→检测虚构→评估推断→整体）；`<Scale>` 二元 |
| O-A5 | `reflection_accuracy` 反思准确性（`ReflectionAccuracyGrader`） | [reflection_accuracy.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agent/reflection/reflection_accuracy.py#L24-L119) | `{context}`, `{history}`, `{observation}`, `{reflection}` | `<Rubrics>`：5 条（仅提观察中对象/仅描述观察中状态/仅用观察细节/仅反思实际观察到/仅可从观察推导信息）；`<Scale>` 二元 |
| O-A6 | `trajectory_accuracy` 轨迹准确性（`TrajectoryAccuracyGrader`） | [trajectory_accuracy.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/agent/trajectory/trajectory_accuracy.py#L26-L113) | `{messages}` | `<Rubrics>`：逻辑连贯 + 目标推进 + 效率；`<Scale>` 3 分制（3=成功无无关/2=成功但有无关/1=失败） |
| O-A7 | 其他 agent 评测器（20 个）：`action_alignment`, `action_loop`, `memory_detail_preservation`, `memory_retrieval_effectiveness`, `observation_information_gain`, `plan_decomposition`, `reasoning_coherence`, `reflection_outcome_understanding`, `reflection_progress_awareness`, `response_completeness`, `response_helpfulness`, `tool_call_precision_recall_match`, `tool_call_step_sequence_match`, `tool_call_success`, `tool_selection`, `tool_usage_efficiency`, `tool_parameter_check`, `trajectory_comprehensive`, `trajectory_error_recovery`, `trajectory_step_efficiency` | `graders/agent/{action,memory,observation,plan,reasoning,reflection,response,tool,trajectory}/` | 同结构 | 均为 LLMGrader 子类 + 英文/中文双 Prompt，变量名各自对应模块字段，Scale 多为二元 0/1 |

### 4. 多轮对话评测器（`graders.multi_turn`，1-5 分制）

| 序号 | 评测器名 | 所在文件 | Prompt 占位符 | 关键结构 |
| :--: | :--: | :--: | :--: | :-- |
| O-M1 | `context_memory` 上下文记忆（`ContextMemoryGrader`） | [context_memory_grader.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/openjudge/graders/multi_turn/context_memory_grader.py#L31-L138) | `{history}`, `{response}` | 中文/英文双 Prompt；`<评分标准>`：5 条（事实/偏好/引用/一致/细节复述）；`<评估步骤>`：5 步；`<注意事项>`：当前回复无需早期信息不扣分；`<评分量表>` 5 分量义（完美/良好/基本/不足/严重遗忘） |
| O-M2 | 其他 6 个多轮评测器：`anaphora_resolution_grader`, `instruction_clarification_grader`, `proactive_interaction_grader`, `response_repetition_grader`, `self_correction_grader`, `topic_switch_grader` | `graders/multi_turn/` | 同结构 | 中文/英文双 Prompt，`<评分标准>/<评估步骤>/<注意事项>/<评分量表>` + `{"reason","score":1-5}` JSON 输出 |

### 5. Cookbook 专用提示词（论文审稿场景）

| 序号 | 提示词名称 | 所在文件 | 核心内容 |
| :--: | :--: | :--: | :-- |
| O-P1 | **get_review_system_prompt()** — 论文审稿系统提示词（动态拼装） | [review.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/cookbooks/paper_review/prompts/review.py#L10-L140) | 动态拼装块：审稿人身份（按学科/通用CS）+ 日期 + 目标会议/期刊（如 NeurIPS）+ 学科专用评审维度（默认 7 个：Quality/Clarity/Significance/Originality/Reproducibility/Ethics/Citations）+ 1-6 分录用量表（Strong Reject → Strong Accept）+ 特殊评审人指令 + 输出语言开关（EN/ZH）；最终要求 `<review>详细审稿</review><answer>X</answer>` 格式 |
| O-P2 | **REVIEW_USER_PROMPT** — 论文审稿用户提示词 | [review.py](file:///C:/me/kaiyuan/agenttrace/OpenJudge-main/cookbooks/paper_review/prompts/review.py#L142) | `Please review this paper and provide an overall recommendation score from 1 to 6.` |
| O-P3 | 论文审稿其他 7 个专用 prompt 文件：`correctness`, `criticality`, `format`, `jailbreaking`, `rebuttal_assessment`, `rebuttal_generation`, `review` | `cookbooks/paper_review/prompts/` | 各维度专用 | 按正确性/批判性/格式/越狱检测/反驳评估/反驳生成维度，提供结构化评分 Prompt |

### 6. 其他内置评测器组（未逐一展开，均按 LLMGrader/FunctionGrader 框架）

| 组名 | 目录 | 数量 |
| :--: | :--: | :--: |
| **Format 格式评测器** | `graders/format/` | 6 个（`json_match`, `json_validator`, `length_penalty`, `ngram_repetition_penalty`, `reasoning_format`, `reasoning_tool_format`） |
| **Skills 评测器** | `graders/skills/` | 5 个（`completeness`, `declaration_alignment`, `design`, `relevance`, `threat_analysis`） |
| **Code 代码评测器** | `graders/code/` | 8 个（`code_bug_detection`, `code_complexity`, `code_execution`, `code_security`, `code_style`, `patch_similarity`, `syntax_checker` + utils） |
| **Math 数学评测器** | `graders/math/` | 1 个（`math_expression_verify`） |
| **Multimodal 多模态评测器** | `graders/multimodal/` | 3 个（`image_coherence`, `image_helpfulness`, `text_to_image`） |
| **Text 文本规则评测器** | `graders/text/` | 3 个（`number_accuracy`, `similarity`, `string_match`） |

---

## 三、两项目对应关系摘要（对齐点 & 差异）

| 维度 | llmOntology（二期 Java 版） | OpenJudge-main（Python 原版） |
| :--: | :--: | :--: |
| **SysPrompt 拆分** | 「守则固定在 sysPrompt，评分标准由用户消息首区块下发」——严格分离 | 通用 `SYSTEM_PROMPT_{EN,ZH}` 只规定 JSON 契约；具体 Rubrics/Steps/Scale 全部在 user prompt 中 |
| **评分分制** | 1-5 整数 → 归一化 `(raw-1)/4` 到 0-1 | 1-5 / 二元 0/1 / 3 分制（轨迹）/ 1-6（审稿） |
| **Prompt 五变量** | `query / answer / reference / context / rubric`（Java `variables()` 方法统一） | 不同 Grader 变量不同：`{query/context/reference_response/response}` 或 `{instruction}` 或 `{observation/memory}` 等 |
| **轨迹类 extraBlock** | 统一在 `renderTrajectory()` / `renderExpectedBlock()` / `renderDialogue()` | 每个 Grader 自带独立 Prompt 全文，Rubrics/Steps 与业务强绑定 |
| **Agentic 取证** | 定制 `AgenticJudgeHolder.sysPrompt()`（内置图谱端点速查 + 数据模型） | 通用 `AgenticGrader` 仅提供 agent 框架 + 解析，具体 Prompt 外部传入 |
| **多语言** | 仅中文 Prompt | 所有 Grader 均 EN/ZH 双 Prompt，`LanguageEnum` 选择 |
| **自定义评测器** | `CustomLlmGrader` 支持 criteria 模式 / template 模式（`{{query}}` 等五占位符）+ SHA-256 promptHash 留痕 | `LLMGrader.__init__` 直接接受 str/list/dict/PromptTemplate 四种 template 入参 |
