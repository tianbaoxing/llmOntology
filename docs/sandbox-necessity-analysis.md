# ontology-server 引入沙箱必要性分析

## 一、项目风险全景扫描

### 1.1 高风险攻击面识别

| 风险类别 | 风险点 | 涉及模块/代码 | 严重度 |
|---|---|---|---|
| **外部代码执行** | AgentScope Harness `execute` 工具未显式禁用，仅靠中间件兜底拦截 | [AgentScopeConfig.java#L125-L130](../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java#L125-L130) | 🔴 高 |
| **外部代码执行** | 技能目录含可执行 Python 脚本（`tongshu_query.py`），若 shell 工具被突破可直接调用宿主 python | [tongshu_query.py](../ontology-server/skills/tongshu-1.0.0/scripts/tongshu_query.py) | 🔴 高 |
| **文件系统越权** | Harness 内置文件工具（read_file/write_file/edit_file 等）未禁用，虽有 RolePermissionMiddleware 但 fail-open（无角色信息时放行） | [AgentScopeConfig.java#L125-L130](../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java#L125-L130) | 🔴 高 |
| **子代理动态生成** | `disableSubagents()` / `disableDynamicSubagents()` 未调用，Agent 可自行 spawn 子代理绕过权限拦截 | [AgentScopeConfig.java#L125-L130](../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java#L125-L130) | 🟠 中高 |
| **任意文件上传 + Tika 解析** | 允许上传 50MB 的 pdf/doc/docx/xls/xlsx，Tika 解析器历史上多次曝出 RCE 漏洞（CVE-2018-1335 等） | [FileStorageService.java#L70-L112](../ontology-server/src/main/java/com/conca/ontology/file/service/FileStorageService.java#L70-L112), [MdConvertService.java#L109-L126](../ontology-server/src/main/java/com/conca/ontology/file/service/MdConvertService.java#L109-L126) | 🟠 中高 |
| **Neo4j 写操作面** | 管理接口直接接收用户 Cypher，虽有角色鉴权但无写语句注入审计 | [GraphWriteService.java](../ontology-server/src/main/java/com/conca/ontology/graph/service/GraphWriteService.java) | 🟠 中 |
| **MCP 外部服务连通** | 支持 MCP 客户端连接第三方服务（weather-mcp），存在 SSRF/横向移动面 | [AgentScopeConfig.java#L107-L109](../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java#L107-L109) | 🟡 中 |
| **数据目录明文** | Neo4j 密码、API Key 等敏感信息明文存 application.yml，进程崩溃或内存 dump 可泄露 | [application.yml#L103-L107](../ontology-server/src/main/resources/application.yml#L103-L107) | 🟡 中 |
| **Cypher 注入风险** | `/api/llm/ontology-query` 虽有写关键字正则拦截，但正则黑名单无法覆盖所有变体 | [OntologyQueryExecutionService.java#L16-L59](../ontology-server/src/main/java/com/conca/ontology/llmapi/service/OntologyQueryExecutionService.java#L16-L59) | 🟡 中 |

### 1.2 现有安全机制评估

| 机制 | 覆盖范围 | 缺陷 |
|---|---|---|
| RolePermissionMiddleware | 工具/子代理/技能名黑名单、文件路径树拦截、execute 路径片段匹配 | fail-open 设计（无角色即放行）；仅拦截被禁技能目录，不覆盖整个文件系统；路径匹配是字符串 contains，可被编码绕过 |
| `HttpProxyTool` 白名单 | path 前缀限制 `/api/llm/`，防 SSRF | 仅覆盖此一个工具，其他工具无限制 |
| `OntologyQueryExecutionService` 写关键字拦截 | 正则黑名单拒绝含 CREATE/MERGE 等关键字的 Cypher | 正则可绕过（注释、Unicode、大小写混合变体）；仅限读接口 |
| 文件上传扩展名白名单 | `pdf,txt,csv,md,json,xml,doc,docx,xls,xlsx` | 扩展名绕过（双后缀、Magic Number 伪装）；Tika 内部解析器仍有漏洞面 |
| 路径穿越防护 | normalize + startsWith 校验 | 部分模块已实现（SkillAnalysisService、BackupService、FileStorageService），但需逐个审计 |
| `RewriteAgentHolder` / `JudgeAgentHolder` 硬禁用 | 改写 Agent、评测 Judge Agent 已显式 `disableShellTool()/disableFilesystemTools()` | **仅子 Agent 安全，主 HarnessAgent 未同步禁用** |

---

## 二、引入沙箱的必要性判定

### 2.1 决策矩阵

| 维度 | 评估 | 结论 |
|---|---|---|
| **部署场景** | 当前所有配置指向本地单机：`bolt://localhost:7687`、`http://127.0.0.1:9876`、内存 Session、无反向代理配置 | **个人/本地开发场景** |
| **用户模型** | 用户/密码存在 `data/users.json`，ADMIN/USER 两级，无多租户隔离 | **单用户或可信小团队** |
| **攻击暴露面** | 无公网部署证据，默认 `server.port=9876` 需用户自行开放 | **内网/本机闭环** |
| **业务数据敏感度** | 文物图谱本体数据，属于研究/收藏类，非金融/隐私/医疗数据 | **中低敏感度** |
| **最高危风险可闭环** | 主 Agent 的 `disableShellTool()` 等 3 行注释去掉即可消除 80% 高风险，无需沙箱 | ✅ **有更轻量替代方案** |

### 2.2 结论：**当前阶段不必引入 Docker 沙箱，但需立即修复 3 项零成本高危漏洞**

理由：
1. **场景不匹配**：沙箱（容器级隔离）的核心价值是**多租户隔离**和**公网不可信输入的故障域隔离**。当前项目是本地单机单用户部署，攻击面完全在用户可控范围内。
2. **成本收益失衡**：Docker 沙箱化需要重构文件挂载、Neo4j 网络拓扑、Skill 执行链路、MCP 通信、Tika 调用通道，工程量约 2-3 周，而收益仅为 "防御 Tika 0day" 这类极端场景。
3. **高风险项可通过代码修复消除**：见下文第三节。

---

## 三、若暂不引入沙箱，必须立即执行的修复项（优先级排序）

### 🔴 P0 高危：主 HarnessAgent 取消注释硬禁用高危工具

当前 [AgentScopeConfig.java#L125-L130](../ontology-server/src/main/java/com/conca/ontology/agent/config/AgentScopeConfig.java#L125-L130) 6 行全部注释，这是最大的隐患。参照同项目的 [RewriteAgentHolder.java#L123-L127](../ontology-server/src/main/java/com/conca/ontology/agent/service/RewriteAgentHolder.java#L123-L127) 和 [JudgeAgentHolder.java#L110-L114](../ontology-server/src/main/java/com/conca/ontology/eval/JudgeAgentHolder.java#L110-L114)，改为：

```java
.disableShellTool()
.disableFilesystemTools()
.disableSubagents()
.disableDynamicSubagents()
// .disableToolsConfig();  // 保留：用于动态开关记忆工具等低风险
```

**修复效果**：消除 execute 任意命令执行 + 文件工具越权读/写 + 子代理逃逸三条高危链，风险降低 80%。中间件 RolePermissionMiddleware 退化为第二道兜底防线。

---

### 🟠 P1 中高：RolePermissionMiddleware fail-open 改 fail-close

当前 [RolePermissionMiddleware.java#L50-L52](../ontology-server/src/main/java/com/conca/ontology/agent/middleware/RolePermissionMiddleware.java#L50-L52) 注明"fail-open：无角色信息直接放行"。主 Agent 硬禁用后该风险降级，但仍建议改为：**无角色上下文时拒绝 shell/execute/file 类工具，仅放行 HttpProxyTool / DisjointRulesTool / build_chart 等白名单自定义工具**。

---

### 🟠 P1 中高：Tika 解析资源隔离（非沙箱的进程级隔离）

Tika 解析恶意 PDF/DOCX 是唯一不能通过纯代码配置完全消除的风险。若不想上容器，折中方案：
1. 将 `MdConvertService` 剥离为独立 Spring Boot 微服务（单端口 + 单接口），限制 JVM 堆 256MB、设置 `SecurityManager` 或 `-Djava.security.policy` 文件访问白名单；
2. 主服务通过本地 HTTP 调用，超时 30s，失败重试 1 次。

工程量约 1-2 天，远小于 Docker 化。

---

### 🟡 P2 中：Cypher 正则黑名单补充 + 字符归一化

[OntologyQueryExecutionService.java#L16-L18](../ontology-server/src/main/java/com/conca/ontology/llmapi/service/OntologyQueryExecutionService.java#L16-L18) 的写关键字拦截可被注释绕过（如 `CRE/**/ATE`），建议先做 Cypher 归一化（去注释、折叠空白、大小写统一）再匹配。

---

## 四、何时必须引入沙箱（触发条件清单）

当满足**任一**条件时，立即启动 Docker 沙箱改造优先级会逆转：

| 触发条件 | 需沙箱隔离的模块 |
|---|---|
| ✅ 服务对公网开放（非 localhost 监听，有公网 IP/域名） | **全链路沙箱**：本体服务、Neo4j、Tika 分别独立容器 + 网络策略隔离 |
| ✅ 多租户（不同用户数据不可互见） | 独立 workspace 卷挂载 + 用户级容器实例池 |
| ✅ 允许用户上传自定义 Skill（含 `.py`/`.sh`） | Skill 执行容器：只读根文件系统 + tmpfs 工作目录 + 无外网 egress + 资源配额（CPU 1核 / 内存 512MB / 超时 30s） |
| ✅ 允许用户自定义 Grader 脚本（非提示词驱动） | 同 Skill 执行容器，额外禁用文件系统写操作 |
| ✅ 接入不可信第三方 MCP 服务超过 3 个 | MCP 流量经独立 egress 代理容器 + 域名/IP 白名单 |

---

## 五、若未来引入沙箱的建议架构

```
┌─────────────────────────────────────────────────────────────┐
│                       Host Network (127.0.0.1 only)          │
│                                                             │
│  ┌──────────────┐   HTTP   ┌──────────────────┐  bolt/r2  ┌─────────┐ │
│  │  eval.html   │◄────────►│  ontology-server │◄─────────►│  Neo4j  │ │
│  │  chat.html   │          │  (Spring Boot)   │           │ Container│ │
│  └──────────────┘          └────────┬─────────┘           └─────────┘ │
│                                     │                              │
│                              HTTP  │  localhost:9877               │
│                                     ▼                              │
│                          ┌──────────────────┐                      │
│                          │   tika-worker    │                      │
│                          │ (独立 JVM/容器)  │                      │
│                          └──────────────────┘                      │
│                                     │                              │
│                           skill-exec 通道（按需）                  │
│                                     ▼                              │
│                          ┌──────────────────┐  只读卷挂载 skills/  │
│                          │  skill-sandbox   │  tmpfs /workspace    │
│                          │ (python:3.11-al) │  --cap-drop ALL      │
│                          └──────────────────┘  --read-only         │
└─────────────────────────────────────────────────────────────┘
```

**沙箱化的最小落地顺序**（先易后难，ROI 递减）：
1. **第一步**：Tika worker 容器化（独立进程 + 资源限制 + 崩溃自动重启）→ ROI 最高
2. **第二步**：Neo4j 容器化（消除宿主 Neo4j 安装依赖，已经是主流部署方式）
3. **第三步**：本体服务本身容器化（标准化部署，非安全驱动）
4. **第四步**：Skill 执行沙箱（仅在"允许用户自定义 Skill"触发后才需要）

---

**总结**：当前本地单机场景下，**先修 P0 的 3 行取消注释 + P1 fail-close 即可，性价比远高于引入沙箱**。沙箱应作为"公网开放/多租户/自定义 Skill"三条件触发后的架构升级项，而非现在就投入。
