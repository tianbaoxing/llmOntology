# ontology-query 调用示例

本文档演示如何调用新增的本体查询接口：

- `POST /api/llm/ontology-query/query`：手工传只读 Cypher
- `POST /api/llm/ontology-query/ask`：直接传自然语言问题

默认服务地址：`http://localhost:9876`

## 1. 手工执行只读 Cypher

适合你已经知道要查什么、想精确控制 Cypher 的场景。

### 1.1 查询年代节点样例

```powershell
$body = @'
{
  "question": "有哪些年代概念？",
  "cypher": "MATCH (n:`Era`) RETURN n.`name` AS key, properties(n) AS props ORDER BY key LIMIT 10",
  "params": {}
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/query" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

### 1.2 查询某个类的实例数量

```powershell
$body = @'
{
  "question": "Place 有多少节点？",
  "cypher": "MATCH (n:`Place`) RETURN count(n) AS count",
  "params": {}
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/query" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

### 1.3 校验只读限制

下面这种写语句会被拒绝：

```powershell
$body = @'
{
  "question": "错误示例",
  "cypher": "CREATE (n:Test {name: 'x'}) RETURN n",
  "params": {}
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/query" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

预期：返回 400，提示只读接口拒绝写操作。

## 2. 直接问自然语言问题

适合 skill 或前端直接提问，服务端会自动走：

`schema -> graph -> profile`

### 2.1 查询概念层

```powershell
$body = @'
{
  "question": "Era 有哪些属性？",
  "limit": 10
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/ask" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

预期：优先返回 schema 里的类定义、属性列表、keyProp 等信息。

### 2.2 查询实例层

```powershell
$body = @'
{
  "question": "妇好墓1976出土的文物有哪些？",
  "limit": 10
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/ask" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

预期：如果图谱里有命中节点和关系，返回 `graph` 证据。

### 2.3 查询构建背景

```powershell
$body = @'
{
  "question": "这个本体的数据来源是什么？",
  "limit": 10
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/ask" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

预期：当图谱证据不足时，回查 `ontology-profile.json`，返回 `profile` 证据。

### 2.4 明确不存在

```powershell
$body = @'
{
  "question": "图谱里有没有不存在的测试文物XYZ？",
  "limit": 10
}
'@

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:9876/api/llm/ontology-query/ask" `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

预期：返回 `found=false`，并明确说明当前本体中不存在该信息。

## 3. 常见响应字段

### 3.1 `/query`

常见字段：

- `question`
- `cypher`
- `params`
- `keys`
- `rows`
- `count`
- `found`
- `message`

### 3.2 `/ask`

常见字段：

- `question`
- `matchedClass`
- `matchedProperty`
- `matchedRelation`
- `schemaVersion`
- `found`
- `evidenceSource`
- `answer`
- `cypher`
- `graphEvidence`
- `profileEvidence`

## 4. 使用建议

1. 先用 `/ask` 验证自然语言问答体验。
2. 需要精确控制查询时，再切到 `/query`。
3. 若返回 `none`，优先检查：
   - 问题里是否包含可定位的实体词
   - schema 中是否存在对应类/属性/关系
   - 图谱里是否真的有数据
4. 若是构建背景类问题，优先确认 `data/ontology-profile.json` 是否已写入相关记录。
