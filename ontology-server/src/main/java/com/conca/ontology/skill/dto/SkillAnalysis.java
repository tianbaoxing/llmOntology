package com.conca.ontology.skill.dto;

import java.util.List;

/**
 * 单技能分析结果（落盘 data/skill-analysis.json 的 skills[name] 值）：
 * analyzedAt 分析时间、contentHash 扫描文件内容哈希（详情页对比判过期）、
 * scannedFiles 参与扫描的文件相对路径、tools 工具比对结果（used=系统工具中被使用、
 * notUsed=系统工具中未使用）、apis 提取到的 API 路径（去重）、shellCommands 提取到的 shell 命令（去重计数）、
 * keyInfo 用户填写的识别关键词（判断 LLM 是否真实执行该 skill 的特征指纹，独立于检测结果，analyze 时保留不清空）、
 * hiddenTools/hiddenApis/hiddenShellCommands 用户删除（忽略）的检测项（过滤太常见的命中，
 * 详情页 × 按钮触发，跨重新检测保留；响应时从对应清单中剔除）。
 * record 由 Jackson 直接序列化/反序列化（字段名即 JSON 键）；旧文件缺这三个字段时反序列化为 null。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
public record SkillAnalysis(
        String analyzedAt,
        String contentHash,
        List<String> scannedFiles,
        ToolUsage tools,
        List<String> apis,
        List<ShellCmd> shellCommands,
        List<String> keyInfo,
        List<String> hiddenTools,
        List<String> hiddenApis,
        List<String> hiddenShellCommands) {

    /** 工具比对结果：used 含命中次数与代码块内命中标记；notUsed 为系统工具中全文未出现的名字 */
    public record ToolUsage(List<ToolHit> used, List<String> notUsed) {
    }

    /** 单工具命中：count=全文总次数，inCodeBlock=是否至少一次出现在代码块围栏内 */
    public record ToolHit(String name, int count, boolean inCodeBlock) {
    }

    /** 单 shell 命令命中计数 */
    public record ShellCmd(String command, int count) {
    }
}
