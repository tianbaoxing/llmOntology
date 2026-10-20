# Neo4j-test 容器启动后自动退出排查记录

> 日期：2026-10-20
> 环境：Windows + Docker Desktop（WSL2），neo4j:5-community（5.26.29），容器由 docker-compose.test.yml 创建
> 结论：容器运行时状态损坏，重建容器修复，数据零丢失

## 一、问题现象

- `Neo4j-test` 容器 `docker start` 后 10~17 秒自动退出，退出码 **3**
- 日志固定三行，JVM 连 `Starting...` 都没打印，`debug.log` 零记录：

```text
Changed password for user 'neo4j'. ...
2026-10-20 05:18:41.739+0000 INFO  Logging config in use: File '/var/lib/neo4j/conf/user-logs.xml'
2026-10-20 05:18:41.757+0000 INFO  Neo4j Server shutdown initiated by request
2026-10-20 05:18:41.758+0000 INFO  Stopped.
```

- 当天 12:02 左右一批容器被批量强杀（旧 `Neo4j`、`market-mysql`、`nacos` 均退出 137，疑似 Docker Desktop 异常重启或批量强制清理），之后该容器每次启动必死

## 二、排查步骤

### 步骤 1：确认容器状态与退出码

```powershell
docker ps -a --filter "name=neo4j" --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"
```

```text
Neo4j-test   Exited (3) 4 minutes ago   neo4j:5-community
```

排除方向：退出码 3 不是信号量（137=SIGKILL、143=SIGTERM），是进程自主返回的。

### 步骤 2：查看容器日志，定位失败阶段

```powershell
docker logs --tail 60 Neo4j-test
```

关键发现：正常启动流程为 `Logging config in use` → `Starting...` → `Started.`；
失败时 `Logging config in use` 后仅 18~34ms 即打印 `shutdown initiated by request`，JVM 尚未完成初始化。

### 步骤 3：查看容器详细状态（排除 OOM / 健康检查 / 重启策略）

```powershell
docker inspect Neo4j-test --format "RestartPolicy={{json .HostConfig.RestartPolicy}} Healthcheck={{json .Config.Healthcheck}} OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}}"
```

```text
RestartPolicy={"Name":"no",...} Healthcheck=null OOMKilled=false ExitCode=3
```

结论：非 OOM、无健康检查、无自动重启策略介入。

### 步骤 4：Docker 事件流分析（关键证据）

```powershell
docker events --since "8h" --until "$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')" --filter container=Neo4j-test --format "{{.Time}} {{.Action}}"
```

实时监控实验（后台 events + docker start）：

```powershell
$job = Start-Job { docker events --since "1s" --format "{{.Time}} {{.Type}} {{.Action}} {{.Actor.Attributes.name}}" }
docker start Neo4j-test
Start-Sleep -Seconds 30
Stop-Job $job; Receive-Job $job
```

捕获的完整事件链：

```text
volume mount neo4j_data_test
volume mount neo4j_logs_test
container start Neo4j-test
（11 秒无任何事件）
volume unmount neo4j_data_test
volume unmount neo4j_logs_test
container die Neo4j-test
```

**决定性结论：全程无 kill / stop / pause / oom 事件** —— Docker 没有发过任何停止信号，容器进程是完全自主退出（优雅关闭）。问题在容器内部。

> 注意：daemon 的 events 历史缓冲较短，回溯历史可能查不到，需实时监控抓取。

### 步骤 5：读取卷内详细日志（排除数据层报错）

```powershell
docker run --rm -v neo4j_logs_test:/logs -v neo4j_data_test:/data alpine sh -c "ls -la /logs /data; tail -n 50 /logs/debug.log; tail -n 30 /logs/neo4j.log"
```

发现：`debug.log` 停留在第一次正常关闭时刻（04:02），之后所有失败启动 JVM 均未写过 debug.log —— 数据库根本没开始初始化；`/data/databases` 目录时间戳停留在首次运行，说明失败与数据文件无关。

### 步骤 6：读取镜像入口脚本（排除 entrypoint 异常）

