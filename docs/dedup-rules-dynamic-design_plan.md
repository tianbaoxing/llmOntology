# 实体去重规则动态化设计方案（最终版 · 基于用户决策）

> **用户决策锁定**：
> ① 存储 = JSON 单文件（与现有 disjoint/content-security 同模式）
> ② 保存 = 整体覆盖 POST
> ③ 用户可自定义 `{label: suffixes[]}`（标签-内容表，不限于 Place/Era）
> ④ **核心**：扫描时支持选择多套规则集，不写死一套；默认内置「考古行业默认」一套，用户可新建任意多套
> ⑤ **入口**：实体去重 Tab 内放链接「📝 编辑规则配置」→ 跳转新页面 `dedup-rules.html`（不在 data.html 内嵌面板）
> ⑥ 含「🧪 预览」按钮
> ⑦ 新行业从零手动配（不做 Profile 模板机制）

---

## 一、现状清单（= 改造目标 = 10 处硬编码点清零）

### 1.1 后端写死位置

| # | 文件 | 行 | 硬编码内容 |
|---|------|----|-----------|
| 1 | [NormalizeAliasStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/NormalizeAliasStage.java) | L35-L39 | `PLACE_SUFFIXES[22]` 静态数组 |
| 2 | [NormalizeAliasStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/NormalizeAliasStage.java) | L40-L42 | `ERA_SUFFIXES[7]` 静态数组 |
| 3 | [NormalizeAliasStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/NormalizeAliasStage.java) | L92-L99 | `suffixesFor()` 用 `switch(label)` 只认 Place/Era |
| 4 | [ExactKeyStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/ExactKeyStage.java) | L55-L58 | `if ("CulturalRelic".equals(label))` 特判执行模式 ②③ |
| 5 | [ExactKeyStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/ExactKeyStage.java) | L105 | 文物尺寸 `DIMS[7]` 写死 |
| 6 | [ExactKeyStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/ExactKeyStage.java) | L137-L145 | `auxFingerprint()` 硬编码 4 label 辅助属性 switch |
| 7 | [DedupScanService.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/DedupScanService.java) | L170 | `CulturalRelic → title` nameProp 硬判 |
| 8 | [DedupScanService.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/DedupScanService.java) | L357 | 冲突检测 `CRITICAL[6]` 写死 |

### 1.2 前端写死位置

| # | 文件 | 行 | 硬编码内容 |
|---|------|----|-----------|
| 9 | [data.html](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/resources/static/pages/data.html) | L747 | 快速步骤中「Place 18 后缀 / Era 7 后缀」文案 |
| 10 | [data.html](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/resources/static/pages/data.html) | L758-L791 | 原理说明 4 张静态卡片（后缀表 / 辅助属性 / 尺寸维度 / 冲突属性）全部为静态 HTML |

---

## 二、数据结构设计

### 2.1 文件组织（多套规则集 = 多 JSON 文件 + 目录索引）

```
ontology-server/files/dedup/
├── rules/
│   ├── archaeology-default.json   ← 内置（builtin=true），与当前写死值 100% 一致
│   ├── custom-medical-001.json    ← 用户自建（示例，未实现前不存在）
│   └── custom-legal-001.json      ← 用户自建（示例）
└── rules-index.json               ← 自动维护：所有规则集的 id / name / builtin / version / updatedAt 清单
```

> 与现有风格一致：Disjoint 存 `files/disjoint-rules.json`，Content-Security 存 `files/content-security-*-rules.json`，这里建独立 `files/dedup/` 子目录避免与其他配置混放。

### 2.2 规则集 JSON 单文件结构（`archaeology-default.json` 示例）

