# /agents 页面二级菜单与模型管理设计

- 版本：v1.1
- 日期：2026-10-29
- 范围：仅设计，不含代码实现
- 后端规范基线：`com.conca.ontology.agent` 包（结构、分层、权限、注释规则全部对齐该包现有代码）
- 关联：`static/pages/agents.html`、`db/migration/V17__model_info.sql`（表已建，57 列，`model` 自然主键）

## 1. 背景与目标

`/agents`（智能体搭建）当前为单页平铺内容。本设计在页内增加**二级菜单（页内 Tab）**：

| Tab | 内容 |
|---|---|
| 智能体管理 | 现有全部内容原样保留（列表 / 新建编辑整页表单 / 试运行弹框），不做任何重构 |
| 模型管理 | 新增：`model_info` 表的分页展示 + 新增 / 编辑（弹框）+ 删除 |

设计原则：
- 复用项目已有模式：二级 Tab 用 `data.html` 的 `page-tabs / ptab / switchTab` 模式；分页参数用 eval 数据集的 `page / pageSize` 模式；接口风格沿用现有 RESTful（GET 查询、POST 新增、PUT 编辑、DELETE 删除，`ApiResponse` 包装）。
- 57 列不可能平铺展示，核心是**分层显示规则**：列表只放关键列，其余字段进弹框分组呈现。

## 2. 页面框架设计

### 2.1 菜单与路由

- 一级菜单不变：`common.js` 的 `MENU` 中 `/agents`（admin: true）保持原样，`ADMIN_PAGES` 已含 `/agents`，无新增路由注册。
- 二级菜单为**页内 Tab，纯前端切换**，URL 不变（刷新回到默认 Tab「智能体管理」）；一期不做 Tab 状态记忆（如 `#models` 锚点列为可选优化）。

### 2.2 DOM 结构（agents.html）

```
.ag-wrap 外层
├─ .page-tabs（新增，复用 data.html 样式）
│   ├─ a.ptab.active  智能体管理  → 显示 #tab-agents
│   └─ a.ptab         模型管理    → 显示 #tab-models
├─ #tab-agents      ← 现有 ag-list-view / ag-form-view 原样移入，JS 零改动
├─ #tab-models      ← 新增模型管理区块（display:none 初始隐藏）
└─ 试运行 modal 照旧置于外层（跨 Tab 独立弹框，不受 Tab 切换影响）
```

- Tab 切换函数 `agSwitchTab(tab)`：互斥显示 `#tab-agents / #tab-models`，切换 active 样式；智能体编辑整页表单（ag-form-view）与 Tab 切换互不干扰（编辑表单属于 Tab 内部状态）。
- 前端命名隔离：模型管理全部以 `mi-` 前缀 + `miXxx()` 函数命名，与 `ag-` 前缀完全隔离（遵循 data.html 各 Tab 前缀隔离惯例）。

## 3. 模型管理 Tab 设计

### 3.1 布局

```
#tab-models
├─ 工具栏（一行，flex 换行）
│   ├─ 搜索：model 模糊（placeholder「模型名，模糊匹配」）
│   ├─ 搜索：organization 模糊
│   ├─ 筛选：confidence 下拉（全部/Confident/Likely/Speculative/Unknown）
│   ├─ 筛选：基础模型 / 前沿模型 / 开放权重 三个三态下拉（全部/是/否）
│   ├─ 按钮：查询、重置
│   └─ 右侧：＋ 新增模型（primary）
├─ 列表表格（见 3.2）
└─ 分页条（见 3.3）
```

### 3.2 列表列设计（57 列 → 9 列 + 操作）

| # | 列 | 字段 | 显示规则 |
|---|---|---|---|
| 1 | 模型 | model | 加粗，主行标识；超长截断 28 字 + title 悬浮全文 |
| 2 | 发布组织 | organization | 截断 24 字 + title；多值逗号原文 |
| 3 | 发布日期 | publication_date | yyyy-MM-dd；空显示 — |
| 4 | 参数量 | parameters | 科学计数法（如 1.8e11）；空显示 — |
| 5 | 领域 | domain | 截断 20 字 + title；空显示 — |
| 6 | 标志 | foundation_model / frontier_model / open_model_weights | 三个小徽标：`基础` `前沿` `开放权重`，仅真值渲染（false/null 不渲染，避免 57 列表格噪音） |
| 7 | 引用数 | citations | 保留源精度（如 1234.5）；空 — |
| 8 | 置信度 | confidence | 原文；空 — |
| 9 | 操作 | — | `编辑` `删除`（btn sm） |

