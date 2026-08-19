# historyOntology2 部署与账号说明

## 1. Neo4j 安装（Docker）

| 项 | 值 |
|---|---|
| 容器名 | `Neo4j`（大写 N，与旧容器 `neo4j` 区分） |
| 镜像 | `neo4j:5-community` |
| HTTP 端口 | `17474`（浏览器访问 http://localhost:17474） |
| Bolt 端口 | `17687` |
| 用户名 | `neo4j` |
| 密码 | `guobo2026` |
| 数据卷 | `neo4j_neo4j2_data` / `neo4j_neo4j2_logs`（持久化） |
| compose 文件 | `docker/docker-compose.yml`（项目名 `name: Neo4j`） |

### 启动 / 停止

```powershell
cd c:\me\kaiyuan\history\historyOntology2

# 启动 Neo4j
docker compose -f docker\docker-compose.yml up -d

# 停止
docker compose -f docker\docker-compose.yml down

# 验证连通
docker exec -i Neo4j cypher-shell -u neo4j -p guobo2026 "RETURN 1 AS ok;"
```

> 注意：compose 已显式声明 `name: Neo4j`，切勿删除该行。曾因新旧 compose 同用默认项目名 `docker` 导致旧容器被误删重建。

## 2. Spring Boot 服务

| 项 | 值 |
|---|---|
| 服务端口 | `18080` |
| Neo4j 连接 | `bolt://localhost:17687` |
| 配置文件 | `src/main/resources/application.yml` |
| JDK | 17 |
| 构建 | Maven Wrapper（`mvnw.cmd`，Maven 3.9.6，仓库在项目内 `.m2\repo`） |

### 构建 / 启动 / 停止

```powershell
cd c:\me\kaiyuan\history\historyOntology2

# 构建（首次较慢，依赖在项目内 .m2\repo）
.\mvnw.cmd clean package "-DskipTests" -q

# 启动（后台运行，启动时自动创建 8 个唯一约束）
java -jar target\history-ontology2-0.1.0-SNAPSHOT.jar

# 健康检查
curl http://localhost:18080/api/graph/stats
```

停止服务：在运行窗口 Ctrl+C，或 `Get-Process java | Stop-Process`（会杀掉所有 java 进程，注意甄别）。

## 3. 概念层（schema 元数据）

| 项 | 值 |
|---|---|
| 唯一事实源 | `data/graph-schema.json`（首次启动从 classpath 模板复制，可写） |
| 模板 | `src/main/resources/graph-schema.json` |
| 结构 | classes[]（含 properties + relations 内嵌声明）+ version（每次变更 +1） |
| 白名单联动 | Schema CRUD 落盘后热更新 GraphSchema 白名单，Graph API 即时生效 |

## 4. 接口一览

### 概念层 `/api/schema`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/schema` | 全量 schema（类型+关系+属性+实时 count，可注入 LLM 上下文） |
| GET | `/api/schema/classes` | 类型列表（摘要+count） |
| GET | `/api/schema/classes/{name}` | 单类详情（属性+出边声明+入边推导+count） |
| POST | `/api/schema/classes` | 新增类（自动建唯一约束+热更新白名单） |
| PUT | `/api/schema/classes/{name}` | 更新类（name/keyProp 不可变；被删关系仍有实例 → 409） |
| DELETE | `/api/schema/classes/{name}` | 删除类（被引用或有实例 → 409） |
| GET | `/api/schema/relations` | 关系扁平视图（全量只读） |

### 图数据 `/api/graph`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/graph/query` | 通用只读 Cypher（写语句被拒绝） |
| GET | `/api/graph/stats` | 各 label 节点数 / 各 type 关系数 |
| GET | `/api/graph/node/{label}/{key}` | 节点详情 + 出入边 |
| GET | `/api/graph/neighbors?label=&key=&depth=1..3` | 邻域子图 |
| POST | `/api/graph/nodes` | 创建单个节点（MERGE 幂等） |
| PUT | `/api/graph/nodes/{label}/{key}` | 更新节点属性（keyProp 不可改） |
| DELETE | `/api/graph/nodes/{label}/{key}?detach=true` | 删除节点（detach 默认 true 连带删关系） |
| POST | `/api/graph/rels` | 创建关系（签名须匹配 schema 声明，两端须存在） |
| PUT | `/api/graph/rels` | 更新关系属性（type+两端键定位） |
| DELETE | `/api/graph/rels` | 删除关系（type+两端键定位） |
| POST | `/api/graph/nodes/batch` | 批量幂等写节点 |
| POST | `/api/graph/rels/batch` | 批量幂等写关系 |

### 管理员页面 `/admin`

| 项 | 值 |
|---|---|
| 入口 | `http://localhost:18080/admin`（AdminPageController forward 到静态页） |
| 页面文件 | `src/main/resources/static/admin/index.html`（单文件，无外部依赖） |
| 功能 | 概览统计 / 概念层管理（类 CRUD）/ 图数据管理（节点与关系 CRUD） |
| 数据通道 | 页面仅调用 `/api/**` 接口，无独立后端逻辑 |

> controller 分两个入口包：`controller/api/`（REST 接口）与 `controller/admin/`（页面入口，后续认证在此拦截）。