```json
{
  "id": "archaeology-default",
  "name": "考古行业默认",
  "builtin": true,
  "version": 1,
  "updatedAt": 1758355200000,

  "suffixTable": {
    "Place": ["遗址博物馆","遗址公园","博物馆","考古遗址","墓群","墓葬","墓地","风景区","景区","旅游区","自治区","自治州","自治县","特别行政区","省","市","区","县","镇","乡","村","遗址"],
    "Era":   ["年间","时期","时代","王朝","朝代","代","朝"]
  },

  "labelConfig": {
    "Place": {
      "nameProp": "name",
      "auxProps": ["province", "kind"],
      "exactModes": ["NAME_AUX"],
      "dimProps": [],
      "conflictProps": ["site", "province"]
    },
    "HistoricalFigure": {
      "nameProp": "name",
      "auxProps": ["dynastyLabel", "identity"],
      "exactModes": ["NAME_AUX"],
      "dimProps": [],
      "conflictProps": ["dynastyLabel"]
    },
    "HistoricalEvent": {
      "nameProp": "name",
      "auxProps": ["startYear", "endYear"],
      "exactModes": ["NAME_AUX"],
      "dimProps": [],
      "conflictProps": ["startYear", "endYear", "era"]
    },
    "AncientText": {
      "nameProp": "name",
      "auxProps": ["category"],
      "exactModes": ["NAME_AUX"],
      "dimProps": [],
      "conflictProps": ["era", "dynastyLabel"]
    },
    "CulturalRelic": {
      "nameProp": "title",
      "auxProps": [],
      "exactModes": ["TITLE_ONLY", "TITLE_DIMS"],
      "dimProps": ["heightCm","widthCm","lengthCm","boreCm","footCm","diameterCm","weightG"],
      "conflictProps": ["era", "site", "province", "dynastyLabel"]
    }
  },

  "globalConflictProps": ["era", "site", "province", "dynastyLabel", "startYear", "endYear"]
}
```

**字段语义（对应三问）**：

| 字段 | 对应问题 | 说明 |
|------|---------|------|
| `suffixTable: Map<label, List<String>>` | **问题 1** | 核心「标签-内容」动态表：`{Place: [省,市,...], Era: [朝,代,...]}`。用户可自定义任意 label 后缀，未配置 label 不剥离（仅基础规范化）。 |
| `labelConfig: Map<label, 单类配置>` | **问题 2** | key 为任意 label，不再写死 Place/Era/CulturalRelic。换行业直接加新 key，不改 Java 代码。 |
| `exactModes: NAME_AUX / TITLE_ONLY / TITLE_DIMS` 枚举 | 问题 2 | 每类精确匹配模式自由组合，消灭 `CulturalRelic` if 特判。 |
| `id / name / builtin` | **问题 3（多套规则集）** | 规则集唯一 ID + 展示名；builtin=true 的不可删（防止默认集丢失）；用户可选多套。 |

### 2.3 rules-index.json 结构（自动生成，用户无需手改）

```json
[
  { "id": "archaeology-default", "name": "考古行业默认", "builtin": true,  "version": 1, "updatedAt": 1758355200000, "active": true },
  { "id": "custom-med-001",      "name": "医疗行业 v1",   "builtin": false, "version": 3, "updatedAt": 1758441600000, "active": false }
]
```

- `active: true` = 当前默认激活的规则集（data.html 扫描时下拉的默认选中项）。同一时刻仅一套 active，用户可切换。

### 2.4 Java Record / Enum 模型

```
com.conca.ontology.dedup.model
├── DedupRuleset.java          Record: id / name / builtin / version / updatedAt + suffixTable / labelConfig / globalConflictProps
├── DedupLabelConfig.java      Record: nameProp / auxProps / exactModes / dimProps / conflictProps
├── DedupExactMode.java        Enum: NAME_AUX, TITLE_ONLY, TITLE_DIMS
├── DedupRulesIndexItem.java   Record: id / name / builtin / version / updatedAt / active（索引条目）
└── DedupRulesFileStore.java   负责：目录扫描 + 索引读写 + 单规则集 JSON 读写
        （参考现有 DisjointRuleFile 相同模式：FileSystem 热读 + ObjectMapper 序列化）
```

