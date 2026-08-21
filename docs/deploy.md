# llmOntology 部署与账号说明

## 1. Neo4j 安装（Docker）

| 项          | 值                                              |
| ---------- | ---------------------------------------------- |
| 容器名        | `Neo4j`（大写 N，与旧容器 `neo4j` 区分）                  |
| 镜像         | `neo4j:5-community`                            |
| HTTP 端口    | `7474`（浏览器访问 http://localhost:7474）          |
| Bolt 端口    | `7687`                                         |
| 用户名        | `neo4j`                                        |
| 密码         | `guobo2026`                                    |
| 数据卷        | `neo4j_neo4j2_data` / `neo4j_neo4j2_logs`（持久化） |
| compose 文件 | `docker/docker-compose.yml`（项目名 `name: Neo4j`） |

### 启动 / 停止

```powershell
cd c:\me\kaiyuan\history\llmOntology

# 启动 Neo4j
docker compose -f docker\docker-compose.yml up -d

# 停止
docker compose -f docker\docker-compose.yml down

# 验证连通
docker exec -i Neo4j cypher-shell -u neo4j -p guobo2026 "RETURN 1 AS ok;"
```

> 注意：compose 已显式声明 `name: Neo4j`，切勿删除该行。曾因新旧 compose 同用默认项目名 `docker` 导致旧容器被误删重建。

## 2. Spring Boot 服务

| 项        | 值                                                                           |
| -------- | --------------------------------------------------------------------------- |
| 服务端口     | `9876`                                                                      |
| Neo4j 连接 | `bolt://localhost:7687`                                                     |
| 配置文件     | `src/main/resources/application.yml`                                        |
| 页面标题     | `app.title`（默认「国博藏品图谱 · 管理控制台」，启动可用 `--app.title=xxx` 或环境变量 `APP_TITLE` 覆盖） |
| JDK      | 17                                                                          |
| 构建       | Maven Wrapper（`mvnw.cmd`，Maven 3.9.6，仓库在项目内 `.m2\repo`）                     |

### 构建 / 启动 / 停止

```powershell
cd c:\me\kaiyuan\history\llmOntology

# 构建（首次较慢，依赖在项目内 .m2\repo）
.\mvnw.cmd clean package "-DskipTests" -q

# 启动（后台运行，启动时自动创建 8 个唯一约束）
java -jar target\ontology-0.1.0-SNAPSHOT.jar

# 健康检查
curl http://localhost:9876/api/graph/stats
```

停止服务：在运行窗口 Ctrl+C，或 `Get-Process java | Stop-Process`（会杀掉所有 java 进程，注意甄别）。

## 3. 概念层（schema 元数据）

| 项     | 值                                                           |
| ----- | ----------------------------------------------------------- |
| 唯一事实源 | `data/graph-schema.json`（首次启动初始化为空 schema，可写）               |
| 初始状态  | 概念层为空，无预置类；由用户（或 ontology-build skill）按场景构建                 |
| 结构    | classes\[]（含 properties + relations 内嵌声明）+ version（每次变更 +1） |
| 白名单联动 | Schema CRUD 落盘后热更新 GraphSchema 白名单，Graph API 即时生效           |

## 4. 接口一览

接口按调用方分两个入口（同源同 service，双入口隔离）：

- `/api/**` — admin 管理页调用（页面仅依赖此入口）
- `/api/llm/**` — LLM / ontology-build skill 调用（构建期问询、概念抽取、批量灌数、对账校验）

### 应用信息 `/api/app`（admin）

| 方法  | 路径         | 说明                        |
| --- | ---------- | ------------------------- |
| GET | `/api/app` | 应用信息（管理页标题 `app.title` 等） |

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

### 图数据 `/api/graph`（admin）与 `/api/llm/graph`（LLM）

单条 CRUD / stats / node 两入口均提供；query、neighbors、batch 仅 LLM 入口：

| 方法     | 路径                                                | 说明                             |
| ------ | ------------------------------------------------- | ------------------------------ |
| POST   | `/api/llm/graph/query`                            | 通用只读 Cypher（写语句被拒绝；对账校验用）      |
| GET    | `/graph/stats`                                    | 各 label 节点数 / 各 type 关系数       |
| GET    | `/graph/nodes?label=&page=&size=`                 | 分页实例列表（每页 50 条，管理页"实例数"跳转页数据源） |
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

| 方法   | 路径                 | 说明                                                |
| ---- | ------------------ | ------------------------------------------------- |
| GET  | `/api/llm/profile` | 全量问询记录（LLM 构建各阶段回看上下文）                            |
| POST | `/api/llm/profile` | 批量保存问答（按 key upsert，`data/ontology-profile.json`） |

### 管理员页面 `/admin`

| 项    | 值                                                               |
| ---- | --------------------------------------------------------------- |
| 入口   | `http://localhost:9876/admin`（AdminPageController forward 到静态页） |
| 页面文件 | `src/main/resources/static/admin/index.html`（单文件，无外部依赖）         |
| 功能   | 概览统计 / 概念层管理（类 CRUD）/ 图数据管理（节点与关系 CRUD）                         |
| 数据通道 | 页面仅调用 `/api/**` 接口，无独立后端逻辑                                      |

> controller 分三个入口包：`controller/api/`（admin REST）、`controller/llmapi/`（LLM/skill REST）、`controller/admin/`（页面入口，后续认证在此拦截）。

### 本体查询 `/api/llm/ontology-query`（LLM / skill）

| 方法   | 路径                              | 说明                                        |
| ---- | ------------------------------- | ----------------------------------------- |
| POST | `/api/llm/ontology-query/query` | 本体查询专用只读 Cypher 执行入口（拒绝多语句和写操作）           |
| POST | `/api/llm/ontology-query/ask`   | 自然语言问答入口（固定流程：schema -> graph -> profile） |

## 5. 账号与登录

| 项    | 值                                                 |
| ---- | ------------------------------------------------- |
| 用户数据 | `data/users.json`（唯一事实源，`app.auth.users-file` 可配） |
| 密码存储 | PBKDF2WithHmacSHA256（210k 迭代 / 16B 盐，不存明文）        |
| 登录入口 | `http://localhost:9876/admin`（未登录自动跳转登录页）         |

### 管理员账号

- **无系统默认密码**：首次启动（尚无任何 ADMIN 用户）时，登录页进入「系统初始化」向导，现场设置 admin 密码并直接登录；初始化仅允许一次，防止重复初始化重置系统。
- 本部署 admin 密码初始化时设为 `admin123`（用户名 `admin`，角色 ADMIN）。

### 普通用户：注册 → 管理员审核 → 登录

1. 登录页自助注册（用户名 2-32 位字母/数字/下划线/中文，密码 6-64 位），注册成功后状态为 `PENDING`。
2. `PENDING` 用户登录会被拒绝（提示「账号待管理员审核，暂时无法登录」）。
3. 管理员审核通过（`PENDING → ACTIVE`）后方可正常登录。
4. 管理员另可启用 / 禁用、重置密码、删除用户（不能操作自己；系统须保留至少一个可用管理员）。

