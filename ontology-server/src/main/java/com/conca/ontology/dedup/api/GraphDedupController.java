package com.conca.ontology.dedup.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dedup.dto.request.DedupScanRequest;
import com.conca.ontology.dedup.dto.result.DedupScanResult;
import com.conca.ontology.dedup.service.DedupScanService;
import com.conca.ontology.shared.web.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 图谱实体去重 REST 接口（M1）：
 *   - 扫描（scan）+ 候选组列表（内嵌在 scan result 中，支持分页）
 *   - 合并执行（/execute）、组详情、Disjoint 全图校验留后续里程碑。
 * 全部只读操作，查询走 GraphQueryService.query() 以享受 WRITE_RE 写操作拦截保护。
 */
@RestController
@RequestMapping("/api/graph/dedup")
@Tag(name = "Admin-GraphDedup", description = "图谱实体去重（M1 里程碑）："
        + "规范化别名 + 精确 key 组合两种确定性去重算法，发现同义异名重复节点，"
        + "输出候选组列表与 Survivor 推荐；合并执行接口留 M3 实现。管理员接口。")
public class GraphDedupController {

    private final DedupScanService service;

    public GraphDedupController(DedupScanService service) {
        this.service = service;
    }

    @Operation(summary = "启动去重扫描（等价于 GET /groups 带全量聚合 KPI）",
            description = "按 label（默认 *=全量逐类）扫描，先规范化别名（去除遗址/墓群/朝代后缀、去括号、全角半角统一），"
                    + "再精确 key 组合（name+辅助属性 / CulturalRelic 的 title 或 title+尺寸）；"
                    + "综合分 ≥ threshold 判定为重复候选组，返回 KPI 汇总 + 候选组分页列表。"
                    + "候选组内按 survivorPriority 给出合并后保留节点（Survivor）建议，按综合分降序排序。")
    @PostMapping("/scan")
    public ApiResponse<DedupScanResult> scan(@RequestBody(required = false) DedupScanRequest req) {
        return ApiResponse.ok(service.scan(req == null ? new DedupScanRequest(null, null, null, null, null) : req));
    }

    @Operation(summary = "快捷 GET 扫描（默认参数：全量 label，threshold 0.95，不限制组数）",
            description = "与 POST /scan 相同语义，便于 Swagger Try it out 一键调用与 curl 调试。")
    @GetMapping("/scan")
    public ApiResponse<DedupScanResult> scanGet(
            @RequestParam(required = false) String label,
            @RequestParam(required = false) Double threshold,
            @RequestParam(required = false) Integer limitGroups,
            @RequestParam(required = false) Integer skipGroups,
            @RequestParam(required = false) String ruleSetId) {
        return ApiResponse.ok(service.scan(new DedupScanRequest(label, threshold, limitGroups, skipGroups, ruleSetId)));
    }
}
