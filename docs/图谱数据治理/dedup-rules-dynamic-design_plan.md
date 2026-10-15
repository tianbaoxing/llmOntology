# 实体去重规则动态化设计方案（已实施 · 与代码对齐版）

> **实施状态**：本方案已实施完成（2026-09），去重管线 10 处硬编码全部清零。
>
> **相对最初决策的两处落地调整**：
> ① **存储**：原定 JSON 单文件（与 disjoint/content-security 同模式），实际先落地为 `files/dedup-rules.json` 单文件，**P2（2026-10-10）起迁入 MySQL `rule_dedup` 表**（Flyway `V2__p2_graph_config_tables.sql` 建表，原 JSON 文件已归档 `.migrated`）；
> ② **规则集数量**：原定「多套规则集可选、builtin 内置默认套」，落地为**全系统仅一套**（单行存储、恒 active、无 builtin 概念、无内置种子；无行 = 尚未创建，保存即新建）。REST 接口保留多套形态的签名（`listAll` / `get(id)` / `setActive`），便于后续扩展多套。
>
> **按原决策落地部分**：③ 用户可自定义 `{label: suffixes[]}` 标签-内容表；⑤ 独立配置页 `dedup-rules.html`（data.html 内仅跳转链接）；⑥ 含「🧪 预览」；⑦ 新行业从零手动配（无 Profile 模板机制）。

---

## 一、改造前硬编码清单（已全部清零）

### 1.1 后端写死位置（改造前快照，行号为改造前代码）

| # | 文件 | 改造前硬编码 | 现状（动态来源） |
|---|------|-------------|-----------------|
| 1-3 | [NormalizeAliasStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/NormalizeAliasStage.java) | `PLACE_SUFFIXES[22]` / `ERA_SUFFIXES[7]` 静态数组 + `switch(label)` | 已删除；`suffixesFor(label, rules)` 从 `DedupRuleset.suffixTable` 快照读取 |
| 4-6 | [ExactKeyStage.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/pipeline/ExactKeyStage.java) | `CulturalRelic` if 特判 / `DIMS[7]` / `auxFingerprint()` 4 label switch | 已删除；`run(nodes, rules)` 按 `labelConfig` 的 `exactModes` 动态执行（当前仅 NAME_AUX 一种模式） |
| 7 | [DedupScanService.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/DedupScanService.java) | `CulturalRelic → title` nameProp 硬判 | `rulesService.namePropOf(rules, label)`：`cfg.nameProp` → 缺省 `cls.keyProp()` |
| 8 | [DedupScanService.java](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/java/com/conca/ontology/dedup/service/DedupScanService.java) | 冲突检测 `CRITICAL[6]` 写死 | `rulesService.conflictPropsOf(rules, label)`：`globalConflictProps ∪ cfg.conflictProps`（LinkedHashSet 保序去重） |

### 1.2 前端写死位置（改造前快照）

