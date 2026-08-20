---
name: "ontology-builder"
description: "Builds a domain ontology (concept layer + instance layer) into the ontology graph service via its LLM REST API. Invoke when user wants to build/construct an ontology or knowledge graph from their domain data files in a fresh deployment. 触发场景：用户要求为某个领域构建本体/知识图谱、导入领域数据。"
---

# Ontology Builder（领域本体构建）

产品首次部署时，用户场景未知。本 skill 通过 ontology 服务的 LLM 接口（`/api/llm/**`）把用户的领域数据构建成图谱：**概念层（schema）+ 实例层（数据）**。

- 服务地址：默认 `http://localhost:9876`（可用环境变量 `ONTOLOGY_BASE_URL` 覆盖；不通时先向用户确认服务地址）。
- 所有写接口 **幂等（MERGE）**，失败可安全重跑。
- 铁律：**概念层先行**。label/type 不在 schema 白名单内的写入会被服务端直接拒绝；关系方向必须匹配 schema 声明（签名校验）。

严格按以下 6 步执行，每步完成并确认后再进入下一步。

---

## Step 1 问询（phase=intake）

首次部署不知道用户场景，**必须先问再动**。用 AskUserQuestion 或直接提问，至少覆盖：

1. **场景**：这是什么领域？（文物、医疗、金融、企业文档……）
2. **要解决的问题**：构建本体是为了做什么？（检索、问答、关联分析、可视化……）
3. **数据资源**：先调 `GET /api/llm/files` 获取已上传文件列表（name/path 绝对路径/size/ext/description/uploadedAt），与用户确认本次使用哪些文件；接口为空时请用户经后台管理页面上传后再继续。
4. **重点关注**：用户最关心哪些实体和关联？（决定概念层设计的粒度）

问完把问答落库（`phase=intake`，key 用稳定的英文标识，同 key 重问会覆盖）：

```bash
curl.exe -X POST "$ONTOLOGY_BASE_URL/api/llm/profile" -H "Content-Type: application/json" -d @profile.json
```

```json
{ "items": [
  { "phase": "intake", "key": "domain", "question": "什么领域？", "answer": "博物馆藏品" }
] }
```

回看历史问询：`GET /api/llm/profile`。

## Step 2 抽样读取与概念分析（phase=concept）

1. **先读 schema 上下文**：`GET /api/llm/schema`（若已有类，新概念须与存量对齐、避免重复建类）。
2. **部分读取数据文件**：从 `GET /api/llm/files` 返回的绝对路径用 Read/Grep 抽样（大文件先读前 100~200 行，再随机抽中段），不要一次全量读。
3. 从样本中分析并产出一份**概念设计方案**（先给用户过目确认，再写库）：
   - **概念（类）**：name（英文 PascalCase）/ cnName / layer（`concept` 概念层 | `instance` 实例层）/ keyProp（唯一键属性名，必填，建后不可改）/ description
   - **类的属性**：name / cnName / dataType / required / description
   - **概念间关系**：声明在**发起方（fromClass）一侧**，name（英文大写蛇形如 `SUB_ERA_OF`）/ cnName / toClass / example；关系自身的属性放 props
   - 实例层类（如 Artifact）通过关系指向概念层类（如 Era），这是产品推荐的分层结构
4. 分析结论存 profile（`phase=concept`，如 key=`concept_design`，answer 为方案摘要）。

## Step 3 概念层写入/修改

确认方案后写入（成功即自动建唯一约束并热更新白名单）：

```bash
# 新增类（含属性与出边声明）
curl.exe -X POST "$ONTOLOGY_BASE_URL/api/llm/schema/classes" -H "Content-Type: application/json" -d @class.json
```

```json
{
  "name": "Era", "cnName": "朝代", "layer": "concept", "keyProp": "name",
  "description": "历史朝代概念",
  "properties": [
    { "name": "name", "cnName": "朝代名", "dataType": "string", "required": true, "description": "唯一键" }
  ],
  "relations": [
    { "name": "SUB_ERA_OF", "cnName": "细分属于", "toClass": "Era", "example": "明永乐->明" }
  ]
}
```