---

## 三、后端改造

### 3.1 新增文件清单

| 路径 | 职责 |
|------|------|
| `dedup/model/*.java`（5 个） | 见 2.4 |
| `dedup/service/DedupRulesService.java` | 规则集 CRUD + Schema 校验 + 扫描快照 + 预览逻辑。对外 API：`listAll()` / `get(id)` / `getActive()` / `setActive(id)` / `save(newSet)` / `delete(id)` / `preview(临时规则, 样例文本)` |
| `dedup/api/DedupRulesController.java` | 规则配置 REST 接口（6 个），服务于 `dedup-rules.html` 新页面 |

### 3.2 新增 REST 接口（全部 `/api/graph/dedup/*` 前缀，符合项目约定）

| 方法 | 路径 | 输入 / 输出 | 作用 |
|------|------|------------|------|
| `GET` | `/rules` | → `List<DedupRulesIndexItem>` | **列表**：返回所有规则集（含 id/name/builtin/version/active），用于 data.html 工具栏下拉 + 配置页左侧列表。 |
| `GET` | `/rules/{id}` | → `DedupRuleset` 完整 JSON | **详情**：点开某套规则的完整内容，用于配置页右侧编辑区初始化。 |
| `POST` | `/rules` | ← `DedupRuleset` (id 空=新建，id 有=覆盖) → `DedupRulesIndexItem` | **保存**：整体覆盖写，version++，保存前做 Schema 属性名校验（拼错属性名返回 400 + 可选值清单）。 |
| `POST` | `/rules/{id}/activate` | → `{activeId: ...}` | **切换激活**：设某套为 active（= data.html 扫描下拉默认值）；builtin 可被取消 active，非 builtin 都能。 |
| `DELETE` | `/rules/{id}` | → `{deleted: true}` | **删除**：仅非 builtin 可删；builtin 返回 403 + 明确原因。 |
| `POST` | `/rules/preview` | ← `{rules: DedupRuleset, samples: [{label, name1, name2}]}` → `{normalized: [...], hitGroup: true/false, subStages: [...]}` | **预览**：提交一份临时规则 + 若干样例字符串，返回「剥离哪些后缀 → 生成哪些 normKey；两字符串是否命中同一候选组及哪些子策略」。 |

### 3.3 改造现有文件

#### 3.3.1 NormalizeAliasStage.java（= 解决问题 1）
- 删除静态 `PLACE_SUFFIXES` / `ERA_SUFFIXES` 数组
- 删除 `suffixesFor()` 的 `switch(label)`
- **构造函数改签名**：不再 `@Component` 直接调静态数组，而改为**扫描快照传参**（见 3.3.4），或由 `DedupScanService` 在每次 scan 时把本次用的 `DedupRuleset` 快照传入

```java
// 改造后 suffixesFor 不再查 service，直接从快照取：
List<String> suffixesFor(String label, DedupRuleset rules) {
    List<String> list = rules.suffixTable().get(label);
    return list == null ? List.of() : list;
}
```

#### 3.3.2 ExactKeyStage.java（= 解决问题 2）
- 删除 `DIMS` 静态数组
- 删除 `auxFingerprint()` 的 `switch(label)`
- 删除 `if ("CulturalRelic".equals(label))` 特判
- 改：`run(List<NodeProps> nodes, DedupRuleset rules)` 每次扫描把快照传入
  - `cfg = rules.labelConfig().get(label)` 取当前类配置
  - 遍历 `cfg.exactModes()` 动态决定执行哪些模式
  - 辅助属性与尺寸维度均从 cfg 取