- 其余 48 列一律不进列表，通过「编辑」弹框查看全量（一期不单独做只读详情弹框，编辑弹框承担查看职责，减少一套 UI）。
- 空值统一显示 `—`（灰），与项目现有列表习惯一致。

### 3.3 分页设计

- 参数：`page`（从 1 起）、`pageSize`（10 / 20 / 50 可选，默认 10，上限 100）。
- 分页条内容：`共 {total} 条 · 第 {page}/{totalPage} 页` + 首页/上一页/页码/下一页/末页 + 每页条数下拉 + 跳页输入。
- 行为：
  - 查询条件变化后点「查询」→ 重置回第 1 页；
  - 「重置」→ 清空条件、回第 1 页、重新加载；
  - 删除后若当前页被删空且非第 1 页 → 自动回退一页刷新；
  - page 越界由后端收敛（沿用 eval 数据集分页语义）。
- 默认排序：`model` 升序（主键序，可预期）；一期不做前端排序切换，`publication_date` 索引已存在，二期如需"按发布日期浏览"再加排序参数。

## 4. 新增 / 编辑弹框设计（核心：57 字段的显示规则）

### 4.1 弹框骨架

- 宽 980px（max-width 94vw），max-height 88vh；结构：标题栏 / 内容滚动区（overflow auto）/ 底部固定按钮条（保存、取消）。
- 标题：新增 `新增模型`；编辑 `编辑模型：{model}`。
- 内容区为**分组折叠面板**：共 8 组，第一组默认展开，其余默认折叠；每组标题常显字段数（如「四、规模与算力（9 字段）」）。折叠只影响浏览，不影响数据收集（保存时全量收集）。
- 组内布局：短字段两列网格（复用 `ag-grid2` 风格）；TEXT 长文本整行。

### 4.2 通用字段类型显示规则

| 类型 | 涉及列 | 控件 | 规则 |
|---|---|---|---|
| 主键 | model | 单行文本 | 必填，≤160；**编辑时只读**（自然主键不可改，无代理 id）；新增时后端查重，重复返回「模型已存在」 |
| 短/中文本 | domain、task、organization 等 VARCHAR | 单行文本 | maxlength=列宽；多值列保持源文件逗号分隔原文，不拆分、不拆子表（与 V17 入库规则一致） |
| 长文本 TEXT | abstract、authors、各 *_notes | textarea | abstract 6 行；authors 3 行；notes 类 2 行；均可多行，不做富文本 |
| 日期 | publication_date | date 控件 | yyyy-MM-dd |
| 日期时间 | last_modified | 只读文本 | UTC 语义，新增时不可填（留空）；编辑时只读展示（源数据 / 系统维护字段） |
| 三态布尔 | foundation_model、frontier_model、open_model_weights、possibly_over_1e23_flop | 下拉（空/是/否） | 映射 null / 1 / 0；默认「空」 |
| 枚举 | confidence | 下拉 | Confident / Likely / Speculative / Unknown / 空 |
| 大数值 DECIMAL(45,10) | 7 个 FLOP/规模列 | **文本输入** | 不用 type=number（1.13e27 超出 JS 安全范围且 number 控件溢出）；正则校验 `^-?\d+(\.\d+)?([eE][+-]?\d+)?$`；显示用科学计数法 |
| 普通数值 | citations、training_time_hours、hardware_quantity 等 | 文本输入 + 同上正则 | 按列小数位校验上限；显示去尾零 |

### 4.3 大数序列化规则（关键）