```powershell
docker run --rm --entrypoint cat neo4j:5-community /startup/docker-entrypoint.sh
```

确认脚本无 trap、无自动 stop 逻辑，流程为：设置密码 → `neo4j console --dry-run` 取命令 → `eval exec` 真正启动。

### 步骤 7：排除法实验矩阵

| 实验 | 命令要点 | 结果 | 排除项 |
|---|---|---|---|
| 基线：全新无卷容器 | `docker run --rm -e NEO4J_AUTH=... neo4j:5-community` | 正常运行 | 镜像、Docker 环境 |
| 同卷新容器 | `docker run -d -v neo4j_data_test:/data -v neo4j_logs_test:/logs ...` | 正常 Started | 数据卷 |
| 同卷+端口映射 | `docker run -d -p 7474:7474 -p 7687:7687 -v ... ` | 正常 | 端口映射 |
| 接入 compose 网络 | `docker network connect llm-ontology-test_default <容器>` | 正常 | compose 网络 |

### 步骤 8：检查容器可写层与配置文件（排除配置损坏）

```powershell
docker diff Neo4j-test
docker cp Neo4j-test:/var/lib/neo4j/conf/neo4j.conf "$env:TEMP\neo4j-test.conf"
Get-Content "$env:TEMP\neo4j-test.conf" -Tail 30
```

`neo4j.conf` 只有 entrypoint 的标准追加项（pagecache 512M / listen 0.0.0.0 / logs 目录），无损坏。

### 步骤 9：结构性配置对比（排除容器配置差异）

```powershell
$a = docker inspect Neo4j-test | ConvertFrom-Json
$b = docker inspect neo4j-porttest | ConvertFrom-Json
# 对比 LogConfig / ShmSize / Entrypoint / PidMode / IpcMode / AutoRemove ...
```

所有可观察配置完全一致，但旧容器必死、新容器必活。

## 三、根因结论

**`Neo4j-test` 容器实体的运行时状态（shim/containerd 层）损坏。**

当天 12:02 左右一批容器被批量强杀时，运行中的 `Neo4j-test` 留下了损坏的运行时残留。之后每次 `docker start` 复用该容器实体，进程组内部即触发异常信号使 Neo4j 优雅自杀（exit 3），表现为"启动即自杀"。

判定依据：镜像 / 数据卷 / 端口映射 / compose 网络 / 配置文件全部排除，且 Docker 事件流证明无任何外部停止信号。

## 四、修复方案（已验证）

数据全在 named volume（`neo4j_data_test` / `neo4j_logs_test`）中，重建容器实体零丢失：

```powershell
docker rm Neo4j-test
docker compose -f docker-compose.test.yml up -d neo4j
```

验证：

```powershell
# 1. 稳定运行（超过原 10~17 秒死线）
docker ps -a --filter "name=Neo4j-test" --format "{{.Status}}"

# 2. 日志出现完整启动链
docker logs --tail 8 Neo4j-test
# Logging config in use → Starting... → Bolt enabled 0.0.0.0:7687 → HTTP enabled 0.0.0.0:7474 → Started.

# 3. 数据完整性（87 节点与损坏前一致）
docker exec Neo4j-test cypher-shell -a bolt://localhost:7687 -u neo4j -p neo4j2026 "MATCH (n) RETURN count(n) AS nodes;"
```

## 五、方法论沉淀

1. **先看退出码语义**：`docker ps -a` 的 Exited(n) —— 137/143 是被信号杀死（外部），其他小数值多为进程自主返回（内部）
2. **events 是区分"被动杀"与"主动死"的黄金证据**：有 kill 事件 = 外部停止；无 kill 事件 + die = 进程自主退出。实时监控用 `Start-Job { docker events ... }` 后台抓取
3. **容器问题四层排除法**：镜像层（无卷新容器）→ 数据层（同卷新容器）→ 接入层（端口/网络）→ 容器实体层（重建）
4. **数据在 named volume 里时，重建容器永远是无损兜底**：遇到"启动即退但日志无实质错误"的容器，直接 `docker rm` + `compose up -d` 重建，不必深查 shim 层细节
