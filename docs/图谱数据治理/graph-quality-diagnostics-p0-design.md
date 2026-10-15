# 图谱质量诊断模块（P0 阶段）设计与实现文档

> **版本**：v1.0
> **日期**：2026-09-20
> **落包**：`com.conca.ontology.quality`（顶级独立领域包）
> **页面入口**：`http://localhost:9876/data` → Tab「图谱诊断」
> **API 前缀**：`/api/graph/quality`（REST 只读）

---

## 一、背景与目标

项目本体（Ontology）由 LLM 抽取构建，数据写入 Neo4j。构建质量直接决定下游问答、推理、Agent 分析的可靠性。P0 阶段目标是**以最小代价，在不改写任何数据的前提下，快速发现 80% 的显性质量问题**，聚焦三类症状：

| 症状大类 | 为什么需要先修它 |
|---|---|
| 拓扑异常 | 孤立点/自环/出入度极端 hub = LLM 抽取漏关联、或过度关联的直接证据，是 QA "答非所问"的 Top 1 原因 |
| Schema 覆盖率低 | 声明的类/关系零实例 = 要么 Schema 过度设计、要么 LLM 提示词有漏洞漏抽取 |
| 分布极度不均 | 单类占比 >70%、某些关系边数为 0 = 后续图算法（PageRank/社区发现）输出结果完全不可靠 |

---

## 二、功能概览（4 大模块）

### 模块一：拓扑异常检测（Topology Anomaly）

覆盖 5 类异常：

| 异常类型 | Neo4j 5.x 诊断 Cypher 核心思路 | 业务意义 |
|---|---|---|
| ① 孤立节点 | `MATCH (n) WHERE NOT (n)--()` count + 明细 | 节点没有任何进出边 = LLM 抽了实体但没建立关联；或图谱清洗时误删关联 |
| ② 自环关系 | `MATCH (n)-[r]->(n)` | 节点指向自身的关系 = 几乎 100% 是抽取 bug（除极少数领域特定的自反关系） |
| ③ 死胡同边 | `MATCH (s)-[r]->(m)` + 两端是否还有延展边 | 起点无入边 / 终点无出边的连接 = 图谱局部"断头路"，多跳问答会在此失败 |
| ④ 入度异常 hub | 按 label 算 `avg/stdev`，`degree > avg + 3σ` Top 20 | 被过度关联的超级类：典型是通用属性节点（如"商代"被 1000+ 文物引用，属正常）vs. 幻觉 hub（LLM 把 80% 的关系都连到某个名字相似的实体） |
| ⑤ 出度异常 hub | 同上，方向相反 | 某节点指向 100+ 其他节点 = 可能是一个粗粒度的"分类总节点"、也可能是抽取时该节点的描述性文本被过度解析为关系 |

**统计假设**：
- 仅对**节点数 ≥ 50 的类**做 3σ 检测（小样本标准差无统计意义）
- 标准差为 0（全类度值完全相同）时退化为 `avg * 0.5` 作保守基线，避免除 0

### 模块二：Schema 覆盖率（Schema Coverage）

将 [graph-schema.json](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/graph-schema.json)（通过 `GraphSchema` 单例加载）与 `GraphQueryService.stats()` 返回的实际类/关系集合做集合差：

| 指标 | 计算方式 | 阈值参考 |
|---|---|---|
| 类覆盖率 | `有实例的类数 / GraphSchema.allClasses().size()` | ≥ 80% 绿；50-80% 橙；<50% 红 |
| 关系覆盖率 | `有实例的关系数 / GraphSchema.allRelations().size()` | 同上 |
| 未使用类明细 | 输出 class.name/cnName/layer/keyProp/description 全字段 | 供人工判断：删 Schema？还是补 Prompt？ |
| 未使用关系明细 | 输出 rel.type/cnName/fromClass/toClass/description/example | 同上 |

**补零特性**：直方图中「声明但零实例」的类/关系仍会出现（count=0），和「根本没声明过的项」在分布视图上严格区分。

### 模块三：节点类分布直方图

对每个 label 的节点数作降序柱状图，附带汇总统计：

- **min / max**：识别极端不平衡（如一个类 3000 节点，另一个类只有 1 节点）
- **avg**：平均每类节点数，对比 max 的比值可衡量分布偏度
- **median**：中位数，抗离群，比 avg 更能代表大多数类的规模
- **pctOfTotal**：每类占总节点的百分比（Tooltip + Table 双显）

### 模块四：关系类分布直方图

对每个关系类型的边数同上；Tooltip 额外显示 `fromClass → toClass` 签名，便于核对 Schema 定义。

---

## 三、后端实现（`com.conca.ontology.quality`）

### 3.1 领域包结构（严格遵循 `com.conca.ontology.user` 基准模板）

