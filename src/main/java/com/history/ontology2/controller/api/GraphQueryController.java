package com.history.ontology2.controller.api;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.history.ontology2.dto.ApiResponse;
import com.history.ontology2.dto.QueryRequest;
import com.history.ontology2.service.GraphQueryService;

@RestController
@RequestMapping("/api/graph")
public class GraphQueryController {

    private final GraphQueryService queryService;

    public GraphQueryController(GraphQueryService queryService) {
        this.queryService = queryService;
    }

    /** 通用只读 Cypher 查询 */
    @PostMapping("/query")
    public ApiResponse<Map<String, Object>> query(@Valid @RequestBody QueryRequest req) {
        return ApiResponse.ok(queryService.query(req.cypher(), req.params()));
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

    /** 邻域查询（depth 1..3） */
    @GetMapping("/neighbors")
    public ApiResponse<Map<String, Object>> neighbors(
            @RequestParam String label,
            @RequestParam String key,
            @RequestParam(defaultValue = "1") int depth) {
        return ApiResponse.ok(queryService.neighbors(label, key, depth));
    }
}
