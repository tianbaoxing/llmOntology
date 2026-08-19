package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.GraphQueryService;

/** admin 入口：图谱统计与节点详情（LLM 专用 query/neighbors 已迁 /api/llm/graph） */
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

    /** 节点详情 + 出入边 */
    @GetMapping("/node/{label}/{key}")
    public ApiResponse<Map<String, Object>> node(@PathVariable String label, @PathVariable String key) {
        return ApiResponse.ok(queryService.nodeDetail(label, key));
    }
}