```
com.conca.ontology.quality/
├── api/
│   └── GraphQualityController.java          ← REST：5 个 GET
├── dto/
│   └── view/                                ← 全部读操作，无 request/result 子包
│       ├── QualityReportView.java           根 DTO：三大模块 + 全局 KPI
│       ├── TopologyAnomalyView.java         拓扑异常汇总 + 5 类明细
│       ├── IsolatedNodeView.java
│       ├── SelfLoopView.java
│       ├── DegreeAnomalyView.java           (degree + deviation σ 倍数)
│       ├── SchemaCoverageView.java          覆盖率 % + 未使用明细
│       ├── UnusedClassView.java
│       ├── UnusedRelationView.java
│       ├── ClassHistogramView.java + ClassBin.java
│       └── RelationHistogramView.java + RelBin.java
├── model/                                    (空：无需持久化实体)
└── service/
    └── GraphDiagnosticsService.java          构造 Cypher → GraphQueryService
```

**架构决策说明**：
- `quality` 设计为**顶级平级包**，而不是 `graph.quality` 子包——与 `disjoint` 等独立领域一致；Spring Bean 跨包注入 `GraphQueryService` + `GraphSchema` 无问题。
- 所有查询**统一经 `GraphQueryService.query()` 调用**，享受两条保护：
  1. `WRITE_RE` 正则拒绝任何写关键字（`CREATE/DELETE/SET/MERGE/REMOVE/DROP`），保证零侵入
  2. 复用同一个 Neo4j Session/连接池，不打开额外 driver

### 3.2 REST API 接口（GraphQualityController）