| # | 文件 | 改造前硬编码 | 现状 |
|---|------|-------------|------|
| 9 | [data.html](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/resources/static/pages/data.html) | 快速步骤「Place 18 后缀 / Era 7 后缀」文案 | `dp-quick` / `dp-detail` / `dp-rule-hint` 按当前规则集 JS 动态渲染 |
| 10 | [data.html](file:///c:/me/kaiyuan/history/llmOntology/ontology-server/src/main/resources/static/pages/data.html) | 原理说明 4 张静态卡片 | 同上，动态渲染（后缀表逐 label 展示，未配置标注空态） |

---

## 二、数据结构设计

### 2.1 存储：MySQL `rule_dedup` 表（单行模式）

由 Flyway `V2__p2_graph_config_tables.sql` 创建，P2 迁移时从 `files/dedup-rules.json` 导入并归档：

```sql
CREATE TABLE rule_dedup (
    id                   VARCHAR(64)  NOT NULL COMMENT '规则集唯一 ID',
    name                 VARCHAR(128) NOT NULL COMMENT '规则集展示名',
    version              INT          NOT NULL COMMENT '版本号（每次写入 +1，乐观锁）',
    updated_at           BIGINT       NOT NULL COMMENT '最后更新时间（epoch 毫秒）',
    suffix_table         JSON         NOT NULL COMMENT 'Normalize 后缀表 Map<label, string[]>',
    label_config         JSON         NOT NULL COMMENT 'label 配置 Map<label, DedupLabelConfig>',
    global_conflict_props JSON        NOT NULL COMMENT '全局冲突检测关键属性（可为空数组）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '去重规则集';
```

- **单行语义**：全系统仅一套规则，持久化为单行，无索引文件；`active` 恒为 true。
- **无行 = 尚未创建**：前端显示空白模板（dedup-rules.html），保存即新建（后端生成 UUID 或采用入参 id）。
- 三个 JSON 列经 MyBatis-Plus `JacksonTypeHandler`（`autoResultMap = true`）读写，与 API JSON 形态一致。

### 2.2 规则集结构（`DedupRuleset`，与 API JSON 一致）

```json
{
  "id": "0f1c…（UUID 或自定义）",
  "name": "去重规则",
  "version": 3,
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
      "conflictProps": ["site", "province"]
    },
    "HistoricalFigure": {
      "nameProp": "name",
      "auxProps": ["dynastyLabel", "identity"],
      "exactModes": ["NAME_AUX"],
      "conflictProps": ["dynastyLabel"]
    },
    "CulturalRelic": {
      "nameProp": "title",
      "auxProps": [],
      "exactModes": ["NAME_AUX"],
      "conflictProps": ["era", "site", "province", "dynastyLabel"]
    }
  },

  "globalConflictProps": ["era", "site", "province", "dynastyLabel", "startYear", "endYear"]
}
```

**字段语义（对应三问）**：

| 字段 | 对应问题 | 说明 |
|------|---------|------|
| `suffixTable: Map<label, List<String>>` | **问题 1** | 核心「标签-内容」动态表：`{Place: [省,市,…], Era: [朝,代,…]}`。用户可自定义任意 label 后缀，未配置 label 不剥离（仅基础规范化）。保存时自动清洗：trim + 去粘贴引号 + 去重 + **限 200 项/label** + 按长度降序排序（长后缀优先剥离）。 |
| `labelConfig: Map<label, 单类配置>` | **问题 2** | key 为任意 label，不写死任何类名。换行业直接改配置，不改 Java 代码。 |
| `exactModes` 枚举 | 问题 2 | **当前仅 `NAME_AUX`** 一种（`DedupExactMode` 枚举预留扩展位）；无 dimProps / TITLE_* 模式。 |
| `id / name / version` | 问题 3（规则集管理） | 单套模式：已有规则时保存沿用原 id；version 每次写入 +1，兼作乐观锁（提交 version 与库内不一致 → 409）。 |

### 2.3 Java 模型与存储分层

```
com.conca.ontology.dedup
├── model
│   ├── DedupRuleset.java          Record: id / name / version / updatedAt + suffixTable / labelConfig / globalConflictProps
│   ├── DedupLabelConfig.java      Record: nameProp / auxProps / exactModes / conflictProps（4 字段，无 dimProps）
│   ├── DedupExactMode.java        Enum: NAME_AUX（预留扩展）
│   ├── DedupRulesIndexItem.java   Record: id / name / version / updatedAt / active（active 恒 true）
│   └── DedupRulesStore.java       MySQL 存储层：单行 CRUD + 乐观锁 409 + synchronized 读-改-写串行 + 后缀清洗
├── entity/RuleDedup.java          MyBatis-Plus 实体（IdType.INPUT，三 JSON 列 JacksonTypeHandler）
├── mapper/RuleDedupMapper.java
└── converter/DedupRulesetConverter.java   实体 ↔ Record 互转
```

> `DedupRulesIndexItem` 由单行派生（无独立索引文件）：`listIndex()` 返回 0 条（未创建）或 1 条（恒 active）。

---

## 三、后端实现

### 3.1 文件清单

| 路径 | 职责 |
|------|------|
| `dedup/model/*.java`（5 个）+ `entity/RuleDedup` + `mapper/RuleDedupMapper` + `converter/DedupRulesetConverter` | 见 2.3 |
| `dedup/service/DedupRulesService.java` | 规则服务：`listAll` / `get` / `getActive` / `getOrDefault` / `setActive` / `save`（Schema 属性名校验）/ `delete` / `preview`；另提供 `namePropOf` / `conflictPropsOf` / `suffixesFor` 供扫描链快捷读取 |
| `dedup/api/DedupRulesController.java` | 规则配置 REST 接口（7 个），服务于 `dedup-rules.html` |

### 3.2 REST 接口（全部 `/api/graph/dedup/*` 前缀）

| 方法 | 路径 | 输入 / 输出 | 作用 |
|------|------|------------|------|
| `GET` | `/rules` | → `List<DedupRulesIndexItem>` | **列表**：单套模式下返回 0 或 1 条（id/name/version/updatedAt/active 恒 true）。 |
| `GET` | `/rules/active` | → `DedupRuleset` | **当前激活**：取唯一一套；未创建返回 404。 |
| `GET` | `/rules/{id}` | → `DedupRuleset` | **详情**：id 校验须与唯一一套一致（不匹配 404）；路径段可空 = 等价 active。 |
| `POST` | `/rules` | ← `DedupRuleset`（无行=新建，有行=整体覆盖）→ `DedupRulesIndexItem` | **保存**：整体覆盖写，version+1；乐观锁（提交 version>0 且与库内不一致 → 409「规则已被他人修改…请刷新后重试」）；保存前 Schema 校验（label 须在 GraphSchema 定义、nameProp/auxProps/conflictProps 须为该类真实属性、globalConflictProps 须为合法标识符，失败 400 + 可选值清单）；后缀清洗限 200 项/label；id 仅允许字母/数字/`-`/`_`。 |
| `POST` | `/rules/{id}/activate` | → `List<DedupRulesIndexItem>` | **激活**：单套恒 active，仅校验存在性（无状态可切换）。 |
| `DELETE` | `/rules/{id}` | → `List<DedupRulesIndexItem>` | **删除**：删除唯一一套 → 回到「尚未创建」状态（下次保存即新建）。 |
| `POST` | `/rules/preview` | ← `{rules: DedupRuleset, sample: {label, name1, name2, props1?, props2?}}` → `PreviewResult{hit, reasons, norm1, norm2, sharedNormKeys, exactSubStagesHit}` | **预览**：提交一份临时规则 + 一组样例（两个字符串 + 可选属性 Map），返回是否命中同一候选组、两串各自的规范化步骤（base + 剥离产生的 normKeys）、共同 normKey 与命中的子策略。 |

### 3.3 管线改造（已完成）

#### 3.3.1 NormalizeAliasStage.java（= 解决问题 1）
- 已删除静态 `PLACE_SUFFIXES` / `ERA_SUFFIXES` 与 `switch(label)`
- `run(nodes, DedupRuleset rules)` 由 `DedupScanService` 每次扫描传入规则快照：

```java
private List<String> suffixesFor(String label, DedupRuleset rules) {
    if (label == null || rules == null || rules.suffixTable() == null) return List.of();
    List<String> s = rules.suffixTable().get(label);
    return s == null ? List.of() : s;
}
```

- 基础规范化 `baseNormalize`：全半角标点统一 + 括号内注释剥离 + 空白压缩；后缀循环剥离（`do…while changed`，长后缀因保存时排序而优先），每个节点产出 1~N 个 normKey。

#### 3.3.2 ExactKeyStage.java（= 解决问题 2）
- 已删除 `DIMS` 静态数组、`auxFingerprint()` 的 label switch、`CulturalRelic` if 特判
- `run(nodes, rules)`：`cfg = rules.labelConfig().get(label)` 取当前类配置，遍历 `cfg.exactModes()` 动态执行（当前仅 `NAME_AUX → modeNamePlusAux`）
- `NAME_AUX`：nameProp 规范化后相同 + 辅助属性指纹一致（`fingerprint` 所有辅助属性都缺失时返回 `"none"`，退化为纯 name 相同）；subStage = `exact-nameAux`

#### 3.3.3 DedupScanService.java（= 解决问题 2 + 问题 3）
- `scan()` 第一行取规则快照：`rules = rulesService.getOrDefault(req.ruleSetId())`，本次扫描全程使用同一份不可变引用（并发 save 不影响进行中的扫描）
- nameProp：`rulesService.namePropOf(rules, label)`（`cfg.nameProp` → 缺省该类 `keyProp`，再缺省 `name`）
- 冲突检测属性：`rulesService.conflictPropsOf(rules, label)` = `LinkedHashSet(globalConflictProps ∪ cfg.conflictProps)`，任一冲突属性在组内出现 ≥2 个不同非空值 → `ConflictLevel.ATTRIBUTE` + 原因文案
- 综合分：`max(bestNorm, bestExact)` 达 1.0 时直接取 1.0；`autoMergeReady` = 无冲突 且 组内最小综合分 ≥ 0.95

#### 3.3.4 DedupScanRequest.java
字段：`label`（`*`=逐类全扫）/ `threshold`（默认 0.95）/ `limitGroups` / `skipGroups` / `ruleSetId`（空 = 当前激活套；单套模式下仅做 id 校验）。`POST/GET /api/graph/dedup/scan`（GraphDedupController）契约不变。

---

## 四、前端实现

### 4.1 页面架构

```
pages/
├── data.html              （实体去重 Tab：工具栏规则集下拉 + 跳转链接 + 原理说明动态渲染）
└── dedup-rules.html       （独立规则配置页：单栏编辑唯一一套规则）
```

### 4.2 data.html 实体去重 Tab

**Layer 0 工具栏**：`规则集` 下拉（`dp-ruleset`，数据来自 `GET /rules`，单套模式下仅一项）｜`Label` 下拉｜阈值｜最多组数｜`[🔍 扫描重复]`｜`[📝 编辑规则配置 →]`（链接新窗口打开 `/pages/dedup-rules.html`）。扫描时 `POST /api/graph/dedup/scan` body 携带 `ruleSetId: 下拉选中值`。

**Layer 1 原理说明**：`dp-quick`（快速步骤）/ `dp-detail`（后缀表等详情卡片）/ `dp-rule-hint`（当前规则集名 · 版本 · 更新时间）全部按 `GET /rules/{id}` 结果 JS 动态渲染；未创建规则时显示「尚未创建去重规则集，请先通过编辑规则配置创建并保存」空态。

### 4.3 dedup-rules.html（独立规则配置页）

单栏布局（非多套列表管理），自上而下：

1. **状态横幅**（`dr-status-banner`）：已创建时显示「当前编辑唯一一套激活的去重规则。ID / v{version} / 更新时间，修改后直接保存整体覆盖」；未创建时显示「配置下方内容后点击保存将创建第一套规则集」。
2. **🗂 后缀表编辑**：每行 = `Label 选择（来自 GraphSchema 实例层类）+ 关键词列表`，可增删行；附循环剥离示例（「西安市博物馆」→「西安市」→「西安」）；关键词支持逗号 / 换行 / 顿号分隔。
3. **🏷 Label 精确判重配置**：每类一张卡（勾选启用），内含 `nameProp` 下拉（该类全部属性）、`NAME_AUX` 模式开关、`auxProps` / `conflictProps` chip 编辑；附术语说明表（nameProp / exactModes / auxProps / conflictProps / globalConflictProps 中文名与解释）。
4. **全局冲突属性**编辑。
5. **操作按钮**：`[🧪 预览]` `[↩️ 撤销改动]` `[💾 保存]`。

**关键交互**：
- **保存**：整体 `POST /rules` 覆盖；成功后重拉最新版本并提示「保存成功，版本 v{n}」；乐观锁冲突 / 校验失败弹出后端 400/409 明细。
- **撤销**：确认后重新 `GET /rules/active` 覆盖前端编辑状态（`drOrigJson` 脏判断）。
- **预览**：弹窗选择 Label + 输入样例 1 / 样例 2（+ 可选属性 JSON）→ `POST /rules/preview` → 命中（绿）/ 未命中（红）+ 两串规范化步骤 + 原因清单（共同 normKey / 精确模式命中 / 未命中说明）。

---

## 五、实施记录（12 步已全部完成）

| # | 步骤 | 实际产出 |
|---|------|---------|
| 1 | 模型层 5 个 Record/Enum + 实体/Mapper/Converter | 见 2.3；无内置种子文件（`ensureInitialized()` 空实现，表由 Flyway 管理） |
| 2 | DedupRulesService：CRUD + Schema 校验 + 预览 | 见 3.1；扫描快照 CopyOnRead |
| 3 | DedupRulesController 7 个 REST 接口 | 见 3.2，Swagger 可调 |
| 4 | NormalizeAliasStage 动态化 | suffixTable 快照传参（3.3.1） |
| 5 | ExactKeyStage 动态化 | exactModes 动态执行，硬编码清零（3.3.2） |
| 6 | DedupScanService + DedupScanRequest 改造 | `ruleSetId` 字段；nameProp / 冲突属性从快照取（3.3.3） |
| 7 | 后端编译与回归 | 硬编码清零验证 + 接口回归；P2 迁移时（JSON → MySQL）四类配置 API 读写回归通过 |
| 8 | dedup-rules.html | 单栏编辑页（后缀表 + Label 配置卡 + 术语说明），`dr-` 前缀样式隔离 |
| 9 | 预览弹窗 + Schema 属性下拉联动 | 见 4.3 交互 3 |
| 10 | data.html 工具栏 | 规则集下拉 + 跳转链接；scan 携带 ruleSetId |
| 11 | data.html 原理说明动态渲染 | dp-quick / dp-detail / dp-rule-hint 按当前规则集渲染 |
| 12 | 端到端联调 | 规则创建 → 编辑 → 扫描生效 → 预览闭环 |

---

## 六、关键行为语义（当前代码）

| 项 | 行为 |
|----|------|
| 未创建规则时扫描 | `GET /rules/active` 404：「尚未创建去重规则集，请先在去重规则配置页面创建并保存一套规则」 |
| 后缀剥离 | 长后缀优先（保存时长度降序）+ 循环剥离直至无可剥离；`stripped.length() > sfx.length()` 防止整词剥空 |
| 并发保存 | `synchronized` 读-改-写串行 + 乐观锁：提交 version 与库内不一致 → 409「规则已被他人修改（当前 vN，提交 vM），请刷新后重试」 |
| Schema 校验 | 拼错属性名（如 `dynastylabel`）→ 400「Label '…' 的 … 属性 '…' 不存在；可用属性：[…]」；未知 label 同样 400（提示可先保存空配置待 Schema 建类后补充） |
| 删除唯一一套 | 回到「尚未创建」状态；再次保存即新建（新 UUID 或入参 id） |
| 预览 | 返回 hit / reasons / norm1 / norm2（raw + base + 剥离 keys）/ sharedNormKeys / exactSubStagesHit；精确模式仅 NAME_AUX 参与 |
| 规则热更新 | 保存立即生效；进行中的扫描不受影响（快照引用），下次扫描用新规则 |

---

## 七、风险与兜底

| 风险 | 概率 | 影响 | 处理 |
|------|------|------|------|
| 并发保存同一套规则导致覆盖 | 中 | 后者覆盖前者 | **已落地**：乐观锁（version 不匹配 409）+ `synchronized` 整体串行 |
| 单 label 后缀过多（500+ 项）性能下降 | 低 | generateNormKeys 变慢 | **已落地**：保存时限 200 项/label（超出 400）+ 长度降序排序 |
| 规则文件损坏 | — | — | **不适用**：MySQL 存储 + Flyway 管理表结构，无文件读写路径 |
| 多套下拉过长 | — | — | **不适用**：单套模式（若未来扩展多套，>20 套时升级可搜索下拉） |
| TITLE_* 模式与 nameProp 语义不匹配 | — | — | **不适用**：当前仅 NAME_AUX；若未来扩展 TITLE_* 枚举，需同步补「nameProp 须为 title 语义」的前后端校验 |

---

## 八、未纳入本次（延后按需）

- **多套规则集管理**：原方案的多套能力未落地（存储单行、无 builtin/激活切换）；REST 已保留多套形态签名，扩展时 Store 改多行 + 补 activate 真实语义 + dedup-rules.html 加规则集列表即可
- Profile 模板一键复制（新建空白 + 手动从零配已够用）
- 规则版本历史 diff / 回滚（version 当前仅作乐观锁，无历史快照表）
- Excel 批量导入后缀表（单类 200 项上限内 UI 操作足够）
- 规则变更审计日志

---

**与最初方案的核心差异总结**：① 存储从「多 JSON 文件 + rules-index.json 索引」简化为「MySQL `rule_dedup` 单行表」（经 JSON 单文件过渡，P2 迁移）；② 规则集从多套（builtin 内置默认 + 可选）简化为全系统一套（恒 active、无内置种子、无行=空白模板）；③ `DedupExactMode` 仅实现 NAME_AUX（无 TITLE_ONLY / TITLE_DIMS / dimProps）；④ preview 为单 sample（含可选 props Map）；⑤ dedup-rules.html 为单栏编辑页（无左侧多套列表/激活切换/删除按钮）。
