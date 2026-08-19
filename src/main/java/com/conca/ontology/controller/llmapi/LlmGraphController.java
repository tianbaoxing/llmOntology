package com.conca.ontology.controller.llmapi;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.NodeBatchRequest;
import com.conca.ontology.dto.NodeCreateRequest;
import com.conca.ontology.dto.NodeUpdateRequest;
import com.conca.ontology.dto.QueryRequest;
import com.conca.ontology.dto.RelBatchRequest;
import com.conca.ontology.dto.RelRequest;
import com.conca.ontology.service.GraphImportService;
import com.conca.ontology.service.GraphQueryService;
import com.conca.ontology.service.GraphWriteService;

/**
 * LLM 专属入口：图数据读写，供构建期实例抽取、批量灌数与对账校验。
 * 与 admin 入口（/api/graph 单条 CRUD/stats/node）同源同逻辑，双入口隔离调用方；
 * query/neighbors/batch 仅 LLM 使用，只在此暴露。
 */
@RestController
@RequestMapping("/api/llm/graph")
public class LlmGraphController {

    private final GraphQueryService queryService;
    private final GraphWriteService writeService;
    private final GraphImportService importService;

    public LlmGraphController(GraphQueryService queryService, GraphWriteService writeService,
                              GraphImportService importService) {
        this.queryService = queryService;
        this.writeService = writeService;
        this.importService = importService;
    }

    /** 通用只读 Cypher 查询（对账校验用） */
    @PostMapping("/query")
    public ApiResponse<Map<String, Object>> query(@Valid @RequestBody QueryRequest req) {
        return ApiResponse.ok(queryService.query(req.cypher(), req.params()));
    }

    /** 图谱统计（各 label 节点数 / 各 type 关系数） */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(queryService.stats());
    }

    /** 节点详情 + 出入边（对账抽查用） */
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

    /** 创建单个节点（MERGE 幂等，存在则覆盖属性） */
    @PostMapping("/nodes")
    public ApiResponse<Map<String, Object>> createNode(@Valid @RequestBody NodeCreateRequest req) {
        return ApiResponse.ok(writeService.createNode(req.label(), req.props()));
    }

    /** 更新节点属性（keyProp 不可改） */
    @PutMapping("/nodes/{label}/{key}")
    public ApiResponse<Map<String, Object>> updateNode(@PathVariable String label, @PathVariable String key,
                                                       @Valid @RequestBody NodeUpdateRequest req) {
        return ApiResponse.ok(writeService.updateNode(label, key, req.props()));
    }

    /** 删除节点（detach 默认 true：连带删除关系） */
    @DeleteMapping("/nodes/{label}/{key}")
    public ApiResponse<Map<String, Object>> deleteNode(@PathVariable String label, @PathVariable String key,
                                                       @RequestParam(defaultValue = "true") boolean detach) {
        return ApiResponse.ok(writeService.deleteNode(label, key, detach));
    }

    /** 创建关系（MERGE 幂等，两端节点须存在，签名须匹配 schema 声明） */
    @PostMapping("/rels")
    public ApiResponse<Map<String, Object>> createRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.createRel(req));
    }

    /** 更新关系属性（type + 两端键定位） */
    @PutMapping("/rels")
    public ApiResponse<Map<String, Object>> updateRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.updateRel(req));
    }

    /** 删除关系（type + 两端键定位） */
    @DeleteMapping("/rels")
    public ApiResponse<Map<String, Object>> deleteRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.deleteRel(req));
    }

    /** 批量幂等写入节点（UNWIND + MERGE） */
    @PostMapping("/nodes/batch")
    public ApiResponse<Map<String, Object>> batchNodes(@Valid @RequestBody NodeBatchRequest req) {
        return ApiResponse.ok(importService.batchNodes(req));
    }

    /** 批量幂等写入关系（MATCH 两端 + MERGE） */
    @PostMapping("/rels/batch")
    public ApiResponse<Map<String, Object>> batchRels(@Valid @RequestBody RelBatchRequest req) {
        return ApiResponse.ok(importService.batchRels(req));
    }
}
