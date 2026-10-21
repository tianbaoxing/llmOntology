# llmOntology 部署与账号说明

## 1. 基础设施（Docker Compose：Neo4j + MySQL）

`docker/docker-compose.yml` 一并编排两个存储（顶层已显式声明 `name: llm-ontology`，切勿删除该行——曾因新旧 compose 同用默认项目名 `docker` 导致旧容器被误删重建）：

| 服务       | 项                    | 值                                        |
| -------- | -------------------- | ---------------------------------------- |
| **Neo4j** | 镜像 / 容器名              | `neo4j:5-community` / `Neo4j`            |
|          | 端口                   | HTTP `7474` / Bolt `7687`                |
|          | 账号                   | `neo4j` / `neo4j2026`                    |
|          | 数据卷                  | `neo4j_data` / `neo4j_logs`（持久化）         |
| **MySQL** | 镜像 / 容器名              | `mysql:8.0` / `ontology-mysql`           |
|          | 端口                   | `3306`                                   |
|          | 账号 / 库                | `root` / `root`，库 `ontology`（容器首次启动自动创建） |
|          | 字符集                  | `utf8mb4` / `utf8mb4_bin`                |
|          | 数据卷                  | `mysql_data`（持久化）                        |

### 启动 / 停止

```powershell
cd <项目根目录>

# 启动（两个存储一起拉起）
docker compose -f docker\docker-compose.yml up -d

# 停止（默认不删卷）
docker compose -f docker\docker-compose.yml down

# 验证 Neo4j
docker exec -i Neo4j cypher-shell -u neo4j -p neo4j2026 "RETURN 1 AS ok;"

# 验证 MySQL（应用首次启动前库为空表，属正常；建表见下节）
docker exec -i ontology-mysql mysql -uroot -proot -e "USE ontology; SHOW TABLES;"
```

## 2. 存储架构（MySQL + Neo4j 分工）

| 存储     | 职责                                                                                                    |
| ------ | ----------------------------------------------------------------------------------------------------- |
| MySQL  | 全部业务数据：用户/角色权限、概念层 schema、构建期问询记录（profile）、去重/互斥规则、内容安全规则、语义改写配置、文件元数据、技能分析、智能体定义、评测（数据集/运行/结果/自定义评测器）、Agent 会话状态 |
| Neo4j  | 实例层图数据（节点与关系）；启动时按概念层 keyProp 自动补建唯一约束                                                                 |

- **建表零手工**：Flyway（`db/migration/V1~V13`）在应用首次启动时自动建表并写入初始数据；
- **存量自动迁移**：早期版本的 JSON 文件 / 目录数据源若仍存在，启动时由迁移 Runner 自动导入 MySQL 并将源归档为 `*.migrated`；表非空时幂等跳过；
- **文件不入库**：`files/`（原件）、`files-md/`（转换产物）、`skills/`、`backups/` 仍为磁盘目录（LLM 按路径访问 + 备份 zip）。

## 3. Spring Boot 服务

| 项          | 值                                                                           |
| ---------- | --------------------------------------------------------------------------- |
| 服务端口       | `9876`                                                                      |
| MySQL 连接   | `jdbc:mysql://localhost:3306/ontology`（root/root）                          |
| Neo4j 连接   | `bolt://localhost:7687`（neo4j/neo4j2026）                                    |
| 配置文件       | `ontology-server/src/main/resources/application.yml`                        |
| LLM 接入     | OpenRouter（OpenAI 兼容协议）：复制 `ontology-server/.env.example` 为 `ontology-server/.env`，填入 `OPENROUTER_API_KEY`；模型在 `app.agent.model` 切换 |
| 页面标题       | `app.title`（默认「LLM 驱动的领域本体知识图谱平台」；本地业务名在 `ontology-server/config/application.yml` 覆盖，或用 `--app.title=xxx`、环境变量 `APP_TITLE`） |
| JDK        | 17                                                                          |
| 构建         | Maven Wrapper（`mvnw.cmd`，Maven 3.9.6；依赖走 Maven Central 与默认本地仓库 `~/.m2`）       |

