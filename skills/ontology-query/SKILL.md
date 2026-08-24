---
name: "ontology-query"
description: "Queries ontology schema first, then generates read-only Cypher and queries graph evidence. If graph evidence is insufficient, falls back to uploaded-file search. Invoke when user asks about ontology classes, relations, properties, instances, counts, or graph facts."
---

# Ontology Query（本体查询）

这个 skill 用于回答本体里的信息查询问题，覆盖：

- 概念层：类、关系、属性、节点类型、schema 结构
- 实例层：某个实体是否存在、属于什么类别/年代/材质/主题、数量统计、样例列表
- 文件兜底：当图谱无结果或证据不足时，从已上传文件中搜索补充证据
- 构建背景：当问题属于构建背景且图谱无法回答时，回查 profile

## 专用工具（优先使用）

HTTP 查询一律通过已注册的原生工具完成，**禁止用 execute 手写 curl**：

| 用途 | 工具 |
|---|---|
| 概念层总览 / 类详情 / 关系清单 | `ontology_schema(action, className)` |
| 只读 Cypher 图谱查询 | `graph_query(question, cypher, paramsJson)` |
| 自然语言一步直问 | `ontology_ask(question)` |
| 已上传文件列表 | `list_uploaded_files()` |
| 构建背景 profile | `get_profile(field)` |

工具返回错误时按前缀判层：

- `[连接失败]`：本体服务未启动。如实告知用户服务不可用，**不要**反复重试，更不要误报为"查询无结果"
- `[参数错误]`：本地校验拒绝（如写语句、缺参数）。修正参数后重试
- `[服务错误]`：服务端 4xx/5xx。向用户透传说明
- 工具连续 2 次失败 → 熔断：停止盲试，按前缀分层上报

## 固定执行主线

除非用户明确要求只查文件或只看构建背景，否则严格按以下顺序执行，不要跳步：

1. `ontology_schema(action="all")` 查询概念层，理解类、属性、关系
2. 根据 schema 对齐用户问题中的类名、属性名、关系名、keyProp
3. 组装只读 Cypher（label/property/relation 必须来自 schema 结果；默认写 LIMIT）
4. `graph_query(question, cypher, paramsJson)` 执行查询（无参数时 paramsJson 传空串）
5. 图谱结果为空或证据不足 → `list_uploaded_files()`，按 name/description 挑候选文件，再用 `grep_files` 搜关键词、`read_file` 读命中附近内容
6. 文件仍不能回答 → 若属构建背景可用 `get_profile(field)` 兜底，否则明确说明未查询到

禁止：

- 跳过 schema 直接猜类名、属性名、关系名
- 生成写语句（CREATE/MERGE/DELETE/SET/CALL 等，会被 `[参数错误]` 拒绝）
- 把项目目录中的任意文件当作"已上传文件证据"
- 没有证据就给出猜测性答案

## Cypher 组装规则

- 只允许 `MATCH` / `OPTIONAL MATCH` / `WHERE` / `WITH` / `UNWIND` / `RETURN` / `ORDER BY` / `LIMIT`
- 优先精确查，必要时再模糊查（CONTAINS）
- 默认加 LIMIT；未写时工具会自动追加 LIMIT 100
- 不确定类名/属性名时回到 `ontology_schema` 确认，不要臆造

## 文件兜底

只有 `list_uploaded_files()` 返回的文件才是 file 证据：

1. 按 `description`、`name` 与用户问题相关度挑选候选文件
2. 用 `grep_files` 搜索关键词定位
3. 命中后用 `read_file` 的 offset/limit 分页读取，禁止大文件整读
4. 引用时注明文件名与具体片段

## 回答组织

- 先给结论
- 再给证据来源：`graph`（Neo4j 图谱）/ `file`（已上传文件）/ `profile` / `none`
- 未查到时明确说明"当前本体中不存在该信息"或"当前图谱未查询到相关结果"

回答模板参考：

```text
结论：...
证据来源：graph
图谱证据：...
```

未查到模板：

```text
结论：当前本体中不存在该信息。
证据来源：none
说明：已检查 schema、图谱结果与文件兜底，未命中相关信息。
```
