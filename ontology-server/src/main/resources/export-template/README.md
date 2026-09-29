# {{PROJECT_NAME}}

由 llmOntology 平台导出的 **AgentScope 2.0 原生 Spring Boot 智能体工程**，可深度二次开发。

槽位定义（提示词/模型/HTTP 工具/MCP）已全部落入 `src/main/resources/application.yml`（`agent.*` 配置节），
装配代码在 `config/AgentScopeConfig.java`（`HarnessAgent.builder()` 显式装配，符合 AgentScope 2.0 规范），
不依赖 llmOntology 平台任何私有类。

## 快速开始

前置：JDK 17+。

**设置模型密钥（必须）**——密钥不随工程导出，经环境变量 `AGENT_API_KEY` 注入：

```bat
:: Windows（CMD）
set AGENT_API_KEY=sk-xxx

:: Windows（PowerShell）
$env:AGENT_API_KEY = "sk-xxx"

# Linux / macOS
export AGENT_API_KEY=sk-xxx
```

**开发模式运行**（浏览器访问 http://127.0.0.1:18080）：

```bat
mvnw.cmd spring-boot:run
```

```bash
# Linux / macOS 首次需赋予执行权限
chmod +x mvnw
./mvnw spring-boot:run
```

**打包独立运行**：

```bat
mvnw.cmd -DskipTests package
set AGENT_API_KEY=sk-xxx
java -jar target\{{PROJECT_NAME}}-1.0.0.jar
```

> 未设置 `AGENT_API_KEY` 时启动失败并提示，属预期行为（见 `agent.model.api-key` 占位符）。

## 工程结构

```
{{PROJECT_NAME}}/
├── pom.xml                          # Spring Boot 3.2.5 + AgentScope 2.0（版本见 properties）
├── mvnw / mvnw.cmd / .mvn/          # Maven Wrapper（自带，无需安装 Maven）
├── src/main/java/{{PACKAGE_PATH}}/
│   ├── AgentApplication.java        # 启动类（@EnableConfigurationProperties）
│   ├── config/
│   │   ├── AgentProperties.java     # agent.* 配置绑定（槽位 → 配置）
│   │   └── AgentScopeConfig.java    # HarnessAgent 装配（模型/工具/MCP/工作区）
│   ├── tool/
│   │   └── HttpJsonTool.java        # 通用 JSON HTTP 管道工具（path 白名单约束）
│   └── web/
│       ├── AgentChatController.java # POST /api/chat（SSE）+ GET /api/meta
│       └── AgentEventWriter.java    # AgentEvent → SSE 帧（事件契约见下）
├── src/main/resources/
│   ├── application.yml              # 全部槽位配置（改这里即可调提示词/模型/工具）
│   └── static/index.html            # 极简聊天页（开箱即用）
├── workspace/
│   ├── skills/                      # AgentScope 技能（SKILL.md 规范，自平台拷贝）
│   └── subagents/                   # 声明式子智能体（frontmatter .md）
└── data/state/                      # 会话记忆（运行时生成；删除即清空记忆）
```

## 配置说明（application.yml → agent.*）

| 配置 | 说明 |
|---|---|
| `name` / `description` / `greeting` | 智能体名称/描述/开场白（页面展示） |
| `sys-prompt` | 系统提示词（块标量，原样保留换行缩进） |
| `max-iters` | ReAct 推理轮次上限：0 = 默认 6，上限 20（启动时收口） |
| `model.base-url` / `model.model-name` | OpenAI 兼容协议接入（baseUrl + 模型名） |
| `model.api-key` | `${AGENT_API_KEY:}` 占位：经环境变量注入，勿写明文 |
| `http-tools[]` | HTTP 工具声明：name/description/endpoint/path-prefix |
| `mcps[]` | MCP 外接：name/url/transport（Streamable HTTP 优先，失败退回 SSE） |

## 二次开发指引

- **调提示词/换模型/改工具端点**：直接改 `application.yml`，无需动代码
- **新增 Java 工具**：在 `tool/` 下新建类（实现 `io.agentscope.core.tool.AgentTool`，
  参照 `HttpJsonTool`），并在 `AgentScopeConfig` 中 `toolkit.registerAgentTool(...)` 注册
- **新增技能**：`workspace/skills/<技能名>/SKILL.md`（AgentScope Harness 技能规范，整目录）
- **新增子智能体**：`workspace/subagents/<名称>.md`（frontmatter：name/description/emoji/color + 提示词）
- **改对话接口/事件契约**：`web/AgentChatController.java` + `web/AgentEventWriter.java`
- **会话记忆**：`data/state/`（JsonFileAgentStateStore 按 sessionId 落盘，删除目录即清空）

## SSE 事件契约（POST /api/chat）

请求体：`{"message": "...", "sessionId": "可选，留空由服务端分配"}`

响应为 `text/event-stream`，帧序列与 llmOntology 平台 AI 问答一致：

| 事件 | 载荷 |
|---|---|
| `session` | `{"sessionId": "..."}`（首帧，回传会话 id） |
| `round` | `phase: start/end`（含 replyId；end 含 tokens 用量） |
| `thinking` | `{"delta": "..."}`（思考增量） |
| `tool` | `phase: start/args/end/result-start/result-delta/result-end` |
| `answer` | `{"delta": "..."}`（回答增量） |
| `done` | `{"promptTokens": n, "completionTokens": n}` |
| `error` | `{"message": "..."}` |

## 依赖说明

- `io.agentscope:agentscope-harness`：ReAct 循环 + 事件流（`streamEvents`）
- `io.agentscope:agentscope-extensions-model-openai`：OpenAI 兼容协议模型实现
- 其余由 Spring Boot 3.2.5 管理（reactor 等经 harness 传递引入）