- 修改：`PUT /api/llm/schema/classes/{name}`（整体替换属性与关系声明；name/keyProp 不可变）。
- 删除：`DELETE /api/llm/schema/classes/{name}`（被其他类引用或库中仍有实例 → 409，属预期保护）。
- 写完 `GET /api/llm/schema/classes` 核对全部类与 count。

## Step 4 实例层抽取规则（phase=instance）

基于已落库的概念，制定实例抽取规则（规则 + 逐条解析，不猜数据里没有的东西）：

- 每个实例类如何从用户文件映射：keyProp 取哪个字段、属性如何清洗（去 HTML、截断、多值按「、」拆分）
- 关系如何判定：字段直取 or 正则/规则抽取（写明正则），无法可靠判定的宁可不做
- 概念层实例（如 Era 的取值域）先收敛成词典，避免同义异写（"明"/"明代"）分裂节点
- 规则存 profile（`phase=instance`，如 key=`extract_rules`）。

## Step 5 批量写数据

先小批量试点（每个 label 先灌 20~50 条），核对无误再全量：

```bash
# 批量节点（items 每条必须含 keyProp；label 必须已在白名单）
curl.exe -X POST "$ONTOLOGY_BASE_URL/api/llm/graph/nodes/batch" -H "Content-Type: application/json" -d @nodes.json
```

```json
{ "label": "Era", "items": [ { "name": "明" }, { "name": "明永乐" } ] }
```

```json
# 批量关系（from/to 为两端 keyProp 值；两端节点须已存在）
{ "type": "SUB_ERA_OF", "fromLabel": "Era", "toLabel": "Era",
  "items": [ { "from": "明永乐", "to": "明" } ] }
```

- 单条兜底/补漏：`POST /api/llm/graph/nodes`、`POST /api/llm/graph/rels`；改属性 `PUT ...`。
- 建议顺序：先概念层实例节点 → 实例层节点 → 关系（两端节点就位后才能 MATCH 上）。
- PowerShell 注意：`-d` 含中文/特殊字符的 JSON 一律写临时文件用 `@file` 方式，避免转义问题。

## Step 6 对齐与校验（phase=validate）

概念层与实例层对齐，验证数据正确性：

1. **总量对账**：`GET /api/llm/graph/stats` — 各 label 节点数 / 各 type 关系数，与源文件条数、设计预期比对。
2. **抽查**：`GET /api/llm/graph/node/{label}/{key}`（含出入边）、`GET /api/llm/graph/neighbors?label=&key=&depth=1`，抽 3~5 个典型实例核对属性与连线。
3. **自定义只读校验**：`POST /api/llm/graph/query`（写语句会被 400 拦截，只能 SELECT 型）：

```json
{ "cypher": "MATCH (a:Artifact) WHERE a.era IS NULL RETURN count(a) AS missing" }
```

4. 发现问题：少数据 → 回 Step 5 补灌（幂等）；概念缺失/方向错 → 回 Step 3 改 schema 后重灌关系。
5. 校验结论存 profile（`phase=validate`，如 key=`final_report`，answer 含节点/关系总数与对账结果），最后向用户汇报构建摘要。

---

## 常见错误

| 现象 | 原因与处理 |
|---|---|
| 写入报 label/type 不在白名单 | 概念未先落库 → 回 Step 3；或 name 拼写与 schema 不一致 |
| 关系写入报签名不匹配 | from/to 方向与 schema 声明相反 → 调整方向或改声明 |
| DELETE class/节点 返回 409 | 删除保护：被引用或仍有实例 → 先清实例/关系 |
| query 返回 400 | 含写语句（CREATE/MERGE/DELETE…）→ 写操作走专用接口 |
| PowerShell curl 中文乱码/参数拆裂 | JSON 写临时文件用 `-d @file`；`-D` 参数值加引号 |
