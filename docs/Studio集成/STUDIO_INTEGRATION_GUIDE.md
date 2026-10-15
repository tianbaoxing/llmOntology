# AgentScope Studio 对接指南

本文档说明其他项目如何对接 `agentscope-studio`，包括服务端口、协议明细和完整对接流程。

---

## 1. 项目定位

AgentScope Studio 是面向 Agent 开发者的**本地可视化工具**，核心能力：

| 能力 | 说明 |
| --- | --- |
| 项目管理 | 以 Project / Run 维度组织 Agent 应用 |
| 运行时交互 | Chatbot 式界面，实时查看 Agent 运行、支持用户输入交互 |
| Tracing | 基于 OpenTelemetry（OTLP 协议）的调用链可视化 |
| 评估 | 面向评估场景的统计分析 |
| Friday Copilot | 内置开发助手，可作为 playground 二次开发 |

技术栈：前端 React/Vite，通信 tRPC over HTTP + Socket.IO(WebSocket)，后端 Node.js/Express，存储 SQLite(TypeORM)。

---

## 2. 对外服务与端口

Studio 启动后默认暴露以下服务端点：

| 端口 | 协议 | 端点 | 用途 |
| --- | --- | --- | --- |
| **3000** | HTTP | `/health` | 健康检查 |
| **3000** | HTTP | `/trpc/*` | tRPC API（注册 Run、推送消息等） |
| **3000** | HTTP | `/v1/traces` | OTLP/HTTP Trace 接收（Protobuf，body 上限 10MB） |
| **3000** | WebSocket | `/friday`、`/python`、`/client` | Socket.IO 命名空间 |
| **4317** | gRPC | OTLP/gRPC | OTLP/gRPC Trace 接收 |

> 注意：
> - 端口被占用时 Studio 会**自动向后寻找可用端口**（最多 +2000），对接前请确认实际端口。
> - 环境变量 `PORT`（HTTP）与 `OTEL_GRPC_PORT`（gRPC）可修改默认端口。
> - Studio 目前**只接收 Trace 类型数据**（不含 metrics/logs）。

---

## 3. 启动 Studio（被对接方）

### 方式 A：npm 安装（推荐）

```bash
npm install -g @agentscope/studio
as_studio
```

### 方式 B：源码运行

```bash
cd agentscope-studio-main
npm install
npm run dev        # 开发模式
```

### 方式 C：Docker

```bash
# 在项目根目录
docker build -f docker/Dockerfile -t agentscope/studio:latest .
docker-compose -f docker/docker-compose.yml up -d
```

docker-compose 编排要点（见 [docker-compose.yml](docker/docker-compose.yml)）：

- 端口映射 `3000:3000`（HTTP）+ `4317:4317`（OTLP gRPC）
- 数据持久化：宿主机 `./data` → 容器 `/app/data`（SQLite 数据库落在此处）
- 健康检查：`curl -f http://localhost:3000/health`，30s 一次，3 次失败判 unhealthy

验证启动成功：

```bash
curl http://localhost:3000/health
```

---

## 4. 对接方式总览

根据对接方类型，选择对应方式：

| 对接方 | 对接方式 | 章节 |
| --- | --- | --- |
| AgentScope Python 应用 | `agentscope.init(studio_url=...)` | 4.1 |
| AgentScope Java 应用 | `agentscope-extensions-studio` 依赖 | 4.2 |
| 任意框架应用（自定义埋点） | OTLP Exporter（gRPC/HTTP） | 4.3 |
| 任意 Java/Python/Go/JS 应用（零侵入） | OpenTelemetry / LoongSuite Agent | 4.4 |
| 自研第三方客户端 | 直接调 tRPC API / Socket.IO | 5 |

---

## 4.1 AgentScope Python 应用对接（标准方式）

**一行代码完成对接**，框架自动完成 Run 注册、消息推送、Trace 上报。