#### 3.3.3 DedupScanService.java（= 解决问题 2 + 问题 3 多套规则选择）
- 删除 L170 CulturalRelic nameProp 硬判 → 取 `cfg.nameProp()`
- 删除 L357 CRITICAL 数组 → 冲突检测属性 = `LinkedHashSet( globalConflictProps ∪ cfg.conflictProps() )`（并集去重）
- **scan 入口新增规则集选择**：
  - `DedupScanRequest` 新增字段 `ruleSetId`（可选，默认=当前 active 的 id）
  - `scan()` 第一行：`DedupRuleset rules = rulesService.getOrDefault(req.ruleSetId())`，取出本次用的不可变快照，传入两个 Stage（跨线程安全：本次 scan 期间 rules 引用不变，即便用户并发 save 新规则也不影响正在跑的扫描）

#### 3.3.4 DedupScanRequest.java
新增字段：
```java
@Schema(description = "使用的规则集 ID；空=使用当前 active 规则集", example = "archaeology-default") String ruleSetId
```
- 其余字段保持不变，100% 向后兼容。

#### 3.3.5 GraphDedupController.java
- 现有 `POST/GET /scan` 保持不变，`DedupScanRequest` 新增字段自动生效
- 新增 `/rules*` 6 个接口在独立 `DedupRulesController`，packagesToScan 已含 `dedup.api`，无需改 OpenApiConfig（遵循 Hard Constraint）

---

## 四、前端改造（= 解决问题 3）

### 4.1 页面架构变化

**不再在 data.html 内嵌折叠面板**，改为：

```
pages/
├── data.html              （实体去重 Tab 改造：工具栏增加规则集下拉 + 链接跳转）
└── dedup-rules.html       （新增：独立规则配置页，全新 UI）
```

### 4.2 data.html 实体去重 Tab 改动（3 处）

#### 改动 1：Layer 0 工具栏新增「规则集」下拉

位于 `Label` 下拉左侧：
```
┌───────────────────────────────────────────────────────────────────────┐
│ 🧠 实体去重（M1 里程碑 · 确定性去重）                                   │
│ ┌──────────┐ ┌──────────┐ ┌──────┐ ┌──────┐  扫描时间：-   [🔍 扫描]  │
│ │规则集：▼ │ │Label：▼  │ │阈值：│ │最多：│                         │
│ │考古行业  │ │全部       │ │0.95  │ │0     │   [📝 编辑规则配置 →]    │
│ └──────────┘ └──────────┘ └──────┘ └──────┘                         │
└───────────────────────────────────────────────────────────────────────┘
```
- 规则集下拉：数据来自 `GET /api/graph/dedup/rules` 索引列表；显示 `name（v{version}）`，`archaeology-default` 后附 🔒 锁图标表示内置不可删
- 切换下拉 → 点击扫描时，`POST /api/graph/dedup/scan` 的 body 里带 `ruleSetId: 选中的id`
- **📝 编辑规则配置 →**：新链接，点击 `window.location.href = '/pages/dedup-rules.html'`，跳独立页面

#### 改动 2：Layer 1 原理说明 4 卡片 → 动态渲染

- 删除 L758-L791 的静态 HTML
- 进入 dedup Tab 时：
  1. 调 `GET /api/graph/dedup/rules/{当前下拉选中的id}` 取完整规则
  2. JS 遍历 `suffixTable` 生成卡片 ①（每行 label + 后缀数量 + 完整列表；未配置 = 标注「仅基础规范化」）
  3. 遍历 `labelConfig` 生成卡片 ②（每行 label + 模式枚举翻译中文 + auxProps/dimProps 列表）
  4. 若存在类配置了 `TITLE_DIMS` 模式，显示卡片 ③（尺寸维度列表）
  5. 卡片 ④ = `去重(所有类 conflictProps + globalConflictProps)` 汇总展示

#### 改动 3：快速步骤说明文案（L747）改 JS 模板字符串动态生成

- 不再写死「Place 18 后缀 / Era 7 后缀」，改为：从 suffixTable 统计总类数与总后缀数，动态渲染为「当前规则集：X 个 label 共 Y 项后缀；精确匹配模式含 NAME_AUX/Z 个类、TITLE_ONLY/Z 个类、TITLE_DIMS/Z 个类」