7 个 DECIMAL(45,10) 列（parameters、training_compute_flop、training_dataset_size_total、finetune_compute_flop、post_training_compute_flop、training_compute_lower_bound、training_compute_upper_bound）：
- 后端 JSON 序列化为**字符串**（非数字），前端原样回显与回传，规避 JS `Number` 超过 2^53 的精度丢失与科学计数法变形；
- 列表/弹框展示时前端格式化为科学计数法（如 `1.13e+27`）。

### 4.4 字段分组明细（57 列全量）

**一、基本信息（11）**
| 字段 | 控件 | 备注 |
|---|---|---|
| model | 单行 | 主键，规则见 4.2 |
| domain | 单行 | 领域，逗号多值 |
| task | 单行（占整行） | 任务，512 列宽多值 |
| organization | 单行 | 发布组织，多值 |
| organization_categorization | 单行 | 组织分类，多值 |
| country_of_organization | 单行 | 国家，多值 |
| base_model | 单行 | 基座模型 |
| model_accessibility | 单行 | 可获取方式 |
| hugging_face_developer_id | 单行 | HF 开发者 ID |
| publication_date | date | 发布日期 |
| last_modified | 只读 | 数据最后修改时间 |

**二、标志位（3）**：foundation_model、frontier_model、open_model_weights —— 三态下拉，横向一行三列。

**三、文献与知名度（7）**
| 字段 | 控件 |
|---|---|
| reference | 单行 |
| link | 单行（整行） |
| archived_links | 单行（整行） |
| citations | 数值文本 |
| notability_criteria | 单行 |
| notability_criteria_notes | textarea 2 行 |
| abstract | textarea 6 行（整行） |

**四、规模与算力（9）**
| 字段 | 控件 |
|---|---|
| authors | textarea 3 行（整行） |
| parameters | 大数文本 |
| parameters_notes | textarea 2 行 |
| training_compute_flop | 大数文本 |
| training_compute_notes | textarea 2 行 |
| training_compute_lower_bound | 大数文本 |
| training_compute_upper_bound | 大数文本 |
| training_compute_estimation_method | 单行 |
| possibly_over_1e23_flop | 三态下拉 |

**五、数据与训练过程（9）**
| 字段 | 控件 |
|---|---|
| training_dataset_size_total | 大数文本 |
| dataset_size_notes | textarea 2 行 |
| epochs | 数值文本 |
| batch_size | 数值文本 |
| batch_size_notes | textarea 2 行 |
| approach | 单行 |
| confidence | 枚举下拉 |
| training_time_hours | 数值文本 |
| training_time_notes | textarea 2 行 |

**六、硬件与运行环境（12）**
| 字段 | 控件 |
|---|---|
| training_hardware | 单行 |
| hardware_quantity | 数值文本 |
| training_chip_hours | 数值文本 |
| hardware_utilization_mfu | 数值文本 |
| hardware_utilization_hfu | 数值文本 |
| utilization_notes | textarea 2 行 |
| training_power_draw_w | 数值文本 |
| numerical_format | 单行 |
| training_cloud_compute_vendor | 单行 |
| training_data_center | 单行 |
| training_code_accessibility | 单行 |
| accessibility_notes | textarea 2 行 |

**七、成本与后训练（5）**
| 字段 | 控件 |
|---|---|
| training_compute_cost_2023_usd | 数值文本 |
| finetune_compute_flop | 大数文本 |
| finetune_compute_notes | textarea 2 行 |
| post_training_compute_flop | 大数文本 |
| post_training_compute_notes | textarea 2 行 |

**八、其他（1）**：wikitext_and_penn_treebank_data —— 单行。

### 4.5 保存与校验

- 仅 `model` 必填；其余 56 字段全部可空（与 DDL 一致，NULL 语义保留）。
- 校验分层：前端 maxlength / 正则 / date 格式预校验；后端同规则兜底（超长、格式非法、model 查重），错误信息逐字段明确返回。
- 后端**不做静默截断**：超列宽直接报错，保证与导入数据（model_info_import.sql）同等严格。
- 保存成功：toast「已保存」+ 关闭弹框 + 刷新当前页列表。

## 5. 删除设计

- 行操作「删除」→ `confirm("确认删除模型「{model}」？该操作不可恢复。")` → DELETE → toast + 列表刷新（当前页删空回退一页，见 3.3）。
- 物理删除（表为独立域、无外键关联，无级联影响）。

