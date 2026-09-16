# llmOntology — LLM 驱动的领域本体知识图谱平台

以「国博藏品图谱」为示例数据集：**6036 节点 / 21422 关系**的博物馆藏品知识图谱，配合 AI Agent 实现自然语言问答与图谱构建。

## 项目价值

传统知识图谱构建需要领域专家设计 Schema、工程师写抽取规则、人工校验数据，门槛高、周期长。本项目探索了一条 **LLM 驱动**的路线：

- **零代码构建本体**：上传领域数据文件，AI Agent（ontology-builder 技能）通过问询 → 概念分析 → Schema 写入 → 批量灌数 → 对账校验六步流程，自动完成「概念层 + 实例层」双层图谱构建；
- **自然语言即查询接口**：用户直接提问（如"查询有多少墓"），Agent 自主拆解问题 → 查 Schema → 生成 Cypher → 取证回答，全程只读红线保护图数据安全；
- **评测体系闭环**：内置 26 个评测器（规则 11 / LLM 15）+ 自定义提示词评测器 + Agentic 取证评测 + Run Diff 回归对比，让「Agent 问答质量」可度量、可回归、可迭代——这是大多数 LLM 图谱项目缺失的一环；
- **生产级工程细节**：Schema 白名单热更新、写接口幂等（MERGE）、双 API 平面隔离（管理页 / LLM 调用方）、异步任务 + 进度轮询、全量备份。

## 架构

### 技术栈

| 层 | 技术 |
|---|---|
| 服务框架 | Spring Boot 3.2.5 / JDK 17 / Maven Wrapper 3.9.6 |
| 图数据库 | Neo4j 5 Community（neo4j-java-driver 5.14 原生驱动） |
| Agent 框架 | AgentScope Java 2.0 Harness（ReAct 循环 + SSE 事件流） |
| LLM 接入 | OpenRouter（OpenAI 兼容协议），支持 deepseek / qwen / glm 等 |
| API 文档 | springdoc-openapi（Swagger UI 分组：llm / admin） |
| 前端 | 原生 HTML/JS 单文件静态页（无外部依赖，公共资源复用） |

### 模块结构

```
src/main/java/com/conca/ontology/
├── controller/
│   ├── api/            # 管理页 REST（/api/**，需登录）
│   ├── llmapi/         # LLM/skill 专用 REST（/api/llm/**，免登录冻结区）
│   ├── ontologyquery/  # 本体查询入口（query / ask）
│   └── page/           # 静态页面路由（/、/login、/chat、/graph …）
├── service/            # 图谱读写、Schema、导入、备份、Agent 会话、评测
├── eval/               # 评测体系：26 内置评测器 + 自定义 + Agentic 取证
├── agent/              # 原生 HTTP 工具（graph_query 等只读代理）
├── dto/                # 全量 record DTO（@Schema 注解）
└── config/             # Neo4j / 认证拦截器 / GraphSchema 白名单初始化
```

### 双 API 平面

- `/api/**` — 管理页调用（Session 认证，AuthInterceptor 拦截）
- `/api/llm/**` — LLM / skill 调用方专属（免登录，供 Agent 自服务构建与查询）

## 示例数据集怎么来的

「国博藏品图谱」的数据来自中国国家博物馆官网公开的藏品介绍页，处理链路分三步（爬虫与转换项目在姊妹仓库 `historyScraper/`、`historyOntology/`）：

1. **采集**：调用官网检索接口（`SearchAll`，需 `X-Requested-With: XMLHttpRequest` 请求头），滑动窗口分页循环抓取直到空页，共 **264 页 × 21 条 ≈ 5524 件藏品**。接口返回 JSON 已含名称、介绍全文、规格、原图 URL，无需爬详情页；原始 JSON 按页落盘备份，图片断点续传下载到本地；
2. **清洗入库**：规格字段去 Word 粘贴 HTML、classification 编码破解（`0;-;质地;品类;年代;细类` 分号分段按码值扫描解码）后入 MySQL（Upsert 幂等可重跑）；
3. **图谱化**：规则解析中文器物名结构（如"定窑白釉莲纹大碗" = 窑口 + 釉色 + 纹饰 + 器型）+ 介绍文本正则抽取，生成本项目可导入的节点/关系数据，最终形成 8 类节点（CulturalRelic 5524 / Era / Place / Category / Material / Theme / CollectionSource 等）与 15 种关系（OF_ERA / EXCAVATED_AT / MADE_OF / SUB_ERA_OF …）。