```python
import agentscope

agentscope.init(
    # ...其他初始化参数
    studio_url="http://localhost:3000",  # Studio 的 HTTP 端点
)

# 之后正常编写 Agent 应用，Studio 会自动：
# 1. 注册 Run（tRPC registerRun）
# 2. 通过 Socket.IO /python 命名空间建立实时连接
# 3. 实时推送 Agent 消息（pushMessage）
# 4. 通过 OTLP 上报 Trace
```

对接后打开 `http://localhost:3000/home`，即可在对应 Project/Run 下看到实时交互界面与 Trace。

## 4.2 AgentScope Java 应用对接

**第 1 步**：添加依赖（Maven）：

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-studio</artifactId>
</dependency>
```

或 Gradle：

```gradle
implementation("io.agentscope:agentscope-extensions-studio")
```

**第 2 步**：在应用启动前初始化：

```java
public static void main(String[] args) {
    StudioManager.init()
        .studioUrl("http://localhost:3000")
        .initialize()
        .block();

    // your application code
}
```

## 4.3 任意应用对接 Tracing（OTLP Exporter）

不依赖 AgentScope 框架，只要按 OpenTelemetry 规范上报 Trace 即可被 Studio 接收和展示。两种端点任选：

- **OTLP/gRPC**：`localhost:4317`
- **OTLP/HTTP**：`http://localhost:3000/v1/traces`（Protobuf 编码）

Python 示例（OTLP/HTTP）：

```python
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

tracer_provider = TracerProvider()
exporter = OTLPSpanExporter(endpoint="http://localhost:3000")
tracer_provider.add_span_processor(BatchSpanProcessor(exporter))

# 之后正常创建 Span 即可
tracer = tracer_provider.get_tracer("test_module", "1.0.0")
with tracer.start_as_current_span("test") as span:
    span.set_attribute("test_key", "test_value")
    ...
```

> 提示：遵循 OpenTelemetry GenAI 语义约定（`gen_ai.operation.name`、Inference / InvokeAgent / ExecuteTool span 等）的 Trace 在 Studio 中可获得更精确的展示；不遵循也能正常显示，只是部分信息不会高亮。AgentScope 扩展属性如 `agentscope.function.name` / `agentscope.function.input` / `agentscope.function.output` 用于普通函数调用的展示。

## 4.4 OTel / LoongSuite Agent 对接（零侵入）

适合无法改代码的存量应用。以 OpenTelemetry Java Agent 为例：

```shell
java -javaagent:path/to/opentelemetry-javaagent.jar \
     -Dotel.resource.attributes=service.name=your-service-name \
     -Dotel.traces.exporter=otlp \
     -Dotel.metrics.exporter=console \
     -Dotel.exporter.otlp.traces.endpoint=http://localhost:4317 \
     -jar myapp.jar
```

LoongSuite Python Agent：

```shell
loongsuite-instrument \
    --traces_exporter otlp \
    --metrics_exporter console \
    --service_name your-service-name \
    --exporter_otlp_endpoint 0.0.0.0:4317 \
    python myapp.py
```

支持的 Agent：LoongSuite Python/Go/Java Agent、OpenTelemetry Python/Java/JavaScript Agent，以及任何支持 OTLP Exporter 的采集器。

---

## 5. 底层协议明细（自研客户端对接参考）

如需绕过 AgentScope SDK 直接对接 Studio，可使用以下协议。

### 5.1 tRPC API（HTTP POST，路径前缀 `/trpc`）

核心接口定义在 [router.ts](packages/server/src/trpc/router.ts)：

| 接口 | 类型 | 用途 |
| --- | --- | --- |
| `registerRun` | mutation | 注册一次 Run，入参 `{ id, project, name, timestamp, pid, status }` |
| `pushMessage` | mutation | 向某个 Run 推送 Agent 消息（支持 text/thinking/image/audio/video/tool_use/tool_result 内容块） |
| `requestUserInput` | mutation | 发起用户输入请求（Runtime 交互） |
| `registerReply` | mutation | 注册一轮回复 |
| `pushMessageToFridayApp` | mutation | 向 Friday 应用推送消息 |