### 构建 / 启动 / 停止

```powershell
cd <项目根目录>

# 构建（首次较慢，需从 Maven Central 拉依赖；多模块聚合，一次构建全部）
.\mvnw.cmd clean package "-DskipTests" -q

# 启动方式一：Maven Wrapper 直接启动（开发常用，无需先构建）
# 注意：studio 模块首次或变更后需先装入本地仓库（-am 连父 POM 一起装入，
# 否则解析 studio 的 pom 时找不到 ontology-parent）：
#   .\mvnw.cmd -pl ontology-agentscope-studio -am install "-DskipTests" -q

# 多模块 Maven，**agentkit 子模块源码已经更新成 8 参数 build ()，但是本地 maven 仓库里还装着旧版本 agentkit jar（7 参数）
# 编译 ontology-server 的时候，Maven 从本地仓库读取旧 agentkit jar 做类型校验 → 报参数数量不匹配。
# mvn clean install -DskipTests

.\mvnw.cmd -pl ontology-server spring-boot:run
.\mvnw.cmd -pl ontology-server spring-boot:run "-Dspring-boot.run.arguments=--app.agent.studio-url=http://127.0.0.1:3000"

# 启动方式二：构建后运行 jar
cd ontology-server
java -jar target\ontology-server-0.1.0-SNAPSHOT.jar

# 健康检查（登录页初始化状态接口，免登录）
curl http://localhost:9876/api/app
```

运行时工作目录约定：`spring-boot:run` 以 `ontology-server/` 为 cwd，jar 方式也必须在该目录启动；
所有相对路径（`data/`、`files/`、`files-md/`、`skills/`、`backups/`、`.env`）均在 `ontology-server/` 下解析。

停止服务：在运行窗口 Ctrl+C，或 `Get-Process java | Stop-Process`（会杀掉所有 java 进程，注意甄别）。

## 4. 管理页面（页面路由）

静态页位于 `src/main/resources/static/pages/*.html`（原生单文件，无外部依赖），由 `shared/web/PageController` 提供路由，页面仅调用 `/api/**` 接口：

| 路由                 | 页面                                  | 权限       |
| ------------------ | ----------------------------------- | -------- |
| `/`                | 控制台首页（概览统计）                          | 登录       |
| `/login`           | 登录页（系统初始化向导 / 登录 / 注册，公开）             | 公开       |
| `/data`            | 图谱管理（概念层 + 图数据 + 去重/互斥/内容安全/改写配置）      | ADMIN    |
| `/files`           | 知识库（文件上传与描述管理）                       | 登录       |
| `/wiki`            | Wiki 知识库（files-md/ 转换产物的只读呈现）         | ADMIN    |
| `/chat`            | AI 问答（SSE 流式，技能快捷插入）                 | 登录       |
| `/agents`          | 智能体搭建（向导 / 列表 / 试运行）                  | ADMIN    |
| `/dashboard`       | 驾驶舱（AI 用量统计，ADMIN 可查看指定用户）            | 登录       |
| `/session-detail`  | 会话详情（上下文构成双图 + 消息明细）                 | 登录       |
| `/eval`            | 评测中心（数据集 / 评测器 / 运行 / 结果 / Diff）       | 登录       |
| `/users`           | 用户管理（审核 / 启停 / 重置密码 / 记忆查看）           | ADMIN    |
| `/instances`       | 实例列表（`?label=&page=&q=`）              | 登录       |
| `/graph`           | 实例图谱浏览（`?label=&key=` 定位中心节点）          | 登录       |

## 5. 接口一览

接口按调用方分两个入口（同源同 service，双入口隔离）：

- `/api/**` — 管理页调用（Session 认证，`AuthInterceptor` 拦截）
- `/api/llm/**` — LLM / skill 调用方专属（免登录，供 Agent 自服务构建与查询）

### 接口域总览