Swagger Tag：`Admin-GraphQuality`（[OpenApiConfig](file:///C:/me/kaiyuan/history/ontology-server/src/main/java/com/conca/ontology/shared/web/OpenApiConfig.java) packagesToScan 已追加 `com.conca.ontology.quality.api`）

| Method | Path | 说明 | 返回 |
|---|---|---|---|
| GET | `/api/graph/quality/report` | **全量报告**（页面首次加载用，单次往返） | `ApiResponse<QualityReportView>` |
| GET | `/api/graph/quality/topology` | 仅拓扑异常（手动刷新局部用） | `ApiResponse<TopologyAnomalyView>` |
| GET | `/api/graph/quality/coverage` | 仅 Schema 覆盖率 | `ApiResponse<SchemaCoverageView>` |
| GET | `/api/graph/quality/histogram/nodes` | 仅节点类分布 | `ApiResponse<ClassHistogramView>` |
| GET | `/api/graph/quality/histogram/relations` | 仅关系类分布 | `ApiResponse<RelationHistogramView>` |

### 3.3 Neo4j 5.x 语法踩坑总结

Neo4j 5.0 引入两处不兼容变更（所有 `size(模式表达式)` 写法全部在运行时炸）：

| Neo4j 4.x 旧写法 | Neo4j 5.x 新写法 | 触发文件 |
|---|---|---|
| `size((n)-[]->())` | `COUNT { (n)-[]->() }` | `countDangling / degreeStats / degreeAnomalies / countAnomalies` 共 4 处 |
| `WHERE count > $threshold`（$threshold 是 Double） | `WHERE count > toInteger($threshold)`（显式类型匹配） | `degreeAnomalies / countAnomalies` 2 处 |
| `size(()-[]->(startNode(r)))`（嵌套函数引用） | 先在 MATCH/WITH 阶段绑定变量 `(s)-[r]->(m)`，对 `s/m` 变量做 COUNT | `countDangling` |

另外在 `degreeStats` 中 `COUNT { }` 返回 INTEGER，求 avg/stdev 时先 `toFloat(outDeg)` 包一层，避免 Neo4j 5.x 在某些版本下对整数统计隐式截断导致 σ 偏差。

### 3.4 异常节点跳转联动

所有带 `(label, keyValue)` 字段的明细行（IsolatedNode / DegreeAnomaly / SelfLoop），后端只返回数据；前端生成跳转按钮：

```
/graph?label=<label>&key=<keyValue>
```

复用现有 `graph.html` 参数入口（无需新页面），可直接定位到具体节点观察上下文关联。

---

## 四、前端实现（data.html「图谱诊断」Tab）

### 4.1 入口

原页面 Tab 从 5 项变为 6 项（Tab 按钮在 `/data` 顶部）：

```
[图数据] [概念层] [互斥约束] [内容安全配置] [语义改写配置]  **【图谱诊断】** ← 新增
```

**懒加载策略**（`switchTab('quality')`）：
- 首次切入才调 `gqRefresh()` 拉取数据；之前不占用后端资源。
- 切入后 `setTimeout(50ms)` 触发 echarts `resize()`——因为容器 `<div>` 先前 `display:none`，宽度 0，必须恢复显示后重算尺寸（对应 Lessons Learned："折叠容器内图表需展开后触发"）。

### 4.2 样式隔离（gq- 前缀）

遵循项目 Hard Constraint："报告类模块样式必须使用命名前缀以实现隔离"。
本模块所有自定义类以 `gq-` 开头，绝不污染 `card/container/btn` 等公共类。部分关键类：

```
.gq-kpi-grid        KPI 6 卡片网格
.gq-kpi.{danger|warn|good}   异常级别着色
.gq-progress        覆盖率进度条（颜色按 % 三段渐变）
.gq-chart-box       echarts 容器外框
.gq-degree-row      入/出度异常行（含横向进度条 + σ 倍数红字）
```

### 4.3 图表库选型：echarts（零新增依赖）

项目 `/vendor` 已有 `echarts.min.js`（[vendor 目录](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/static/vendor)），直接引入：

```html
<script src="/vendor/echarts.min.js"></script>
```

**销毁重建机制**（严格遵循 Lessons Learned："图表切换需清理旧实例以防止内存泄漏"）：

```javascript
let gqChartClasses = null, gqChartRels = null;
function gqRenderClassHistogram(h) {
  if (gqChartClasses) { gqChartClasses.dispose(); gqChartClasses = null; }
  gqChartClasses = echarts.init($('gq-chart-classes'));
  gqChartClasses.setOption(option);
}
```

每次 `gqRefresh()` 都会先 dispose 旧实例 → 再 init，确保反复点击刷新按钮不会堆积 10+ 个 echarts 实例。

### 4.4 前端模块数据流（单函数主入口）

```
gqRefresh()
└── GET /api/graph/quality/report → resp.data
    ├── gqRenderKpi(r)            6 张 KPI 卡片（着色规则：异常>0 红，否则绿）
    ├── gqRenderTopology(r.topology)
    │     ├── 5 小格统计（非0红字）
    │     ├── gqDegreeBarList() × 2   入/出度横向进度条（σ≥5 标红 hot）
    │     └── 孤立点 + 自环表格（复用项目 table() 工具函数）
    ├── gqRenderCoverage(r.coverage)
    │     ├── gqSetProgress() × 2      类/关系 覆盖进度条
    │     └── 未使用类 + 未使用关系表格
    ├── gqRenderClassHistogram()  echarts 垂直柱状（ToolTip 显示 label + layer + %）
    └── gqRenderRelHistogram()    echarts 垂直柱状（ToolTip 显示签名 from→to + %）
```

**颜色方案**：`GQ_PALETTE` 12 色轮询（与 PALETTE 色板同源但独立变量，方便本模块日后微调不影响其他 Tab）。

### 4.5 页面布局（自上而下）

```
┌─ 诊断工具栏卡 ────────────────────────────────────────┐
│  标题「图谱质量诊断」 + 生成时间 + ↻ 刷新诊断按钮    │
├─ KPI 卡 ──────────────────────────────────────────────┤
│  [节点][关系][拓扑异常][未使用类][未使用关系][类覆盖] │
├─ 一、拓扑异常卡 ──────────────────────────────────────┤
│  5 异常 stat box                                      │
│  ├ 孤立点表（前500/总数，含→查看节点）                │
│  ├ 自环关系表（全量）                                 │
│  ├ 入度异常 Top20（横向进度条 + σ标红 + →查看）       │
│  └ 出度异常 Top20（同上）                             │
├─ 二、Schema 覆盖率卡 ─────────────────────────────────┤
│  类覆盖进度条 / 关系覆盖进度条                        │
│  ├ 未使用类定义表                                     │
│  └ 未使用关系定义表                                   │
└─ 三、分布直方图卡 ────────────────────────────────────┘
   ├ 节点类分布（min/avg/median/max stat + echarts bar）
   └ 关系类分布（同上）
```

---

## 五、关键文件清单（点击跳转）

### 后端

| 文件 | 作用 |
|---|---|
| [GraphQualityController.java](file:///C:/me/kaiyuan/history/ontology-server/src/main/java/com/conca/ontology/quality/api/GraphQualityController.java) | REST 入口，5×GET + Swagger Tag |
| [GraphDiagnosticsService.java](file:///C:/me/kaiyuan/history/ontology-server/src/main/java/com/conca/ontology/quality/service/GraphDiagnosticsService.java) | 核心业务：Cypher 构造 + 集合差算法 + 统计后处理 |
| [quality/dto/view/](file:///C:/me/kaiyuan/history/ontology-server/src/main/java/com/conca/ontology/quality/dto/view) | 10 个 View Records（不可变 DTO） |
| [OpenApiConfig.java#L43](file:///C:/me/kaiyuan/history/ontology-server/src/main/java/com/conca/ontology/shared/web/OpenApiConfig.java#L43) | 已追加 `com.conca.ontology.quality.api` 到 packagesToScan |

### 前端

| 文件 | 作用 |
|---|---|
| [data.html 样式区](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/static/pages/data.html#L111-L155) | `gq-*` 样式前缀样式块 |
| [data.html Tab 栏](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/static/pages/data.html#L209-L211) | 新增「图谱诊断」Tab 按钮 |
| [data.html Tab 容器](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/static/pages/data.html#L212-L295) | `tab-quality` 4 模块 HTML 结构骨架 |
| [data.html switchTab](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/static/pages/data.html#L565-L570) | quality 懒加载 + echarts resize |
| [data.html 图谱诊断 JS 模块](file:///C:/me/kaiyuan/history/ontology-server/src/main/resources/static/pages/data.html#L1160-L1430) | `gqRefresh/gqRender*` 渲染主流程 |

---

## 六、验证与验收

### 6.1 编译验证

```powershell
C:\me\kaiyuan\history\llmOntology\mvnw.cmd clean compile -pl ontology-server -am
# 结果：BUILD SUCCESS（Compiling 212 source files，无新增 ERROR）
```

### 6.2 运行时冒烟用例

启动后端后按顺序执行，任一失败 = 需回滚：

| 编号 | 操作 | 期望结果 |
|---|---|---|
| ① | GET `/api/graph/quality/report` 返回 HTTP 200 | JSON 含 topology/coverage/classHistogram/relHistogram 4 键，且 generatedAtMs 为当前时间 |
| ② | 无节点时（空图） | Topology 5 指标 = 0，bins 里出现所有声明类（count=0） |
| ③ | 图中人为创建一个孤立节点（无任何关系） | `isolatedCount ≥ 1`，isolatedTop 中出现该节点；点「查看节点」→ graph.html 正确定位 |
| ④ | 人为创建 `(n)-[r:TEST]->(n)` 自环 | `selfLoopCount = 1`，自环表显示 relType=TEST |
| ⑤ | 某 label 人为创建 51 个节点，其中 1 个连 200 条边，其它 1 条边 | 3σ 检测命中 → inDegreeAnomalyCount/outDegreeAnomalyCount ≥ 1 |
| ⑥ | 切 Tab → 点「图谱诊断」→ ↻ 反复刷新 10 次 | 浏览器 DevTools Memory 无持续上涨（echarts dispose 生效） |
| ⑦ | Swagger UI 点开 `Admin-GraphQuality` | 5 个接口全部列出，Try it out 均能执行 |

### 6.3 已知限制（P1 阶段再解决）

1. **大图性能**：`degreeStats` 对全图 MATCH (n)，千万节点级需加索引并考虑 APOC 周期性统计（当前方案在万-十万节点量级正常）
2. **孤立点明细 LIMIT 500**：不是全量（避免网络传输爆）；如需全量导出，P1 加分页接口
3. **语义级去重**：当前仅在拓扑层找孤立点，**同义异名实体重复节点**（如"三星堆" vs "三星堆遗址"）P0 不检测——这是 P1 确定性去重算法的职责
4. **Disjoint 图级校验**：当前仅 DisjointRuleService 对单段文本做字符串检测，未对全图扫描节点是否同时属于 disjoint 两类——P1 补

---

## 七、Roadmap（P1/P2 后续方向）

| 阶段 | 功能 | 价值 / 对应学术方案 |
|---|---|---|
| **P1** | 6 种确定性去重流水线（规范化别名 + MinHash LSH + Jaro-Winkler + 阻塞分面 + Sorted Neighborhood + 精确 key 归一化） | 对应 arXiv 2607.28662，零 LLM 推理成本解决 80%+ 显性重复 |
| **P1** | 嵌入语义消歧（BGE-M3）+ 属性冲突保护（年代/地点冲突禁止合并） | 对应 Wikontic 2025 Conflict Guard 思想 |
| **P1** | Disjoint 规则全图扫描校验 | 解决「某节点同时标记为 disjoint 两类」→ 类定义冲突 |
| **P2** | LLM CoVe 二阶校验（抽取后走 Chain-of-Verification 自审计） | 对应 ACL Findings 2025，精度 +10-13% |
| **P2** | 四模型一致同意过滤（4 LLM 独立抽取） | 对应 Frontiers in Immunology 2026 Unanimity 高置信过滤 |
| **P2** | OWL TBox 推理（Openllet） | 概念循环、类不交冲突、属性域/范围矛盾检测 |
| **P2** | 金标准回归集（150 条人工双标注） | Eurecom ISWC2024 对照法，每次抽取回归 Precision/Recall/F1 |

---

*文档终*