### 4.3 新增页面 `dedup-rules.html`（独立规则配置页 = 用户说的「跳转新页面」）

#### 4.3.1 页面整体布局（左右分栏）

```
┌─ 图谱管理 ─ 实体去重规则配置 ───────────────────────────────────────── [← 返回 data.html]
│
│ ┌─ 左侧：规则集列表（宽 280px） ──────────┐ ┌─ 右侧：编辑区 ────────────────────┐
│ │ [➕ 新建空白规则集]                      │ │ 当前规则集：考古行业默认 🔒 v1       │
│ │                                          │ │                                    │
│ │ 🔘 考古行业默认 🔒 (active)  [编辑]     │ │ 基本信息：                          │
│ │ ☐ 医疗行业 v1           v3    [编辑][🗑]│ │  名称：[考古行业默认________]       │
│ │ ☐ 医疗行业 v2           v1    [编辑][🗑]│ │                                    │
│ │                                          │ │ ┌─ 全局冲突检测属性 ─────────────┐ │
│ └──────────────────────────────────────────┘ │ │ [era ×][site ×][province ×]... │ │
│                                              │ │ [➕ 添加全局冲突属性]            │ │
│                                              │ └────────────────────────────────┘ │
│                                              │                                    │
│                                              │ ┌─ Label 配置列表 ──────────────┐ │
│                                              │ │ [Place ✏️] [Era ✏️] [Cult...] │ │
│                                              │ │ [➕ 新增 Label 配置]            │ │
│                                              │ └────────────────────────────────┘ │
│                                              │                                    │
│                                              │ 当前编辑 Label：Place               │
│                                              │ ┌────────────────────────────────┐ │
│                                              │ │ name 属性源：[name ▼]           │ │
│                                              │ │ Normalize 后缀表：               │ │
│                                              │ │ [遗址博物馆×][遗址公园×]...[➕]  │ │
│                                              │ │ 精确模式：☑ NAME_AUX (辅助属性:) │ │
│                                              │ │   [province ×][kind ×][➕]      │ │
│                                              │ │ 冲突属性：[site ×][province×][➕]│ │
│                                              │ └────────────────────────────────┘ │
│                                              │                                    │
│                                              │ [💾 保存]  [🧪 预览]  [↩️ 撤销修改] │
│                                              └────────────────────────────────────┘
│
└ 图例：🔘=active 当前默认  🔒=builtin 不可删  🗑=仅非 builtin 显示
```

#### 4.3.2 关键交互说明

1. **新建空白规则集**：
   - 弹窗输入名称 → 后端生成 UUID 作为 id，创建一份空 JSON（suffixTable={}, labelConfig={}, globalConflictProps=[]），返回后自动进入编辑。用户从零手动配。
2. **➕ 新增 Label 配置**：
   - 下拉 = 当前 `GraphSchema` 中所有实例层 label 名（复用已有 schema 接口）。选中后，创建默认配置：`nameProp=该类 keyProp`，其余字段为空数组。
3. **name 属性源下拉**：
   - = `GraphSchema.classOf(label).properties()` 全部属性。选 nameProp 后，若其值不为 title/title 类语义，`TITLE_ONLY/TITLE_DIMS` 复选框灰态+tooltip 提示。
4. **🧪 预览按钮**：
   - 弹出对话框，输入「测试 Label（默认 Place）」「样例 1（如西安市博物馆）」「样例 2（如西安）」→ 调 `POST /api/graph/dedup/rules/preview` → 返回结果以 step-by-step 方式展示：
     ```
     ✅ 两字符串将命中同一候选组
     ├── 规范化步骤：样例1「西安市博物馆」→ 基础规范化 → 剥离后缀「博物馆」得「西安市」→ 再剥离「市」得「西安」（共 3 个 normKey）
     ├── 规范化步骤：样例2「西安」→ 仅有 1 个 normKey：「西安」
     └── 命中子策略：normalizeAlias（normKey=西安）
     ```