| 域         | Admin 平面                                | LLM 平面                  | 说明                                     |
| --------- | --------------------------------------- | ----------------------- | -------------------------------------- |
| 认证 / 用户   | `/api`（登录/注册/初始化）、`/api/users`、`/api/admin/role-permissions`、`/api/prompts` | —                       | 用户 CRUD/审核、角色权限、用户提示词库                  |
| 应用信息      | `/api/app`                              | —                       | 标题等（登录页初始化状态判断）                        |
| 概念层       | `/api/schema`                           | `/api/llm/schema`       | 详见下表                                   |
| 图数据       | `/api/graph`                            | `/api/llm/graph`        | 详见下表；query / neighbors / batch 仅 LLM 入口 |
| 去重规则      | `/api/graph/dedup`                      | —                       | 规则集 CRUD + 图谱去重扫描                      |
| 互斥规则      | `/api/disjoint-rules`                   | `/api/llm/disjoint-rules` | 规则 CRUD + 冲突检查                         |
| 图谱质量      | `/api/graph/quality`                    | —                       | 图质量诊断报告                                |
| 内容安全      | `/api/content-security`                 | —                       | 规则/白名单管理 + 注入检测                        |
| 语义改写      | `/api/rewrite-config`                   | —                       | 四功能启停与提示词配置                            |
| 文件知识库     | `/api/files`                            | `/api/llm/files`        | 上传/列表/描述/删除；LLM 侧返回可直接读取的 MD/文本路径       |
| Wiki      | `/api/wiki`                             | —                       | 列表/单篇/元信息编辑/按来源文件查询                    |
| 备份        | `/api/backups`                          | —                       | 全量异步备份（图数据 + 文件 + MySQL 业务表 → zip，进度轮询）  |
| AI 会话     | `/api/agent`                            | —                       | SSE 聊天 / 会话列表与详情 / 子智能体                  |
| 智能体搭建     | `/api/agents`                           | —                       | 智能体定义 CRUD / 导出 / 试运行                   |
| 驾驶舱       | `/api/dashboard`                        | —                       | 用量统计 / 会话明细下钻                           |
| 技能        | `/api/skills`                           | —                       | 技能使用分析                                 |
| 评测        | `/api/eval`                             | —                       | 数据集 / 评测器 / 运行 / 结果下载 / Run Diff        |
| 本体查询      | —                                       | `/api/llm/ontology-query` | 详见下表                                   |
| 构建期问询记录   | —                                       | `/api/llm/profile`      | 详见下表                                   |

完整端点以 Swagger UI 为准：`http://localhost:9876/swagger-ui/index.html`（免登录，AuthInterceptor 放行 Swagger UI 与 `/v3/api-docs/**`）。

### 概念层 `/api/schema`（admin）与 `/api/llm/schema`（LLM）

两个入口均提供以下接口（LLM 侧多一个 relations 全量视图）：

| 方法     | 路径                          | 说明                                       |
| ------ | --------------------------- | ---------------------------------------- |
| GET    | `/schema`                   | 全量 schema（类型+关系+属性+实时 count，可注入 LLM 上下文） |
| GET    | `/schema/classes`           | 类型列表（摘要+count）                           |
| GET    | `/schema/classes/{name}`    | 单类详情（属性+出边声明+入边推导+count）                 |
| POST   | `/schema/classes`           | 新增类（自动建唯一约束+热更新白名单）                      |
| PUT    | `/schema/classes/{name}`    | 更新类（name/keyProp 不可变；被删关系仍有实例 → 409）     |
| DELETE | `/schema/classes/{name}`    | 删除类（被引用或有实例 → 409）                       |
| GET    | `/api/llm/schema/relations` | 关系扁平视图（全量只读，仅 LLM 入口）                    |

> 概念层唯一事实源为 MySQL（`graph_schema_meta` / `graph_class` / `graph_class_prop` / `graph_rel_def` 四表，Flyway 自动建表）。首次启动初始化为空 schema，无预置类，由用户（或 ontology-builder 技能）按场景构建；Schema CRUD 提交后热更新 GraphSchema 白名单，Graph API 即时生效。

