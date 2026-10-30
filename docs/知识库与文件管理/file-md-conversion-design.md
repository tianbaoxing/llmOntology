# 知识库文件自动转 Markdown 设计（files-md）

## 1. 背景与方案

**问题**：知识库（`http://localhost:9876/files`）支持上传 pdf / doc / docx / xls / xlsx 等二进制格式，但 LLM 的文件工具（Read/Grep）读二进制是乱码或空内容，无法检索关键信息。

**方案**：上传时自动把二进制文件转成 Markdown，**LLM 只读 MD，不给模型看原始文件**。

```
上传 pdf/doc/docx/xls/xlsx ──┬─→ files/          存原件（管理页可见，人用；溯源/重转）
                              └─→ files-md/       自动生成 <原文件全名>.md（LLM 专用）
上传 txt/csv/md/json/xml ────→ files/             原样存储（LLM 直读，不转换）
```

核心原则：

- **原件保留**：files/ 永远保留原件，MD 是可再生产物（不纳入备份）
- **对 LLM 透明**：`GET /api/llm/files` 返回的 `path` 恒为"可直接 Read/Grep 的 Markdown/文本路径"，技能提示词无需关心原件格式
- **双目录隔离**：MD 放独立目录 `files-md/`（镜像 files/ 相对路径），不进 files/ 子目录——避免 reconcile 递归扫描时把 MD 补录成知识库条目
- **失败不炸上传**：转换失败只记 FAILED + 原因（页面可见、可重试），上传流程不受阻断

**技术选型**：Tika（解析 pdf/doc/docx/xls/xlsx → XHTML，保留标题/表格/列表结构）→ 截取 body → flexmark-html2md-converter（XHTML → GFM Markdown，表格转管道表格）。PDF 每页插入 `---` 分隔线便于定位；MD 首行插来源注释（`> 来源: xxx.docx · 转换于 ...`）便于溯源。

**为什么文本类（csv/txt/md/json/xml）不转**：LLM 天然可直读，转 MD 无收益；CSV 转 GFM 表格反而破坏原始结构、弱化 Grep 行号定位（如 artifact.csv 直接返回原路径）。

## 2. 参数（application.yml）

```yaml
app:
  upload-dir: files                          # 原件目录
  upload-allowed-ext: pdf,txt,csv,md,json,xml,doc,docx,xls,xlsx   # 上传白名单
  metadata-file: data/file-metadata.json     # 元数据（唯一事实源）
  md-dir: files-md                           # MD 输出目录（镜像 upload-dir 相对路径）
  convert-ext: pdf,doc,docx,xls,xlsx         # 触发转换的扩展名（二进制类）
  convert-min-chars: 50                      # 空结果检测阈值：正文有效字符 < 该值判 FAILED
```

- MD 命名：`<原文件全名>.md`（如 `报告.docx` → `报告.docx.md`，零命名冲突、文件名可溯源）
- 空结果检测：剥掉 MD 语法符号后有效字符（字母/数字/汉字）不足阈值 → FAILED"疑似扫描件或空文档，无文本层"

**元数据模型**（FileEntryDTO 扩展，向后兼容，旧 JSON 缺省 null 自动回填）：

| 字段 | 说明 |
|---|---|
| `mdName` | 对应 MD 文件名（仅二进制转换文件有值） |
| `convertStatus` | `NONE`（文本类无需转换）/ `SUCCESS` / `FAILED` |
| `convertMsg` | 失败原因（仅 FAILED） |
| `convertedAt` | 转换完成时间 |

## 3. 核心调用流程

### 3.1 上传（POST /api/files）

```
FileController.upload
└─ FileStorageService.upload（synchronized）
    ├─ 白名单校验 → 原子落盘 files/（tmp + rename，同名覆盖）
    ├─ upsert(reconcile(), entry)     元数据登记
    └─ ensureConverted(...)           统一补转换（新文件在此转，避免重复）
        └─ MdConvertService.convertAndWrite
            ├─ Tika → XHTML → body → flexmark → GFM MD
            ├─ 空结果检测（< convert-min-chars → FAILED）
            └─ 原子写入 files-md/<原名>.md（tmp + rename）
```

