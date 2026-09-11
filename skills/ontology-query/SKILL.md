---
name: "ontology-query"
description: "Queries ontology schema first, then generates read-only Cypher and queries graph evidence. If graph evidence is insufficient, falls back to uploaded-file search. Invoke when user asks about ontology classes, relations, properties, instances, counts, or graph facts."
---

# Ontology Query（本体查询）

这个 skill 用于回答本体里的信息查询问题，覆盖：

- 概念层：类、关系、属性、节点类型、schema 结构
- 实例层：某个实体是否存在、属于什么类别/年代/材质/主题、数量统计、样例列表
- 文件兜底：当图谱无结果或证据不足时，从已上传文件中搜索补充证据
- 构建背景：当问题属于构建背景且图谱无法回答时，可回查 `ontology-profile.json`

默认服务地址不写死：运行 `scripts/env.py` 获取默认服务地址。如用户提供了别的地址，优先使用用户给定地址；也可用环境变量 `ONTOLOGY_BASE_URL` 覆盖。

## Step 0 通道探测（最先执行）

检查你的工具清单，二选一：

- **有 `http_request_ontology_query` 工具** → 使用**通道 A**。且本次会话**禁止调用 `execute`**——本项目中 execute 存在但已损坏（Windows 参数死锁），调用必然失败。
- **没有 `http_request_ontology_query` 工具** → 使用**通道 B**（execute + curl，含全部环境探测与 Windows 约定）。

判定条件是 `http_request_ontology_query` 的存在性，优先级高于 execute 的存在性。探测无需任何工具调用，直接读自己的工具清单即可。

## Step 0.5 互斥约束（幻觉抑制红线，最终输出前必须执行）

**工具优先级**（本步骤必须执行一次）：检查你的工具清单：
- 存在原生工具 `disjoint_rules_list` → **优先直接调用它**（无参数）。它返回的文本内含详细判定规则，直接按其内部的"LLM 必须遵守的判定规则"执行即可。
- 没有 `disjoint_rules_list` → 走通道 A/B，从端点速查表调用 `GET /api/llm/disjoint-rules` 读取规则，按下面 §§ 判定规则自判。

### LLM 自行判定的严格流程（两条通道都要执行）

1. **拿到规则列表后**，逐条阅读规则，把每条规则中【】括号里的内容提取成候选项集合 S_i（第 i 条规则对应集合 S_i）。
2. 在你准备输出"最终事实性结论"（也就是按「回答组织」章节拼成完整文本**之前**），先在脑子里/草稿区把即将输出的那段最终文本写好 —— 记为 T。
3. 逐条校验：对每条规则 i，检查集合 S_i 中是否有 ≥2 个候选项（或它们的同义词/近义表达）同时出现在 T 里。
   - 例：S_i = {青铜, 瓷}，T = "这件器物是唐代青铜青花瓷" → "青铜" 与 "瓷（青花瓷中的瓷）" 同时出现 → 命中冲突。
4. **只要有任何一条规则命中冲突，你必须立即终止本次回答，不再继续生成结论/证据。** 严禁改写、严禁拆分输出、严禁用含糊措辞绕过。

### 冲突终止话术模板（必须严格使用）

```text
⚠ 检测到互斥冲突（CRM-ACA 幻觉抑制红线），输出终止。
冲突点：
  1. 规则 [R001]：材质互斥：一件器物主体不能同时是【青铜】且【瓷】
     候选集合 S = {青铜、瓷}；你的拟输出文本同时命中：青铜 + 瓷（青花瓷）
  2. （如还有其他规则逐条列出，每条对应 1 行）
请修正事实后再提问，或联系管理员在后台「图谱管理 → 互斥约束」中调整规则。
```

### 无冲突（所有规则 S_i 在 T 中命中数 < 2）

正常执行「固定执行主线（共享）」并按「回答组织」输出最终答案。

## 端点速查表（通道 A/B 共用）

| 业务动作 | HTTP | 端点 | 请求体/参数 |
|---|---|---|---|
| 概念层全量 | GET | /api/llm/schema | - |
| 单个类详情 | GET | /api/llm/schema/classes/{name} | - |
| 关系列表 | GET | /api/llm/schema/relations | - |
| 执行只读 Cypher | POST | /api/llm/ontology-query/query | {question, cypher, params} |
| 上传文件列表 | GET | /api/llm/files | - |
| 构建背景 | GET | /api/llm/profile | - |
| 互斥约束词典（只读） | GET | /api/llm/disjoint-rules | - |

## 通道 A：http_request_ontology_query 调用规范

