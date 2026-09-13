# Chat 回答支持折线图与表格渲染 — 设计方案（v2 插件工具版）

> 目标：`http://localhost:9876/chat` 页面，AI 回答目前纯文本展示（`textContent +=`）。
> 本方案使统计类问题的回答能渲染 **Markdown 表格** 与 **ECharts 图表（折线/柱状/饼图）**。
>
> **v2 核心变化**：图表格式不再靠 AI 凭记忆自由组装 JSON（随意性大），
> 而是定义图表插件，通过 `build_chart` 工具下发——AI 只传数据，
> 标准由 Java 代码校验并组装，前端拿到永远是标准 JSON。

## 一、已确认的决策

| 决策点 | 结论 |
|---|---|
| 触发方式 | AI 自主判断（无需用户手动指定） |
| 工具交互模式 | **传数据·工具组装**：AI 把数据作为参数调 `build_chart`，工具校验后由 Java 组装标准 JSON 返回，AI 原样转写 |
| 插件清单 | **line（折线）+ bar（柱状）+ pie（饼图）**；表格不走插件，Markdown 原生输出 |
| 前端库引入 | 本地 vendor（`static/vendor/`），无外网依赖 |
| 图表库 | ECharts |
| 流式渲染策略 | 完成后整体渲染（流式期间维持纯文本） |
| XSS 防护 | DOMPurify（图谱数据源于用户上传文件，经 AI 引用转 HTML 存在注入面） |

## 二、v1 → v2 演进

| | v1（旧） | v2（本方案） |
|---|---|---|
| 格式来源 | sysPrompt 文字约定 | `build_chart` 工具返回，代码组装 |
| AI 职责 | 凭记忆组装 chart JSON | 只传数据参数，转写工具返回的标准 JSON |
| 随意性 | 高（字段名/结构可能写错） | 低（字段顺序、类型、默认值由 Java 固定） |
| 校验 | 无，前端解析失败才降级 | 后端前置校验，出错返回具体原因让 AI 修正重试 |
| 可调试 | 不可见 | 调用与返回在「执行过程」tool-box 中可见 |
| sysPrompt 改动 | 约 10 行格式规则 | 1 句：何时调工具、转写规则 |

## 三、`build_chart` 工具设计

**新增文件**：`agent/ChartPluginTool.java`（风格对齐 `DisjointRulesTool`：`@Tool` 注解，description 写明何时调用、拿到后做什么）。

**注册**：`AgentScopeConfig` 中 `toolkit.registerTool(new ChartPluginTool())`。

**签名**（三种插件统一传参形态：类别轴 + 数值系列，AI 侧认知负担最小）：

```
build_chart(
  type:      "line" | "bar" | "pie"，白名单校验
  title:     图表标题
  xAxis:     类别数组，如 ["元","明永乐","清乾隆"]
  series:    数值系列数组，每项 {name, data}，data 为数值数组
  yAxisName: 单位（可选，pie 时忽略）
)
```

**校验规则**（失败返回具体错误信息，AI 自动修正后重调）：

| 校验项 | 错误返回示例 |
|---|---|
| type 不在白名单 | `[build_chart 错误] type 必须是 line/bar/pie，收到 "scatter"` |
| data 与 xAxis 长度不一致 | `[build_chart 错误] xAxis 有 8 项，系列「藏品数」data 有 7 项，长度必须一致` |
| data 含非数值 | `[build_chart 错误] 系列「藏品数」data 第 3 项 "很多" 不是数值` |
| xAxis / series 为空 | `[build_chart 错误] xAxis 与 series 不能为空` |

**成功返回**：组装好的标准 JSON（统一 `xAxis + series` 同构形态，字段顺序固定）+ 尾附转写指令：*将上方 JSON 原样放入 ` ```chart ` 围栏输出到回答中，不得增删改任何字段*。

## 四、数据例子（端到端）

以「国博藏品图谱」为例。

### 1. 用户提问

```
各朝代青花瓷藏品数量对比是怎样的？
```

### 2. AI 查完图谱，判断适合折线图，调用 build_chart（参数即数据）

```json
{
  "type": "line",
  "title": "各朝代青花瓷藏品数量",
  "xAxis": ["元", "明洪武", "明永乐", "明宣德", "清康熙", "清乾隆"],
  "series": [{ "name": "藏品数", "data": [12, 45, 120, 156, 210, 260] }],
  "yAxisName": "件"
}
```

### 3. 工具校验通过，返回标准 JSON

```json
{"type":"line","title":"各朝代青花瓷藏品数量","xAxis":["元","明洪武","明永乐","明宣德","清康熙","清乾隆"],"series":[{"name":"藏品数","data":[12,45,120,156,210,260]}],"yAxisName":"件"}
```

（附转写指令：原样放入 ```chart 围栏，不得修改）

### 4. AI 输出回答（落盘内容，chart 块 = 工具返回值原样转写）

````
青花瓷藏品在各朝代的数量分布如下：

```chart
{"type":"line","title":"各朝代青花瓷藏品数量","xAxis":["元","明洪武","明永乐","明宣德","清康熙","清乾隆"],"series":[{"name":"藏品数","data":[12,45,120,156,210,260]}],"yAxisName":"件"}
```