## 6. 后端设计（对齐 `com.conca.ontology.agent` 包规范）

### 6.1 包结构

新包 `com.conca.ontology.model`（表 model_info，独立域、无外键；包结构与 agent 包一一对应）：

```
com.conca.ontology.model/
├─ api/
│   └─ ModelInfoController.java    @RestController @RequestMapping("/api/model-info")
├─ service/
│   └─ ModelInfoService.java       普通 @Service 类（无接口 + impl 拆分，与 agent 包一致）
├─ mapper/
│   └─ ModelInfoMapper.java        @Mapper extends BaseMapper<ModelInfoEntity>，零自定义 SQL
├─ entity/
│   └─ ModelInfoEntity.java        @TableName("model_info")，@TableId("model", IdType.INPUT)
└─ config/
    └─ MybatisPlusPageConfig.java  MyBatis-Plus 分页拦截器（项目当前未配置，见 6.5）
```

不建 `dto/`、`model/` 子包：一期无独立 DTO 与领域模型需求（请求体直接绑定实体、响应视图用 Service 内嵌 record，均有 agent 包先例：`RewriteConfigController.SaveBody` / `RewriteConfigService.ConfigView`）。

### 6.2 Controller 规范（对齐 AgentBuilderController / RewriteConfigController）

- 构造器注入 Service；swagger `@Tag(name = "Admin-ModelInfo")` + 每方法 `@Operation`；类头 javadoc 含 `@author` / `@date`。
- **每个方法签名带 `HttpSession session`，方法体首行 `requireAdmin(session)`**：登录已由 `user/web/AuthInterceptor`（WebMvcConfig 注册）保证，Controller 内做 ADMIN 兜底（401/403 抛 `ApiException`），requireAdmin / requireLogin 私有静态方法与 AgentBuilderController 同款。
- 返回统一 `ApiResponse.ok(...)`。

**接口清单**（model 主键为任意文本 ≤160，可能含 `/`、空格等，**不用路径参数**——规避 URL 编码歧义；RewriteConfigController 已有无路径参数先例）：

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| GET | `/api/model-info/page` | `page, pageSize, model, organization, confidence, foundationModel, frontierModel, openWeights`（@RequestParam） | 分页 + 条件查询；返回 `PageView` |
| GET | `/api/model-info/detail` | `model`（@RequestParam） | 单条全字段；不存在抛 404 ApiException |
| POST | `/api/model-info` | `@RequestBody ModelInfoEntity` | 新增；model 查重 |
| PUT | `/api/model-info` | `@RequestBody ModelInfoEntity` | 编辑；以 body.model 定位，忽略隐式主键变更 |
| DELETE | `/api/model-info` | `model`（@RequestParam） | 物理删除 |

请求体直接绑定 `ModelInfoEntity`（表单 57 字段与表行一一对应、无敏感字段，独立 SaveDTO 无增量价值；区别于 agent 定义含密钥才需要 AgentSaveDTO）。

### 6.3 Service 规范（对齐 RewriteConfigService）

- 普通 `@Service` 类，无接口；类头 javadoc 说明表结构与业务语义。
- 校验风格：非法入参抛 `ApiException(400, "字段中文说明 + 错误原因")`，逐字段明确：
  - `model` 必填、≤160；新增时 `selectById` 查重，重复报「模型已存在」；
  - 各 VARCHAR/TEXT 字段超列宽直接报错（**不静默截断**，与导入数据同等严格）；
  - `confidence` 枚举校验（Confident/Likely/Speculative/Unknown）；日期由类型绑定天然校验。
- 查询构造：MyBatis-Plus `LambdaQueryWrapper`（model/organization 模糊 LIKE；布尔筛选仅「是/否」参与，null 不过滤；utf8mb4_bin 区分大小写，一期按现状）。
- 逻辑方法：`page(...)` / `detail(...)` / `create(...)` / `update(...)` / `delete(...)`。
- 响应视图（Service 内嵌 record，风格同 ConfigView）：

