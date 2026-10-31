package com.conca.ontology.disjoint.api;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.disjoint.dto.DisjointRule;
import com.conca.ontology.disjoint.dto.DisjointRulesDTO;
import com.conca.ontology.disjoint.service.DisjointRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * @author tianbaoxing
 * @date 2026-09-11
 */
@Tag(name = "Admin-DisjointRules", description = "互斥约束词典（简化版）：规则为带【】候选项的自由文本，如「材质互斥：一件器物主体不能同时是【青铜】且【瓷】」")
@RestController
@RequestMapping("/api/disjoint-rules")
public class DisjointRulesController {

    private final DisjointRuleService service;

    public DisjointRulesController(DisjointRuleService service) {
        this.service = service;
    }

    @Operation(summary = "全量规则", description = "读取全部互斥约束规则（按规则 ID 字典序）")
    @GetMapping
    public ApiResponse<DisjointRulesDTO> getAll() {
        return ApiResponse.ok(service.loadAll());
    }

    @Operation(summary = "下一个自增 ID", description = "返回 R001/R002... 形式的下一个可用 ID，前端新增时预填")
    @GetMapping("/next-id")
    public ApiResponse<String> nextId() {
        return ApiResponse.ok(service.nextId());
    }

    @Operation(summary = "单条规则详情")
    @GetMapping("/{id}")
    public ApiResponse<DisjointRule> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(summary = "新增/覆盖规则", description = "id 唯一；存在即覆盖，不存在即新增。校验：text 中【】包裹的候选项至少 2 个。")
    @PostMapping
    public ApiResponse<DisjointRulesDTO> save(@RequestBody DisjointRule rule) {
        return ApiResponse.ok(service.save(rule));
    }

    @Operation(summary = "删除规则")
    @DeleteMapping("/{id}")
    public ApiResponse<DisjointRulesDTO> delete(@PathVariable String id) {
        return ApiResponse.ok(service.delete(id));
    }
}
