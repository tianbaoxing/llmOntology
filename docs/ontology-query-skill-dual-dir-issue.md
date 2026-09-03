# ontology-query 技能双份目录问题排查与统一方案

> 日期：2026-09-03
> 背景：会话中触发 ontology-query 技能查询"德州有文物吗"时，按 SKILL.md 运行 `scripts/env.py` 失败，排查发现存在两份 ontology-query 目录，其中 IDE 加载的副本缺少 `scripts/env.py`。

## 一、现象

- 调用 `Skill` 工具加载 ontology-query 后，运行 `scripts/env.py` 报"文件不存在"。
- 实际存在两个 ontology-query 目录，内容不一致（一个完整、一个只有 SKILL.md）。

## 二、排查结论

两个目录是同一技能的**两个存储位置**：一个是项目源码（权威），一个是 IDE 注册副本（镜像）。

| 位置 | 角色 | 内容 |
|---|---|---|
| `C:\me\kaiyuan\history\llmOntology\skills\ontology-query` | 项目仓库中的权威技能定义，git 跟踪 | `SKILL.md` + `scripts\env.py`（完整） |
| `C:\Users\conca\.trae-cn\skills\ontology-query` | Trae CN 全局用户技能目录，`Skill` 工具实际从此加载 | 仅 `SKILL.md`（缺 `scripts\env.py`） |

### 关键证据

1. **源头在项目**：两个技能（ontology-builder、ontology-query）最初在 llmOntology 仓库中编写并提交（首条提交 `5f9f4be`，后续多次优化至 `7d2c391`），仓库内是完整版。
2. **IDE 副本由导入/注册产生**：
   - `ontology-builder` 副本创建于 2026-08-19 17:31（技能编写当天）。
   - `ontology-query` 副本创建于 2026-09-03 18:01:37（本次本体查询会话启动后不久）。
3. **副本内容与源码正文一致但被截断**：两份 SKILL.md 均为 257 行、正文逐字一致（MD5 差异源于编码/BOM）；区别是导入 IDE 目录时只复制了 SKILL.md，未带上 `scripts\` 目录。
4. 故障机理：SKILL.md 中约定"默认服务地址通过运行 `scripts/env.py` 探测"，IDE 副本缺该脚本，导致从副本触发技能时拿不到服务地址。

## 三、故障影响

- 从 IDE 副本触发技能时，`scripts/env.py` 不存在，无法解析服务地址（`http://localhost:9876`）。
- 需要人工回源到项目目录执行 env.py，查询流程被中断（本次德州文物查询即受此影响，后改用项目目录下的 env.py 完成）。

## 四、统一方案（以项目目录为准）

**原则：项目目录为唯一修改/版本控制入口，IDE 副本只做整目录镜像。**

### 定位分工

- 写代码 / 改内容 / git 提交 → 只在 `C:\me\kaiyuan\history\llmOntology\skills\ontology-query\`。
- 被 `Skill` 实际调用 → 走 IDE 副本，但它只是镜像，不直接在其上修改。

> 注意：不能直接删除 IDE 副本。`Skill` 工具的加载源就是 `.trae-cn\skills\`，删掉技能会失联不可用。

### 同步操作（先删干净再整目录拷贝，保证逐字节一致）

```powershell
Remove-Item -Recurse -Force "C:\Users\conca\.trae-cn\skills\ontology-query"
Copy-Item -Recurse -Force "C:\me\kaiyuan\history\llmOntology\skills\ontology-query" "C:\Users\conca\.trae-cn\skills\ontology-query"
```

### 后续维护

- 每次在项目里修改该技能后，重跑上述同步命令。
- 可将同步命令固化为项目内脚本（如 `skills\ontology-query\scripts\sync-to-ide.ps1`），实现一键同步。

### 备选方案（不推荐）

若 Trae 支持将项目目录直接注册为技能源（而非全局 `.trae-cn\skills`），可去掉 IDE 副本实现"项目即一切"；默认不支持时，删副本会导致技能失联，故不采用。

## 五、本次德州查询的补充结论（顺带记录）

- Place 中无"德州"节点（精确与模糊匹配均无命中），故无 `EXCAVATED_AT` / `MENTIONS_PLACE` 直接关联德州的文物。
- 文物文本提及"德州"命中 1 件：`t20181218_24857`《乾隆十六年南巡各地详图》，介绍中描述京杭运河途经地时提到"经德州城西"。
- 证据来源：graph。