5. **💾 保存按钮**：
   - 整体 `POST /api/graph/dedup/rules` 覆盖保存；成功后若 name 变更 → 自动刷新左侧列表；若 builtin 规则被修改 → name 字段可改但 id/builtin 仍不可删（前端灰态删除按钮）。
6. **删除按钮**：
   - 仅非 builtin 显示；点击二次确认 → `DELETE /api/graph/dedup/rules/{id}` → 成功后左侧列表刷新，若被删的是 active 集 → 自动把 active 切回 archaeology-default。
7. **↩️ 撤销修改**：
   - 重新 `GET /api/graph/dedup/rules/{id}` 拉最新版本覆盖前端编辑状态。

---

## 五、实现步骤（依赖顺序 = 12 步）

| # | 步骤 | 产出 | 对应问题 |
|---|------|------|---------|
| 1 | 新建 5 个 model Record/Enum：DedupRuleset / DedupLabelConfig / DedupExactMode / DedupRulesIndexItem / DedupRulesFileStore | 数据层就绪；FileStore 含种子 archaeology-default.json 首次启动自动初始化 | 1 + 2 |
| 2 | 新建 DedupRulesService：listAll / get / getActive / setActive / save / delete / preview；save 含 Schema 属性名校验；扫描快照 CopyOnRead | 规则服务就绪 | 全部 |
| 3 | 新建 DedupRulesController：6 个 REST 接口在 Swagger 可直接调通 | 后端接口就绪 | 3 |
| 4 | 改造 NormalizeAliasStage：删静态数组 + switch，run 签名加 DedupRuleset 快照参数 | 后缀表动态化 | 1 |
| 5 | 改造 ExactKeyStage：删 DIMS / auxFingerprint switch / CulturalRelic if 特判；run 签名加 DedupRuleset 快照参数 | 精确匹配动态化 | 2 |
| 6 | 改造 DedupScanService + DedupScanRequest：新增 ruleSetId 字段；nameProp + 冲突属性从 rules 快照取；scan 入口 CopyOnRead | 后端所有硬编码清零；扫描时可选规则集 | 2 + 3 |
| 7 | 后端编译 `mvnw.cmd compile`；等价性验证（archaeology-default 扫描结果与改前写死版 byte-identical）；Swagger 测通 6 接口 + scan 新参数 | 后端闭环 + 向后兼容验证 | N/A |
| 8 | 新建 dedup-rules.html：页面骨架 + 左右分栏 CSS（全部 dp- 前缀样式隔离） + 与 6 个 REST 接口联动 JS | 独立规则配置页可 CRUD | 3 |
| 9 | 实现预览弹窗 + Schema 属性下拉联动 + 新建/删除规则集交互 | 配置页交互细节完成 | 3 |
| 10 | 改造 data.html 工具栏：加规则集下拉 + 跳转链接；scan 请求携带 ruleSetId | data.html 可选择多套规则 | 3 |
| 11 | 改造 data.html Layer 1 原理说明 4 卡片 + 快速步骤文案：JS 动态渲染（不再写死） | data.html 展示无硬编码 | 3 |
| 12 | 端到端联调：(a) 新建医疗规则集 → 建 Disease 类后缀 → 保存并 setActive → 返回 data.html 选中它 → 扫描结果变化；(b) 预览弹窗正确性；(c) 恢复 archaeology-default 结果与旧版一致 | 三问全部闭环 | 1+2+3 |

---

## 六、关键验证项

