package com.conca.ontology.controller.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.skill.SkillAnalysisService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Skills 管理（/eval 页面「Skills 管理」Tab，整体 ADMIN，AuthInterceptor 拦截）：
 * GET  /api/skills                 → 技能清单（含已分析摘要）
 * GET  /api/skills/{name}          → 单技能详情（文件清单 + 摘要 + stale 提示）
 * GET  /api/skills/{name}/resources?path= → 读技能内资源文件（防路径穿越）
 * POST /api/skills/{name}/analyze  → 手动触发检测（工具/API/Shell）并落盘
 * POST /api/skills/{name}/hide     → 删除（忽略）检测项（工具/API/Shell，跨重新检测保留）并自动保存
 * 系统工具清单复用 GET /api/agent/tools（Toolkit 实时存活集，单一事实来源）。
 */
@RestController
@RequestMapping("/api/skills")
@Tag(name = "skill", description = "Skills 管理：技能清单、详情、工具/API/Shell 使用检测（ADMIN）")
public class SkillManageController {

    private final SkillAnalysisService skillAnalysisService;

    public SkillManageController(SkillAnalysisService skillAnalysisService) {
        this.skillAnalysisService = skillAnalysisService;
    }

    @GetMapping
    @Operation(summary = "技能清单", description = "返回系统技能列表（含磁盘目录定位与已有分析摘要；agent 未装配时退化为磁盘扫描）")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(skillAnalysisService.listSkills());
    }

    @GetMapping("/{name}")
    @Operation(summary = "技能详情", description = "基本信息 + 文件清单 + 已有分析摘要；stale=true 表示文件已变更、分析可能过期")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String name) {
        return ApiResponse.ok(skillAnalysisService.skillDetail(name));
    }

    @GetMapping("/{name}/resources")
    @Operation(summary = "读技能资源文件", description = "读取技能目录内任意文本资源（path 为相对技能根目录，越出目录 400）")
    public ApiResponse<Map<String, Object>> resource(
            @PathVariable String name,
            @Parameter(description = "相对技能根目录路径，如 scripts/env.py", example = "scripts/env.py")
            @RequestParam String path) {
        return ApiResponse.ok(skillAnalysisService.readResource(name, path));
    }

    @PostMapping("/{name}/analyze")
    @Operation(summary = "触发检测", description = "对 SKILL.md + 全部文本资源做工具/API/Shell 检测，结果落盘 data/skill-analysis.json")
    public ApiResponse<Map<String, Object>> analyze(@PathVariable String name) {
        return ApiResponse.ok(skillAnalysisService.analyze(name));
    }

    @PostMapping("/{name}/hide")
    @Operation(summary = "删除检测项", description = "将工具/API/Shell 命令加入忽略清单（过滤太常见的命中，跨重新检测保留），自动保存 data/skill-analysis.json")
    public ApiResponse<Map<String, Object>> hide(
            @PathVariable String name,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "要忽略的项，如 {\"tools\":[\"read_file\"],\"apis\":[],\"shellCommands\":[\"cd\"]}，空数组/缺省表示该类不变更")
            @RequestBody Map<String, List<String>> body) {
        return ApiResponse.ok(skillAnalysisService.hideItems(name,
                body.get("tools"), body.get("apis"), body.get("shellCommands")));
    }

    @PutMapping("/{name}/keyinfo")
    @Operation(summary = "保存识别关键词", description = "保存用于判断 LLM 是否真实执行该 skill 的特征关键词（独立于检测结果，analyze 时保留不清空）")
    public ApiResponse<Map<String, Object>> saveKeyInfo(
            @PathVariable String name,
            @RequestBody Map<String, List<String>> body) {
        return ApiResponse.ok(skillAnalysisService.saveKeyInfo(name, body.get("keywords")));
    }
}