响应 `FileUploadResult.file` 携带 convertStatus/convertMsg；前端上传提示"已转换为 MD / 转换失败：原因"。

### 3.2 列表与对账（GET /api/files，admin 页与 LLM 共用入口）

```
FileStorageService.list（synchronized）
├─ ① reconcile()        磁盘对账：files/ 新文件补录（含手动拖入）、已消失文件剔除
└─ ② ensureConverted()  MD 对账（稳态下零开销）：
      文本类                 → 回填 NONE
      二进制缺 MD 且非 FAILED → 补转换（存量历史文件/被删 MD 自动重建）
      MD 已存在但状态缺失     → 回填 SUCCESS（mtime 作 convertedAt）
      FAILED                → 不动（不自动重试，避免每次 list 撞同一堵墙）
```

存量历史文件无需迁移脚本：重启后首次访问列表自动补转。

### 3.3 LLM 视图（GET /api/llm/files）

```
LlmFileController.files
├─ storageService.list()（同上对账）
├─ 过滤：convertStatus == FAILED 剔除（LLM 不可见；admin 页可见原因，reconvert 修复后自动出现）
└─ toView() 逐条分流（path 恒为"应读路径"）：
      SUCCESS + mdName 非空（二进制）
        → name = mdName，path = files-md/xxx.md 绝对路径，originName = 原文件名
      其他（NONE 文本类，如 artifact.csv）
        → name/path = files/ 原件，originName = null
```

### 3.4 删除（DELETE /api/files/{name}）

文件实体 + `files-md/` 对应 MD + 元数据记录一并移除。

### 3.5 手动重试（POST /api/files/reconvert?name=xxx）

对 pdf/word/excel 强制重转（先删旧 MD）；FAILED 修复后手动触发。注：`{*/name}` 通配必须居路径末尾，故 name 走查询参数。

## 4. 关键类与文件

| 文件 | 职责 |
|---|---|
| `service/MdConvertService.java` | 转换管道：Tika→XHTML→flexmark→GFM；空结果检测；原子写入；shouldConvert/mdNameOf/mdPathOf |
| `service/FileStorageService.java` | 挂钩：上传即转、删除连带删 MD、ensureConverted 对账补转、reconvert |
| `controller/llmapi/LlmFileController.java` | LLM 只读视图：FAILED 过滤 + path 分流 |
| `controller/api/FileController.java` | 管理端：上传/列表/说明/删除/reconvert |
| `dto/FileEntryDTO.java` | 元数据条目（含 4 个转换扩展字段） |
| `dto/view/LlmFileEntryView.java`、`LlmFileListView.java` | LLM 视图 DTO |
| `static/pages/files.html` | 前端：MD 状态列（✅/—/❌+原因悬停+重试按钮）、上传提示带转换结果 |
| `skills/ontology-builder/SKILL.md`、`skills/ontology-query/SKILL.md` | 微调：path 恒为可直接读取的 Markdown/文本 |

依赖（pom.xml）：tika-core / tika-parser-pdf-module / tika-parser-microsoft-module 2.9.2 + flexmark-html2md-converter 0.64.8 + **commons-io 2.16.1（显式引入）**。

## 5. 踩坑记录

1. **commons-io 缺失**：Tika 解析 docx/pdf **内嵌资源**（含图片的 docx）时走 zip 容器探测，需要 commons-io 2.15+ 的 `ChecksumInputStream`，但 Tika 各模块均不传递声明 → 运行期 `NoClassDefFoundError`。纯文本小 docx 不触发该路径故能成功。修复：pom 显式引入 commons-io 2.16.1。
2. **LinkageError 穿透**：`NoClassDefFoundError` 是 Error 不是 Exception，`catch (Exception)` 接不住，导致整个上传 500 且 FAILED 状态都没记录。修复：`convertAndWrite` 改为 `catch (Exception | LinkageError)`，任何转换失败都只记 FAILED + 原因。
3. **flexmark API**：静态方法 `FlexmarkHtmlConverter.converter(String)` 不存在，需 `FlexmarkHtmlConverter.builder().build().convert(html)`。
