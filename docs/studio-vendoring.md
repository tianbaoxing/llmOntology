# agentscope-extensions-studio 源码内嵌（Vendoring）记录

日期：2026-09-01

## 1. 背景与目标

项目原先通过 Maven 依赖 `io.agentscope:agentscope-extensions-studio:2.0.0`（Run 注册 + 消息推送 + OTLP Trace 上报）。为便于直接修改 Studio 对接源码，将该 jar 的源码内嵌到本项目，移除远程依赖，后续项目直接使用内嵌代码。

## 2. 源码来源

- **Maven Central sources jar**（与本地 `.m2\repo` 中实际使用的二进制 jar 同一次构建产出，逐行一致）：
  `https://repo1.maven.org/maven2/io/agentscope/agentscope-extensions-studio/2.0.0/agentscope-extensions-studio-2.0.0-sources.jar`
- 备选：GitHub tag `v2.0.0`（commit `44c304e`），注意 main 分支已是 2.0.2 之后的代码，结构有变动。

## 3. 内嵌位置

保留原包名 `io.agentscope.core.*`（业务代码 import 零改动、与官方源码 diff 最小），共 28 个类：

```text
src/main/java/io/agentscope/core/
├── studio/                  # StudioManager / StudioClient / StudioWebSocketClient /
│   │                        # StudioMessageHook / StudioConfig / StudioUserAgent
│   └── pojo/                # PushMessageRequest / RegisterRunRequest / RequestUserInputRequest / UserInputMetadata
└── tracing/telemetry/       # TelemetryTracer / GenAiIncubatingAttributes / AttributesExtractors / ...
    └── model/               # InputMessage / OutputMessage / TextPart / ToolCall*Part 等
```

- `io.agentscope.core.studio`：Studio 对接核心（HTTP + Socket.IO）
- `io.agentscope.core.tracing.telemetry`：OTLP Trace 上报，被 StudioManager 强依赖，必须一起内嵌

## 4. pom.xml 修改

### 4.1 删除依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-studio</artifactId>
    <version>2.0.0</version>
</dependency>
```

### 4.2 新增 studio 原第三方依赖（照抄其原版 pom）

| 依赖 | 版本 |
| --- | --- |
| com.squareup.okhttp3:okhttp-jvm | 5.3.2 |
| io.socket:socket.io-client | 2.1.2 |
| io.opentelemetry:opentelemetry-api | 1.61.0 |
| io.opentelemetry:opentelemetry-exporter-otlp | 1.61.0 |
| io.opentelemetry.instrumentation:opentelemetry-reactor-3.1 | 2.27.0-alpha |

agentscope-core 不需要显式声明（已由 agentscope-harness 传递提供）。

### 4.3 关键：覆盖 Boot 管理的 otel 版本

```xml
<properties>
    <!-- 覆盖 Boot 3.2.5 管理的 opentelemetry 1.31.0：内嵌 studio 源码按 otel 1.61.0 编译 -->
    <opentelemetry.version>1.61.0</opentelemetry.version>
</properties>
```

## 5. 踩坑记录

### 踩坑 A：otel 混版导致启动卡死

**现象**：服务启动卡在 bean 创建阶段，9876 不监听；日志报
`NoClassDefFoundError: io/opentelemetry/sdk/common/internal/StandardComponentId$ExporterType`，
且 `StudioManager.initialize().block()` 永不返回。

**根因**：Spring Boot 3.2.5 的 dependencyManagement 把所有 `io.opentelemetry:*` 强制管到 **1.31.0**（`opentelemetry.version=1.31.0`）。显式声明 api/exporter-otlp 1.61.0 只覆盖了直连版本，传递依赖（sdk-common 等）仍被压到 1.31.0 —— 1.61 的 exporter 代码引用了 1.31 里不存在的类。

**为什么之前用 jar 没事**：原 studio jar 的全部 otel 传递依赖被 Boot 均一压成 1.31.0 全家桶，版本自洽。

**修复**：properties 覆盖 `opentelemetry.version=1.61.0`，整套 otel 统一（含 api-incubator 1.61.0-alpha）。用 `mvnw dependency:tree` 验证全树 1.61.0。

### 踩坑 B：PowerShell 调 mvnw

PowerShell 下必须 `.\mvnw.cmd`（带 `.\` 前缀），裸 `mvnw.cmd` 报 CommandNotFound。

### 踩坑 C：clean 失败

强杀 java 进程后若 maven 进程链仍存活会锁住 target 目录，`mvnw clean` 报 "Failed to delete target\classes"；确认进程退出后重试即可。

## 6. 验证结果（2026-09-01 实测）

1. `mvnw clean compile` 通过（28 个内嵌类零错误）
2. 服务启动成功，端口 9876 监听
3. 启动日志确认 Studio 对接链路完整：
   ```text
   StudioManager: Registered run with Studio: 05a89ccf-...
   StudioWebSocketClient: Connected to Studio WebSocket (/python namespace)
   StudioManager: Studio integration initialized successfully
   AgentScopeConfig: AgentScope Studio 对接成功：http://localhost:3000（project=国博藏品图谱）
   ```

## 7. 后续维护

- **修改源码**：直接改 `src/main/java/io/agentscope/core/studio/` 下的文件，重启生效
- **升级注意**：
  - 若升级 agentscope-harness，内嵌 studio 不会跟随升级，需人工核对 core API 变更
  - 官方仓库已有 v2.0.1/v2.0.2（含 2.0.1 bugfix），如需同步可对照 GitHub tag diff 更新内嵌源码
- **验证方式**：启动服务后在 /chat 发消息，Studio（:3000）查看对应 Run 的消息推送与 Trace