### 5.2 Socket.IO 命名空间（WebSocket）

定义在 [socket.ts](packages/server/src/trpc/socket.ts)：

| 命名空间 | 连接方 | 说明 |
| --- | --- | --- |
| `/python` | AgentScope Python SDK | 连接时通过 handshake auth 携带 `run_id` 绑定 Run；断开时 Studio 自动将 Run 置为 DONE 并清理输入请求 |
| `/client` | Web 前端 / 第三方看板 | 加入 project/run room 后接收实时推送：Run 数据、消息、Spans、模型调用数据、用户输入请求 |
| `/friday` | Friday 应用 | 接收 `interrupt` 等事件，实现打断/交互闭环 |

### 5.3 OTLP 端点

- HTTP：`POST /v1/traces`，Content-Type `application/x-protobuf`（也接受 `application/octet-stream`、`application/json`），body 上限 10MB（见 [index.ts](packages/server/src/index.ts)）
- gRPC：标准 OTLP gRPC Trace Service（端口 4317），gRPC 启动失败时自动降级仅用 HTTP 端点

---

## 6. 对接流程（端到端）

```text
┌──────────────────────┐          ┌───────────────────────────────┐
│  对接方（你的项目）    │          │  AgentScope Studio             │
└──────────┬───────────┘          └───────────────┬───────────────┘
           │                                      │
           │            0. 启动 Studio             │
           │            (npm/Docker, 端口3000/4317)│
           │                                      │
           │  1. agentscope.init(studio_url=...)  │
           │     或 StudioManager.init()          │
           │──────────── registerRun ────────────►│  创建 Run 记录
           │                                      │
           │  2. Socket.IO 连接 /python (auth:run_id)
           │─────────────────────────────────────►│  绑定实时通道
           │                                      │
           │  3. 运行期 pushMessage / requestUserInput
           │─────────────────────────────────────►│  实时消息与交互
           │◄──────────── 用户输入(Studio UI) ─────│
           │                                      │
           │  4. OTLP Trace 上报                   │
           │──── gRPC:4317 或 HTTP:/v1/traces ───►│  Trace 落库展示
           │                                      │
           │  5. Run 结束断开连接                   │
           │─────────────────────────────────────►│  Run 状态 → DONE
```

文字版流程：

1. **启动 Studio** 并用 `/health` 确认就绪。
2. **应用侧初始化连接**：AgentScope 应用传入 `studio_url`（Python/Java），或自研客户端调用 `registerRun` + 连接 Socket.IO。
3. **运行期数据上报**：消息经 tRPC/Socket.IO 实时推送；Trace 经 OTLP（gRPC 4317 或 HTTP `/v1/traces`）上报。
4. **Studio 可视化**：在 `http://localhost:3000/home` 查看 Project → Run → Runtime 交互与 Trace 调用链。
5. **结束**：应用断开 `/python` 连接后 Studio 自动收尾 Run 状态。

---

## 7. 常见问题

| 问题 | 说明 |
| --- | --- |
| 端口冲突 | Studio 会自动漂移到可用端口（+1 ~ +2000），对接端点需以启动日志为准；也可用 `PORT` / `OTEL_GRPC_PORT` 显式指定 |
| Trace 上报无数据 | 确认端点正确（gRPC 用 4317，HTTP 用 `http://host:3000`，Python OTLP/HTTP Exporter 会自动补 `/v1/traces`）；确认只上报 Trace（Studio 不收 metrics/logs） |
| Docker 数据丢失 | 必须挂载 `./data:/app/data`，SQLite 数据库在容器 `/app/data` 下 |
| 非 AgentScope 框架数据展示不全 | 遵循 OTel GenAI 语义约定（`gen_ai.*`）可获得最佳展示效果，详见 `docs/tutorial/en/develop/tracing.md` |
