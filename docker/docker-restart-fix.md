# Docker 关闭容器自动重启操作文档（Windows Docker Desktop）

> 
> 场景：Docker Desktop 每次启动，部分容器自动拉起，需要改为**手动启动**。
> 原理：修改容器重启策略为 `restart=no`，主机 / Docker 重启不会自动启动容器。

## 一、查看所有容器重启策略（CMD 终端）

```
for /f "tokens=*" %i in ('docker ps -aq') do @docker inspect --format "%i {{.Name}} RestartPolicy={{.HostConfig.RestartPolicy.Name}}" %i
```

- 策略说明
  - `always`：Docker 启动就自动重启
  - `unless-stopped`：除非手动 stop，否则自动拉起
  - `no`：不自动重启（目标状态）

本次查询出需要修改的容器：

1. `ontology-mysql`：unless-stopped
2. `Neo4j`：unless-stopped
3. `higress`：always

## 二、修改这 3 个容器关闭自动重启

```
docker update --restart=no ontology-mysql Neo4j higress
```

## 三、验证修改结果

```
docker inspect ontology-mysql --format "{{.HostConfig.RestartPolicy.Name}}"
docker inspect Neo4j --format "{{.HostConfig.RestartPolicy.Name}}"
```

> 
> 输出 `no` 代表修改成功。

## 四、停止当前正在运行的这几个容器（可选）

```
docker stop ontology-mysql Neo4j
```

## 五、重要注意事项

1. 如果容器是通过 `docker-compose` 创建：
`docker update` **仅临时生效**，执行 `docker compose up -d` 重建容器会恢复旧策略。
需要修改 `docker-compose.yml`：

```
# 删除或修改
restart: always
# 改为
restart: "no"
```

修改 yml 后，执行 `docker compose up -d` 重建容器。
2. 后续启动容器方式：

```
docker start ontology-mysql Neo4j higress
```

## 六、通用批量脚本（CMD，一次性全部容器改为 no）

> 
> 适合后续有大量新增自启容器场景

```
for /f %i in ('docker ps -a -q') do docker update --restart=no %i
```

## 七、PowerShell 版本（备用）

```
# 查看所有容器重启策略
docker ps -aq | ForEach-Object { docker inspect $_ --format "$_ {{.Name}} Restart:{{.HostConfig.RestartPolicy.Name}}" }

# 批量修改所有容器关闭自启
docker ps -aq | ForEach-Object { docker update --restart=no $_ }
```

## 八、重启策略对照表

表格

| 重启策略 | 行为 |
| --- | --- |
| `no` | 永不自动重启（手动 start 才启动） |
| `always` | Docker 服务启动，容器自动拉起 |
| `unless-stopped` | 只要不是手动 stop，Docker 重启就自动拉起 |
| `on-failure` | 容器异常崩溃退出时自动重启 |