package com.conca.ontology.graph.api;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.graph.dto.view.GraphStatsView;
import com.conca.ontology.graph.dto.view.NeighborGroupsView;
import com.conca.ontology.graph.dto.view.NeighborsView;
import com.conca.ontology.graph.dto.view.NodesPageView;
import com.conca.ontology.graph.dto.result.QueryResult;
import com.conca.ontology.graph.service.GraphQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 管理端入口：图谱统计与节点详情（LLM 专用 query/neighbors 已迁 /api/llm/graph） */
@Tag(name = "Admin-Graph", description = "管理页图数据查询：统计、实例分页、节点详情、邻域（graph.html 数据源）。"
        + "与 LLM 平面同源同逻辑。")
@RestController
@RequestMapping("/api/graph")
public class GraphQueryController {

    private final GraphQueryService queryService;

    public GraphQueryController(GraphQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "图谱统计", description = "各 label 节点数 / 各 type 关系数。")
    @GetMapping("/stats")
    public ApiResponse<GraphStatsView> stats() {
        return ApiResponse.ok(queryService.stats());
    }

    @Operation(summary = "分页实例列表", description = "概览页\"实例数\"跳转的数据源；按唯一键排序分页。")
    @GetMapping("/nodes")
    public ApiResponse<NodesPageView> nodes(
            @Parameter(description = "节点标签", example = "Era") @RequestParam String label,
            @Parameter(description = "页码（从 1 起）", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小（1..200）", example = "50") @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(queryService.nodesPage(label, page, size));
    }

    @Operation(summary = "节点详情 + 出入边", description = "返回节点属性与全部出入边（上限 200 行）。")
    @GetMapping("/nodes/{label}/{key}")
    public ApiResponse<QueryResult> node(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key) {
        return ApiResponse.ok(queryService.nodeDetail(label, key));
    }

    @Operation(summary = "邻域查询（分页 + 过滤）",
            description = "graph.html 数据源：depth 1..3；skip/limit 按边分页增量加载；"
                    + "过滤参数（dir/relType/otherLabel/f）用于分组过滤查询，三者须同时出现。")
    @GetMapping("/nodes/{label}/{key}/neighbors")
    public ApiResponse<NeighborsView> neighbors(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key,
            @Parameter(description = "遍历深度 1..3", example = "1") @RequestParam(defaultValue = "1") int depth,
            @Parameter(description = "边分页偏移", example = "0") @RequestParam(defaultValue = "0") int skip,
            @Parameter(description = "边分页大小（上限 500）", example = "100") @RequestParam(defaultValue = "100") int limit,
            @Parameter(description = "过滤：方向 in|out（与 relType/otherLabel 同时出现）") @RequestParam(required = false) String dir,
            @Parameter(description = "过滤：关系类型") @RequestParam(required = false) String relType,
            @Parameter(description = "过滤：对端节点标签") @RequestParam(required = false) String otherLabel,
            @Parameter(description = "过滤：对端属性条件，格式 prop:op:value（op=eq|like），可多个")
            @RequestParam(required = false) List<String> f) {
        return ApiResponse.ok(queryService.neighbors(label, key, depth, skip, limit, dir, relType, otherLabel, f));
    }

    @Operation(summary = "邻居分组统计", description = "按 方向×关系类型×对端标签 聚合的边数（graph.html 分组表数据源）。")
    @GetMapping("/nodes/{label}/{key}/neighbor-groups")
    public ApiResponse<NeighborGroupsView> neighborGroups(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key) {
        return ApiResponse.ok(queryService.neighborGroups(label, key));
    }
}
