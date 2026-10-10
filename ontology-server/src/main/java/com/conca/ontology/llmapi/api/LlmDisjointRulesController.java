package com.conca.ontology.llmapi.api;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.disjoint.dto.DisjointCheckRequest;
import com.conca.ontology.disjoint.dto.view.DisjointConflictView;
import com.conca.ontology.disjoint.dto.view.DisjointRulesView;
import com.conca.ontology.disjoint.service.DisjointRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "LLM-DisjointRules", description = "互斥约束词典 LLM 平面：读规则+冲突检测。"
        + "LLM 生成最终回复/写入前必须跑 check，有冲突必须终止并告知用户冲突规则。")
@RestController
@RequestMapping("/api/llm/disjoint-rules")
public class LlmDisjointRulesController {

    private final DisjointRuleService service;

    public LlmDisjointRulesController(DisjointRuleService service) {
        this.service = service;
    }

    @Operation(summary = "全量规则（免登录，只读）",
            description = "返回全部互斥约束规则原文（按规则 ID 字典序）。用于需要把规则注入 LLM 上下文的场景。")
    @GetMapping
    public ApiResponse<DisjointRulesView> all() {
        return ApiResponse.ok(service.loadAll());
    }

    @Operation(summary = "冲突检测",
            description = "输入一段文本，返回其中触发的互斥冲突明细。conflict[] 为空表示文本无冲突。"
                    + "冲突非空时，LLM 必须直接终止当前生成/写入，按返回的 ruleText 逐条向用户说明冲突点。")
    @PostMapping("/check")
    public ApiResponse<CheckResult> check(@RequestBody(required = false) DisjointCheckRequest req) {
        String text = req == null ? "" : req.text();
        List<DisjointConflictView> conflicts = service.checkConflicts(text);
        return ApiResponse.ok(new CheckResult(text == null ? 0 : text.length(), conflicts.size(), conflicts));
    }

    public record CheckResult(
            int textLength,
            int conflictCount,
            List<DisjointConflictView> conflicts) {
    }
}
