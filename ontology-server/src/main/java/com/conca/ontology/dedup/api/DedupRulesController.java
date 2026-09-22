package com.conca.ontology.dedup.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dedup.model.DedupRuleset;
import com.conca.ontology.dedup.model.DedupRulesIndexItem;
import com.conca.ontology.dedup.service.DedupRulesService;
import com.conca.ontology.shared.web.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/api/graph/dedup")
public class DedupRulesController {

    private final DedupRulesService service;

    public DedupRulesController(DedupRulesService service) {
        this.service = service;
    }

    @Operation(summary = "列出所有规则集（索引）", description = "返回所有规则集的轻量条目（id/name/version/active），用于工具栏下拉和配置页左侧列表。")
    @GetMapping("/rules")
    public ApiResponse<List<DedupRulesIndexItem>> listIndex() {
        return ApiResponse.ok(service.listAll());
    }

    @Operation(summary = "获取单套规则集完整内容", description = "不指定 id 时返回当前 active 规则集。")
    @GetMapping({"/rules/{id}"})
    public ApiResponse<DedupRuleset> get(
            @Parameter(description = "规则集 ID；空=当前 active") @PathVariable(required = false) String id) {
        return ApiResponse.ok(service.getOrDefault(id));
    }

    @Operation(summary = "获取当前激活的规则集", description = "等价于 GET /rules/{activeId}")
    @GetMapping("/rules/active")
    public ApiResponse<DedupRuleset> getActive() {
        return ApiResponse.ok(service.getActive());
    }

    @Operation(summary = "保存（新建 / 整体覆盖）规则集", description = "id 为空=新建（后端生成 UUID）；id 已存在=整体覆盖并 version+1（乐观锁：version 不匹配返回 409）。保存前做 Schema 属性名校验，不存在返回 400+可选值。")
    @PostMapping("/rules")
    public ApiResponse<DedupRulesIndexItem> save(@RequestBody DedupRuleset ruleset) {
        return ApiResponse.ok(service.save(ruleset));
    }

    @Operation(summary = "切换激活的规则集", description = "设为 active 的规则集 = data.html 工具栏下拉默认选中项。")
    @PostMapping("/rules/{id}/activate")
    public ApiResponse<List<DedupRulesIndexItem>> activate(
            @Parameter(description = "规则集 ID") @PathVariable String id) {
        return ApiResponse.ok(service.setActive(id));
    }

    @Operation(summary = "删除规则集", description = "若删除的是当前 active，自动将剩余第一条规则集切为 active；若删除后无规则，则 active 置空。")
    @DeleteMapping("/rules/{id}")
    public ApiResponse<List<DedupRulesIndexItem>> delete(
            @Parameter(description = "规则集 ID") @PathVariable String id) {
        return ApiResponse.ok(service.delete(id));
    }

    public record PreviewRequest(DedupRuleset rules, DedupRulesService.PreviewSample sample) {}

    @Operation(summary = "规则预览", description = "提交一份临时规则 + 两个样例字符串（含可选 props），返回：是否命中同一候选组、规范化过程（剥离哪些后缀）、命中的子策略（norm 或 exact-*）。")
    @PostMapping("/rules/preview")
    public ApiResponse<DedupRulesService.PreviewResult> preview(@RequestBody PreviewRequest req) {
        if (req == null || req.rules() == null || req.sample() == null) {
            return ApiResponse.error(400, "preview 请求必须包含 rules + sample 两部分");
        }
        return ApiResponse.ok(service.preview(req.rules(), req.sample()));
    }
}
