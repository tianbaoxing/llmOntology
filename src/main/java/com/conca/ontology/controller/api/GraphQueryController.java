package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.GraphQueryService;

/** 管理端入口：图谱统计与节点详情（LLM 专用 query/neighbors 已迁 /api/llm/graph） */
@RestController
@RequestMapping("/api/graph")
public class GraphQueryController {

    private final GraphQueryService queryService;

    public GraphQueryController(GraphQueryService queryService) {
        this.queryService = queryService;
    }

    /** 图谱统计（各 label 节点数 / 各 type 关系数） */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(queryService.stats());
    }

    /** 分页实例列表（概览"实例数"跳转页数据源） */
    @GetMapping("/nodes")
    public ApiResponse<Map<String, Object>> nodes(
            @RequestParam String label,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(queryService.nodesPage(label, page, size));
    }

    /** 节点详情 + 出入边 */
    @GetMapping("/nodes/{label}/{key}")
    public ApiResponse<Map<String, Object>> node(@PathVariable String label, @PathVariable String key) {
        return ApiResponse.ok(queryService.nodeDetail(label, key));
    }

    /** 邻域查询（depth 1..3，graph.html 数据源；skip/limit 按边分页增量加载） */
    @GetMapping("/nodes/{label}/{key}/neighbors")
    public ApiResponse<Map<String, Object>> neighbors(@PathVariable String label, @PathVariable String key,
                                                      @RequestParam(defaultValue = "1") int depth,
                                                      @RequestParam(defaultValue = "0") int skip,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(queryService.neighbors(label, key, depth, skip, limit));
    }
}