工具清单里有 `http_request_ontology_query` 时使用本通道。工具是无业务语义的 HTTP 管道：base-url 服务端固定，只需传端点速查表中的 path。

另外如果工具清单里有原生工具 `disjoint_rules_list`，**互斥约束这一步**优先直接调用这个原生工具（无参数），不走 HTTP 管道；其余本体查询仍通过 http_request_ontology_query。原生工具返回的文本里已内置「LLM 必须遵守的判定规则」章节，按其流程执行即可。

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| path | 是 | 端点速查表中的端点，必须以 `/api/llm/` 开头 |
| method | 否 | `GET`（默认）/ `POST` |
| params | 否 | GET 查询参数，JSON 对象字符串如 `{"name":"x"}` |
| body | 否 | POST 的 JSON 请求体字符串，上限 16KB |

示例（与端点速查表逐行对应）：

- 概念层全量：path=`/api/llm/schema`
- 单个类详情：path=`/api/llm/schema/classes/CulturalRelic`
- 关系列表：path=`/api/llm/schema/relations`
- 执行只读 Cypher：path=`/api/llm/ontology-query/query`，method=`POST`，body=`{"question":"Place 有多少节点","cypher":"MATCH (n:`Place`) RETURN count(n) AS count","params":{}}`
- 上传文件列表：path=`/api/llm/files`
- 构建背景：path=`/api/llm/profile`

错误前缀三层分类：

- `[连接失败]`：服务未启动（附 base-url）→ 按熔断规则如实报告，不要编造结果
- `[参数错误]`：本地校验拒绝（path 越界 / method 非法 / body 超 16KB 或非 JSON）→ 修正参数后重试
- `[服务错误]`：HTTP 404/500 等，透传服务端 message → 读 message 判断原因（如写语句被服务端只读红线拒绝）

## 通道 B：execute + curl 调用规范

工具清单里没有 `http_request_ontology_query` 时使用本通道，通过 `execute` 执行 curl 调用端点速查表中的 HTTP API。

### 规则 0（必须最先执行）

在执行任何业务命令之前，必须先用 `execute` 运行一次环境探测：

```cmd
echo %OS%
```

判断规则：

- 输出 `Windows_NT`：按 Windows + cmd 规则拼命令
- 原样输出 `%OS%` 或命令报错：按 Linux/macOS 规则处理

如果没有先执行这一步，后续命令一律视为不合规。

### `execute` 使用强约束

1. **Windows 下禁止传 `working_directory` 参数**
   - 当前框架在 Windows 上会把它拼成 `cd '路径' &&`
   - `cmd.exe` 不识别单引号，容易直接失败
   - 需要切目录时，把 `cd /d "C:\实际路径" && ` 直接写进 `command` 前缀

2. **Windows 分支只用 cmd 兼容语法**
   - 禁止：`head`、`tail`、`cat`、`ls`、`grep`、`python3`、单引号包路径
   - 对应改用：`type`、`dir`、`findstr`、`python`

3. URL 与 JSON 参数一律用双引号包裹

4. 输出超长会自动截断，不要再接 `| head`

5. `execute` 连续 2 次失败后必须熔断
   - 停止继续盲试相似命令
   - 先检查是否违反本文件中的环境约束
   - 不要把命令包装层错误直接说成“服务连接失败”

### 错误分类

当 `execute` 失败时，先判断错误发生在哪一层。

#### 1. 命令包装层 / shell 层错误

若报错包含以下特征：

- 路径语法错误
- `cd '路径' &&`
- “文件名、目录名或卷标语法不正确”
- Windows 下混入 `head`、`python3`、单引号路径等

则说明这是**命令包装层错误**，不是 HTTP 服务失败，不应直接下结论说“本体服务连接失败”。

#### 2. HTTP / 服务层错误

只有在命令已成功发出请求后，出现连接拒绝、超时、404、500 等，才能判断为**服务层错误**。

## 固定执行主线（共享）

除非用户明确要求只查文件或只看构建背景，否则严格按以下顺序执行，不要跳步，不要改顺序（怎么发指令见你所在通道的章节）：

1. 先查询 schema，理解概念层
2. 根据 schema 对齐用户问题中的类、属性、关系、keyProp
3. 组装只读 Cypher
4. 调用 `/api/llm/ontology-query/query`
5. 若图谱结果为空或不足，再调用 `/api/llm/files`
6. 根据文件列表筛选候选文件，再用文件工具搜索命中内容
7. 若文件仍不能回答，再明确说明未查询到相关结果

禁止：