从数据可以看出，明宣德与清乾隆两朝的青花瓷藏品最为丰富。

| 朝代 | 藏品数 | 代表作 |
|---|---|---|
| 元 | 12 | 青花缠枝牡丹纹罐 |
| 明宣德 | 156 | 青花海水龙纹高足碗 |
| 清乾隆 | 260 | 青花缠枝莲纹赏瓶 |
````

### 5. 前端渲染效果

- ` ```chart ` 围栏块 → 提取 JSON → type=line → **ECharts 折线图**（x 轴 6 个朝代，y 轴藏品数）
- Markdown 表格 → **带边框、斑马纹的 HTML 表格**

### 6. 失败重试例子（校验兜底）

AI 传参 `data` 7 项但 `xAxis` 8 项 → 工具返回：

```
[build_chart 错误] xAxis 有 8 项，系列「藏品数」data 有 7 项，长度必须一致
```

→ AI 补齐数据后重调 → 校验通过 → 正常输出。整个过程在页面「执行过程」折叠区可见。

### 7. 刷新页面 / 切换会话再切回

```
刷新 → loadMessages() → /messages 返回上述原始文本
    → addStaticAgentMsg(m) → 调用同一渲染函数
    → 重新提取 chart 块 → echarts.init → 图表恢复 ✓
```

图表不是独立的持久化数据，而是**随回答文本一起落盘的 chart JSON 块**（工具产出的标准 JSON），所以刷新、切换会话、重启服务后回看都能恢复。

## 五、前端渲染管线（chat.html）

**新增 vendor 文件**（本地引入，无外网依赖）：

| 文件 | 用途 | 体积 |
|---|---|---|
| `static/vendor/echarts.min.js` | 图表渲染 | ~1MB |
| `static/vendor/marked.min.js` | Markdown→HTML（表格/代码） | ~40KB |
| `static/vendor/purify.min.js` | XSS 防护 | ~20KB |

**渲染流程**（现状 `textContent +=` 流式追加保持不变，仅完成时转换）：

```
流式期间: answer.textContent += delta          ← 不动
round end / done (setDone 首次触发):
  1. 取 answer.textContent 完整文本
  2. marked 解析 → HTML（表格、代码块）
  3. 提取 <code class="language-chart"> 块 → JSON.parse
     → 替换为图表容器 div（失败则保留原文本块，不中断）
  4. DOMPurify 清洗 → answer.innerHTML
  5. 遍历图表容器 → echarts.init + setOption（按 type 映射）
```

**type → ECharts option 映射**：

- `line`：标准折线（category x 轴 + 多 series）
- `bar`：标准柱状（同 line 结构）
- `pie`：前端将 `xAxis[i] + series[0].data[i]` zip 为 `{name, value}`（标准 JSON 保持单一同构形态，解析逻辑最小）

**历史回看**：`addStaticAgentMsg`（现状是 `ui.answer.textContent = m.answer`）末尾必须改为复用同一渲染函数——否则会出现「流式回答有图、刷新后变回原始 JSON 文本」的不一致。压缩记录/旧会话无 chart 块则仅走 Markdown 渲染，无害。

## 六、必须处理的三个坑（实现要点）

1. **「已停止」追加会摧毁已渲染 HTML**：chat.html 中 `setDone` 之后执行 `answer.textContent += '\n\n（已停止）'`——`textContent` 赋值会清空 innerHTML 中的图表/表格。需改为 `appendChild` 追加 span。
2. **ECharts 实例生命周期**：切换会话 `loadMessages` 清空 DOM 前需 `dispose` 已有实例（否则泄漏）；初始化前用 `getInstanceByDom` 防重复 init；监听 `window.resize` 统一 resize。
3. **渲染幂等**：`setDone` 可能被 round end、done 事件、流结束兜底多次触发，渲染必须挂在 `isDone` 首次置位处，只执行一次。

## 七、样式约定

- 图表容器：高 280px、宽撑满气泡（`.msg-agent` 内 max-width 92% 现有约束下）
- 表格：`.msg-agent table` 加边框、斑马纹、表头底色，与现有 UI 风格（蓝 #2563eb 主色）一致

## 八、改动清单

| 文件 | 改动类型 | 内容 |
|---|---|---|
| `agent/ChartPluginTool.java` | **新增** | 插件工具：type 白名单、数据校验、标准 JSON 组装、转写指令 |
| `AgentScopeConfig.java` | 修改 | `toolkit.registerTool(new ChartPluginTool())` + sysPrompt 追加 1 句规则（何时调、如何转写） |
| `static/vendor/echarts.min.js` | 新增 | 本地 vendor |
| `static/vendor/marked.min.js` | 新增 | 本地 vendor |
| `static/vendor/purify.min.js` | 新增 | 本地 vendor |
| `static/pages/chat.html` | 修改 | 引 vendor；渲染管线函数；type→option 映射；「已停止」追加方式；表格/图表样式 |

## 九、降级策略

- AI 未调工具、或转写 chart 块与工具返回不一致 → 前端解析失败时显示原始 JSON 文本块，不影响其余内容
- marked/DOMPurify 异常 → 兜底回退纯文本展示
- AI 未输出 chart 块 → 正常 Markdown 渲染，表格照常展示
- 本功能上线前的历史会话 → 纯 Markdown 渲染，兼容