| 验证项 | 说明 | 预期结果 |
|--------|------|---------|
| V1 默认等价性 | 用 archaeology-default 跑扫描 vs 改造前代码各跑一次同一批 1000 节点数据 | 候选组数量、每组 eid、综合分、conflictLevel 完全一致（JSON diff 为 0） |
| V2 新建规则集生效 | 新建空规则集，手动为 Place 加后缀「城」，扫描 Place，样例数据含「长安城」「长安」 | 命中 normalizeAlias 候选组（旧版不会） |
| V3 扫描中途改规则不影响当前 | 线程 A 发起 10 秒长扫描，线程 B 并发 save 新后缀表 | A 的扫描结果全为旧规则（快照机制），B 之后发起的新扫描用新规则 |
| V4 Schema 校验拦截 | 保存时故意把 dynastyLabel 拼为 dynastylabel（小写 d） | 返回 400 + 明确错误：Label HistoricalFigure 不存在属性「dynastylabel」，可选值：[...] |
| V5 builtin 不可删 | DELETE archaeology-default | 返回 403 + 原因 |
| V6 预览功能 | 规则集：Place 后缀=[市,博物馆]；样例：「西安市博物馆」vs「西安」 | 返回：两者命中同一组，展示剥离「市」「博物馆」过程 |
| V7 新行业零代码接入 | 假设 Schema 有 Disease（属性 name/category/icdCode），在配置页新建「医疗 v1」规则集，加 Disease：suffix=[综合征,病,症]，exactModes=[NAME_AUX] auxProps=[category] conflictProps=[icdCode] → 保存 → 激活 → 扫描 | 扫描结果中 Disease 类会按「综合征/病/症」剥离后缀 + 结合 category 相同判重，全程未改 Java/JS |

---

## 七、风险与兜底

| 风险 | 概率 | 影响 | 兜底处理 |
|------|------|------|---------|
| 并发 save 同一规则集导致版本冲突（用户 A、B 同时编辑同一套） | 中 | 后者覆盖前者修改 | 乐观锁：save 请求必须带 `ifMatchVersion`，与磁盘当前 version 不一致返回 409「规则已被他人修改，请刷新后重试」；前端编辑区隐藏字段维护当前读到的 version |
| 规则 JSON 文件被外部手动改坏（非法 JSON / 缺字段） | 低 | 服务启动失败或接口 500 | DedupRulesFileStore 读文件时 try-catch，单文件损坏记录 warn 并跳过；索引损坏则自动从目录扫描重建索引；内置 archaeology-default 永远有 fallback：Java 中保留一份 final 种子常量，JSON 读不出来时装内存 |
| 规则集数量无限增长（100+ 套）导致下拉过长 | 低 | 前端 UI 拥挤 | 下拉 + 搜索输入框组合（类似现有 content-security 分类下拉）；v1 先不做、>20 套时自动升级为可搜索下拉 |
| suffixTable 单 label 后缀过多（500+ 项）性能下降 | 低 | generateNormKeys 慢 | 前后端双重限 200 项/label；保存前按长度降序自动排序 |
| 精确模式 TITLE_ONLY/DIMS 但 nameProp 选了 name（非 title） | 高 | 规则可能无效 | 前端实时灰态复选框+tooltip；后端 save 时再次校验，模式与 nameProp 语义不匹配返回 400（用户可能绕过前端直接 POST） |

---

## 八、未纳入本次（延后按需）

- Profile 模板一键复制（已有「新建空白 + 手动从零配」足够；若以后需要相同行业快速建则加）
- 规则版本历史 diff / 回滚到 Vn（目前有 version 字段但仅做乐观锁，历史快照目录后续可加 `files/dedup/history/{id}-v{version}.json.bak`）
- Excel 批量导入后缀表（目前单类最多 200 项，UI 操作足够）
- 规则变更审计日志（对接 UsersFile 体系后续）

---

以上即为最终方案。**核心变化相对前版总结 3 点**：① 规则集从 1 套变多套，id/name/builtin 管理，扫描时下拉选；② 配置 UI 从 data.html 折叠面板改独立 dedup-rules.html 页面，data.html 仅加跳转链接与下拉；③ 规则目录独立子目录 `files/dedup/rules/` + 索引。请确认后进入实现阶段。