- 跳过 schema 直接猜类名、属性名、关系名
- 在图谱尚未查询前直接转去本地文件搜索
- 把项目目录中的任意文件直接当作“已上传文件证据”
- 没有证据就给出猜测性答案

### Step 1 理解用户问题

先判断用户问题属于哪一类：

- 概念层问题：如“有哪些类”“Era 有什么属性”“Theme 和 CulturalRelic 什么关系”
- 实例层问题：如“妇好墓司母辛鼎是什么年代”“有没有青花主题文物”
- 统计问题：如“Place 有多少节点”
- 构建背景问题：如“这个本体的数据来源是什么”“抽取规则是什么”

### Step 2 先查询概念层

必须先调用 `GET /api/llm/schema`；如有必要再调用 `GET /api/llm/schema/classes/{name}`、`GET /api/llm/schema/relations`。

先确认：

- 用户问题对应的类名或候选类名
- 相关属性名 / 中文名 / 数据类型
- 相关关系名 / 中文名 / 方向
- keyProp

如果 schema 中无法支持该问题：不要臆造类名、属性名、关系名，不要直接生成 Cypher；可继续进入文件兜底，或明确说明概念层不足以支撑该查询。

### Step 3 组装只读 Cypher

只有在 Step 2 已完成 schema 对齐后，才允许生成 Cypher。

- 只允许 `MATCH` / `OPTIONAL MATCH` / `WHERE` / `WITH` / `RETURN` / `ORDER BY` / `LIMIT`
- Cypher 中出现的 label / property / relation 名称必须来自 schema
- 优先精确查，必要时再模糊查
- 默认加 `LIMIT`
- 不要生成写语句

### Step 4 执行图谱查询

调用 `POST /api/llm/ontology-query/query`，请求体示例：

```json
{
  "question": "Place 有多少节点？",
  "cypher": "MATCH (n:`Place`) RETURN count(n) AS count",
  "params": {}
}
```

补充约束：

- 必须按 `schema -> cypher -> query` 的显式流程执行
- 图谱结果为空、无命中或证据不足时，才进入文件兜底

### Step 5 图谱无结果时从已上传文件中搜索

只有在 `/query` 返回结果为空、证据不足或无法回答时，才进入本步骤。

必须先调用 `GET /api/llm/files` 获取已上传文件列表，然后：

1. 按 `description`、`name` 与用户问题相关度挑选候选文件
2. 用 `grep_files` 搜索关键词
3. 命中后用 `read_file` 读取命中附近内容
4. 引用时注明文件名与具体片段

文件工具清单：`read_file` / `grep_files` / `glob_files` / `list_files`。禁止使用错误的不存在的工具名：`glob`、`Read`、`cat_file`，以及任何未在本 skill 中明确列出的近似别名。

补充约束：

- 只能把 `/api/llm/files` 返回的文件视为 `file` 证据
- 不要直接扫描项目目录、本地仓库目录或猜测文件路径代替 `/api/llm/files`
- 只有接口返回的绝对路径，才能交给 `grep_files` / `read_file`
- 大文件禁止整读
- pdf 当前运行时若无解析能力，应如实说明暂不支持读取

## 规则（共享）

1. 必须先看概念层，再决定怎么查图谱
2. Neo4j 语句只能是只读查询，禁止任何写入、修改、删除
3. 没查到就明确说“当前本体中不存在该信息”或“当前图谱未查询到相关结果”
4. 回答时区分证据来源：
   - `graph`：来自 Neo4j 图谱
   - `file`：来自已上传文件
   - `profile`：来自 `ontology-profile.json`
   - `none`：未查到
5. 禁止把 agent 记忆（memory）或历史会话内容冒充本次查询证据；记忆只能作补充且必须显式标注 `memory`
6. 熔断：同一通道连续 2 次失败后必须停止，先检查是否违反本 skill 的约束，禁止盲试相似调用；收到 `[连接失败]` 前缀时直接如实报告服务未启动，不要编造查询结果

## 回答组织

输出时按下面规则：

- 先给结论
- 再给证据来源（graph / file / profile / none）
- 若未查到，明确说明“不存在”或“未查询到”
- 不要用猜测补全空白

回答模板参考：

```text
结论：...
证据来源：graph
图谱证据：...
```

文件证据模板：

```text
结论：...
证据来源：file
文件证据：{文件名} 中命中：...（引用具体片段）
```

未查到模板：

```text
结论：当前本体中不存在该信息。
证据来源：none
说明：已检查 schema、图谱结果与文件兜底，未命中相关信息。
```
