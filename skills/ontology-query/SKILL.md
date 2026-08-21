---
name: "ontology-query"
description: "Queries ontology concepts and graph evidence, then answers with explicit evidence or not-found results. Invoke when user asks about ontology classes, relations, properties, instances, or graph facts."
---

# Ontology Query（本体查询）

这个 skill 用于回答本体里的信息查询问题，覆盖：

- 概念层：类、关系、属性、节点类型、schema 结构
- 实例层：某个实体是否存在、属于什么类别/年代/材质/主题、数量统计、样例列表
- 构建背景：当图谱证据不足时，回查 `ontology-profile.json` 中记录的构建期问答

默认服务地址：`http://localhost:9876`。如用户提供了别的地址，优先使用用户给定地址；也可用环境变量 `ONTOLOGY_BASE_URL` 覆盖。

## 环境与工具约定

**规则 0（必须最先执行）：先探测操作系统，再拼接命令。**

在执行任何业务命令（curl、目录切换、文件操作等）之前，必须先用 `execute` 运行一次环境探测，确认当前是 Windows 还是 Linux/macOS，避免按错误的 shell 语法拼命令：

```cmd
echo %OS%
```

- 输出 `Windows_NT` → Windows + cmd.exe，按下方 Windows 规则拼接命令。
- 原样输出 `%OS%` 或命令报错 → Linux/macOS，可用 `uname -s` 进一步确认，后续改用 Unix 语法（路径分隔符 `/`，可用 `cat`/`ls`/`grep`/`head`、单引号）。
- 探测结论在整个会话内保持一致，后续所有命令都按同一环境的语法拼接，禁止两套语法混用。

当前预期部署环境是 Windows 10，shell 为 cmd.exe（以规则 0 的实际探测结果为准）。本文所有 HTTP 接口都通过 `execute` 工具执行 curl 命令访问。

`execute` 使用规则：

1. **禁止传 `working_directory` 参数**：当前框架版本在 Windows 上会把它拼成 `cd '路径' &&`（单引号），cmd.exe 不识别，必然失败。需要指定目录时，把 `cd /d "C:\实际路径" && ` 直接写在 `command` 开头。
2. 只用 Windows/cmd 语法：禁用 `head`、`tail`、`cat`、`ls`、`grep`、`python3` 等 Unix 习惯；对应能力改用 `type`、`dir`、`findstr`、`python`。
3. URL 与 JSON 参数一律用双引号包裹。POST JSON 示例：`curl -s -X POST "http://localhost:9876/api/llm/ontology-query/query" -H "Content-Type: application/json" -d "{\"question\":\"...\",\"cypher\":\"...\",\"params\":{}}"`
4. 输出超长会由框架自动截断，不要接 `| head`。
5. 熔断：`execute` 连续 2 次失败（尤其 stderr 是乱码）时，停止换命令盲试；改用 `read_file`/`grep_files`/`list_files` 完成任务，仍不行就如实向用户报告环境故障。

文件类工具的实际名称：`read_file`（支持 `offset`/`limit` 分页）、`grep_files`（字面关键词搜索）、`glob_files`、`list_files`。不要调用 `Read`、`glob` 等不存在的工具名。

严格按下面顺序执行，不要跳步。

## 规则

1. 必须先看概念层，再决定怎么查图谱。
2. Neo4j 语句只能是只读查询，禁止任何写入、修改、删除。
3. 没查到就明确说“当前本体中不存在该信息”或“当前图谱未查询到相关结果”，不要补造答案。
4. 回答时区分证据来源：
   - `graph`：来自 Neo4j 图谱
   - `file`：来自已上传文件（`GET /api/llm/files` 列表中的文件）
   - `profile`：来自 `ontology-profile.json`
   - `none`：未查到

## 执行步骤

### Step 1 理解用户问题

先判断用户问题属于哪一类：

- 概念层问题：如“有哪些类”“Era 有什么属性”“Theme 和 CulturalRelic 什么关系”
- 实例层问题：如“妇好墓司母辛鼎是什么年代”“有没有青花主题文物”
- 统计问题：如“Place 有多少节点”
- 构建背景问题：如“这个本体的数据来源是什么”“抽取规则是什么”

### Step 2 先查询概念层

必须先调用：

- `GET /api/llm/schema`
- 如需单类细节，可再调用 `GET /api/llm/schema/classes/{name}`
- 如需关系清单，可再调用 `GET /api/llm/schema/relations`

先确认：

- 类名 / 中文名
- 属性名 / 中文名 / 数据类型
- 关系名 / 中文名 / 方向
- keyProp

只有在 schema 能支撑问题时，才进入下一步组装 Cypher。

### Step 3 组装只读 Cypher

根据用户问题和 schema 生成 Neo4j 查询语句：

- 只允许 `MATCH` / `OPTIONAL MATCH` / `WHERE` / `WITH` / `RETURN` / `ORDER BY` / `LIMIT`
- 优先精确查，必要时再模糊查
- 默认加 `LIMIT`
- 不要生成写语句

### Step 4 执行图谱查询

调用：

- `POST /api/llm/ontology-query/query`

请求体示例：

```json
{
  "question": "有哪些年代概念？",
  "cypher": "MATCH (n:`Era`) RETURN n.`name` AS key, properties(n) AS props ORDER BY key LIMIT 20",
  "params": {}
}
```

如果想直接让服务端完成“问题理解 + schema 对齐 + 查询 + 总结”，可调用：

- `POST /api/llm/ontology-query/ask`

### Step 5 证据不足时回查 profile

当图谱证据为空、结果不足以回答问题，或者用户问的是构建背景时，再调用：

- `GET /api/llm/profile`

重点关注：

- `domain`
- `purpose`
- `data_source`
- `concept_scope`
- `concept_design`
- `extract_rules`
- `final_report`

只把 profile 当作兜底证据，不要把它伪装成图谱查询结果。

### Step 5.1 从已上传文件中搜索

当 profile 仍不足以回答时，调用：

- `GET /api/llm/files`

获取已上传文件列表，每条字段：

- `name`：文件名
- `path`：绝对路径
- `size`：大小
- `ext`：扩展名（pdf/txt/csv）
- `description`：描述
- `uploadedAt`：上传时间

然后从文件中搜索：

1. 按 `description`、`name` 与用户问题的相关度挑选文件；列表为空则说明无文件可查，不要凭空补造。
2. 先用 `grep_files` 在候选文件中搜索关键词：`pattern` 填关键词，`path` 用文件列表返回的绝对路径；大文件禁止整读。
3. 命中后用 `read_file` 读取命中行附近的内容（`offset`/`limit` 分页定位），csv 按行列引用。
4. 引用时注明文件名与具体片段作为证据。
5. pdf 当前运行时没有解析工具：如实告知用户暂不支持读取 pdf，不要伪装成已读取。

只把文件当作兜底证据，不要把它伪装成图谱查询结果。

### Step 6 组织回答

输出时按下面规则：

- 先给结论
- 再给证据来源（graph / profile / file）
- 若未查到，明确说明“不存在”或“未查询到”
- 不要用猜测补全空白

回答模板参考：

```text
结论：...
证据来源：graph
图谱证据：...
```

证据来自文件时：

```text
结论：...
证据来源：file
文件证据：{文件名} 中命中：...（引用具体片段）
```

若未查到：

```text
结论：当前本体中不存在该信息。
证据来源：none
说明：已检查概念层与图谱结果，未命中相关节点/关系。
```