```text
record PageView(List<ModelInfoEntity> rows, long total, int page, int pageSize)
```

### 6.4 实体映射规则（57 列 → Java）

- `@TableName("model_info")`；主键 `@TableId(value = "model", type = IdType.INPUT)`（自然主键，同 RewriteConfigItem 的 feature）。
- 列名驼峰自动映射（map-underscore-to-camel-case）。
- 类型映射：VARCHAR/TEXT → `String`；DECIMAL → `BigDecimal`；DATE → `LocalDate`；DATETIME → `LocalDateTime`；TINYINT(1) → `Boolean`（可空，三态）。
- **7 个 DECIMAL(45,10) 大数列**（parameters、training_compute_flop、training_dataset_size_total、finetune_compute_flop、post_training_compute_flop、training_compute_lower_bound、training_compute_upper_bound）：字段加 `@JsonSerialize(using = ToStringSerializer.class)`，JSON 序列化为字符串（见 4.3，规避 JS 2^53 精度丢失）。
- 无 Lombok（手写 getter/setter，与 RewriteConfigItem 一致）。

### 6.5 分页实现

- 项目当前未配置 MyBatis-Plus 分页拦截器（无 `PaginationInnerInterceptor`，也无 `selectPage` 使用先例），需在 `model/config/MybatisPlusPageConfig.java` 新增 `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)` 配置类（`@Configuration`）。
- 分页查询：`mapper.selectPage(new Page<>(page, pageSize), wrapper)`；page 越界由拦截器自动收敛。
- pageSize 上限 100，Service 内校验。

### 6.6 权限与登录

- 登录：`AuthInterceptor` 全局拦截（现状，不改）。
- ADMIN：各 Controller 方法内 `requireAdmin(session)` 兜底（403「仅管理员可管理模型信息」）。
- 与 `/api/agents` 同级的管理面接口，无需改动 RolePermissionMiddleware（那是 agent 运行时工具权限，与本接口无关）。

## 7. 改动清单（实施时）

| 文件 | 改动 |
|---|---|
| `static/pages/agents.html` | 顶部加 page-tabs；现有内容包入 `#tab-agents`（零逻辑改动）；新增 `#tab-models` 区块 + mi- 前缀样式与脚本 |
| `common.js` | **不改**（一级菜单、权限逻辑均不变） |
| 后端（全部新增，零存量改动） | `com.conca.ontology.model` 包下 5 个类：`api/ModelInfoController`、`service/ModelInfoService`、`mapper/ModelInfoMapper`、`entity/ModelInfoEntity`、`config/MybatisPlusPageConfig`；无表结构变更（V17 已建） |

## 7.1 后端新增文件清单（参照 agent 包规则）

| 文件 | 内容要点 |
|---|---|
| `model/api/ModelInfoController.java` | 5 个接口（见 6.2 清单）；每方法 `requireAdmin(session)` 兜底；swagger 注解；类头 `@author/@date` javadoc |
| `model/service/ModelInfoService.java` | 分页查询 / 详情 / 新增（查重）/ 编辑 / 删除 + 全量字段校验；内嵌 `PageView` record |
| `model/mapper/ModelInfoMapper.java` | `@Mapper` 空接口 extends `BaseMapper<ModelInfoEntity>` |
| `model/entity/ModelInfoEntity.java` | `@TableName("model_info")`；57 字段手写 getter/setter；大数列 `@JsonSerialize(ToStringSerializer)` |
| `model/config/MybatisPlusPageConfig.java` | `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)` |

## 8. 待确认问题

1. 模糊搜索是否需要不区分大小写（当前表 collation 为 utf8mb4_bin，精确区分大小写）？一期建议按现状（区分大小写）实现。
2. Tab 状态是否需要锚点记忆（如 `#models`，刷新后停留模型管理 Tab）？一期默认不做。
3. `last_modified` 编辑时是否允许手动修改？本设计为只读（源数据维护字段）；如需人工维护可放开为 datetime 输入。
4. 是否需要独立「详情」只读弹框？本设计由编辑弹框承担（表单即详情）；若后续普通用户（非 ADMIN）需要只读浏览，再加详情弹框。