### 图数据 `/api/graph`（admin）与 `/api/llm/graph`（LLM）

单条 CRUD / stats / node 两入口均提供；query、neighbors、batch 仅 LLM 入口：

| 方法     | 路径                                                | 说明                             |
| ------ | ------------------------------------------------- | ------------------------------ |
| POST   | `/api/llm/graph/query`                            | 通用只读 Cypher（写语句被拒绝；对账校验用）      |
| GET    | `/graph/stats`                                    | 各 label 节点数 / 各 type 关系数       |
| GET    | `/graph/nodes?label=&page=&size=`                 | 分页实例列表（管理页"实例数"跳转页数据源）        |
| GET    | `/graph/node/{label}/{key}`                       | 节点详情 + 出入边                     |
| GET    | `/api/llm/graph/neighbors?label=&key=&depth=1..3` | 邻域子图（仅 LLM 入口）                 |
| POST   | `/graph/nodes`                                    | 创建单个节点（MERGE 幂等）               |
| PUT    | `/graph/nodes/{label}/{key}`                      | 更新节点属性（keyProp 不可改）            |
| DELETE | `/graph/nodes/{label}/{key}?detach=true`          | 删除节点（detach 默认 true 连带删关系）     |
| POST   | `/graph/rels`                                     | 创建关系（签名须匹配 schema 声明，两端须存在）    |
| PUT    | `/graph/rels`                                     | 更新关系属性（type+两端键定位）             |
| DELETE | `/graph/rels`                                     | 删除关系（type+两端键定位）               |
| POST   | `/api/llm/graph/nodes/batch`                      | 批量幂等写节点（仅 LLM 入口）              |
| POST   | `/api/llm/graph/rels/batch`                       | 批量幂等写关系（仅 LLM 入口）              |

### 构建期问询记录 `/api/llm/profile`（LLM）

| 方法   | 路径                 | 说明                                            |
| ---- | ------------------ | --------------------------------------------- |
| GET  | `/api/llm/profile` | 全量问询记录（LLM 构建各阶段回看上下文）                        |
| POST | `/api/llm/profile` | 批量保存问答（按 key 原位置覆盖 upsert；存储于 MySQL `ontology_profile` 表） |

### 本体查询 `/api/llm/ontology-query`（LLM / skill）

| 方法   | 路径                              | 说明                                        |
| --- | ------------------------------- | ----------------------------------------- |
| POST | `/api/llm/ontology-query/query` | 本体查询专用只读 Cypher 执行入口（拒绝多语句和写操作）           |
| POST | `/api/llm/ontology-query/ask`   | 自然语言问答入口（固定流程：schema -> graph -> profile） |

## 6. 账号与登录

| 项     | 值                                            |
| ----- | -------------------------------------------- |
| 用户数据  | MySQL `sys_user` 表（Flyway 自动建表；无 JSON 文件存储）   |
| 密码存储  | PBKDF2WithHmacSHA256（210k 迭代 / 16B 盐，不存明文）    |
| 登录入口  | `http://localhost:9876/login`（未登录访问其他页面自动跳转）  |

### 管理员账号

- **无系统默认密码**：首次启动（尚无任何 ADMIN 用户）时，登录页进入「系统初始化」向导，现场设置 admin 密码并直接登录；初始化仅允许一次，防止重复初始化重置系统。
- 本部署 admin 密码初始化时设为 `admin123`（用户名 `admin`，角色 ADMIN）。

### 普通用户：注册 → 管理员审核 → 登录

1. 登录页自助注册（用户名 2-32 位字母/数字/下划线/中文，密码 6-64 位），注册成功后状态为 `PENDING`。
2. `PENDING` 用户登录会被拒绝（提示「账号待管理员审核，暂时无法登录」）。
3. 管理员审核通过（`PENDING → ACTIVE`）后方可正常登录。
4. 管理员另可启用 / 禁用、重置密码、删除用户（不能操作自己；系统须保留至少一个可用管理员）。