> 说明：官网数据有噪声（如规格"口径318厘米"实为 31.8），清洗层已处理。数据仅限本地个人研究使用。

## 功能一览

| 页面 | 功能 |
|---|---|
| `/` 控制台 | 应用概览与统计 |
| `/data` 图谱管理 | 概念层（类/属性/关系 CRUD，写后热更新白名单）+ 图数据（节点/关系 CRUD，双 Tab 联动） |
| `/graph` 图谱浏览 | 实例邻域子图可视化（支持大邻居分页加载） |
| `/instances` 实例列表 | 分页浏览全量实例 |
| `/chat` AI 问答 | SSE 实时展示 Agent 思考与工具执行过程；技能快捷插入 chips |
| `/files` 知识库 | 领域数据文件上传（文本类白名单）+ 描述管理 |
| `/eval` 评测中心 | 见下方评测体系 |
| `/dashboard` 驾驶舱 | 使用统计（支持 ADMIN 查看指定用户） |
| `/backup` 备份 | 全量异步备份（图数据 + 文件 + Schema → zip，进度轮询，文件名编码统计） |
| `/users` 用户管理 | 注册 → 管理员审核 → 登录；PBKDF2 密码存储；启用/禁用/重置密码 |

### AI 问答（Agent）

- AgentScope Harness ReAct 循环（maxIters=8），OpenRouter 模型可配置切换；
- 5 个原生只读 HTTP 工具（ontology_schema / graph_query / ontology_ask / list_uploaded_files / get_profile）：双保险只读预检、无 LIMIT 自动追加、输出字符兜底、错误前缀分层；
- 2 个内置技能：`ontology-builder`（六步构建流程）/ `ontology-query`（查询主线 + 证据规范），启动自动装载，chat 页可一键插入；
- 会话状态留痕，支持 AgentScope Studio 可视化对接。

### 评测体系（/eval）

- **26 个内置评测器**：规则 11 个（string_exact / number_accuracy / text_similarity / tool_call_success / action_loop / observation_information_gain …）+ LLM 15 个（correctness / hallucination / tool_selection / trajectory_accuracy / 多轮 4 件套 …）；
- **Agentic 取证评测**：judge 本身是带只读图谱工具的 Agent，自主查证回答中的断言，输出证据链（claim + Cypher + 图谱返回摘要 + confirmed）；
- **自定义评测器**：页面输入提示词即可创建（criteria / template 两种模式），无需写代码；
- **多次判分取平均**（1-3 次可配）降噪；**多轮对话样本**（turns 脚本，跨轮记忆真实生效）；
- **Run Diff 回归对比**：两次评测结果按总体指标 / 评测器翻转矩阵 / 样本级 delta 对比，datasetHash 检测数据集漂移。

## 快速开始

### 1. 启动 Neo4j

```powershell
docker compose -f docker\docker-compose.yml up -d
```

### 2. 配置环境

```powershell
Copy-Item ontology-server\.env.example ontology-server\.env
# 编辑 ontology-server\.env 填入 OPENROUTER_API_KEY（https://openrouter.ai/keys）
```

Neo4j 连接、端口等在 `ontology-server/src/main/resources/application.yml` 中配置（默认 bolt://localhost:7687，服务端口 9876）。

### 3. 构建并启动

```powershell
.\mvnw.cmd clean package "-DskipTests"
# jar 方式（工作目录须为 ontology-server，data/files/skills/.env 相对 cwd 解析）
cd ontology-server; java -jar target\ontology-server-0.1.0-SNAPSHOT.jar
# 或开发模式（studio 模块变更后先 .\mvnw.cmd -pl ontology-agentscope-studio -am install "-DskipTests"）
cd ..; .\mvnw.cmd -pl ontology-server spring-boot:run
```

### 4. 初始化

访问 http://localhost:9876 ，首次启动进入「系统初始化」向导创建管理员账号，然后在 `/files` 上传领域数据、到 `/chat` 对 Agent 说"帮我构建本体"即可开始。

API 文档：http://localhost:9876/swagger-ui/index.html

## License & 声明

- 代码开源（LICENSE 待定）；
- 示例数据来自中国国家博物馆官网公开页面，仅供学习研究，请勿商用或再分发；
- `data/` 目录（用户/图谱/评测数据）与 `.env` 均不入库。
