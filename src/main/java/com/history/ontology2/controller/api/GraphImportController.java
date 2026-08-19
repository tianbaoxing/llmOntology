package com.history.ontology2.controller.api;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.history.ontology2.dto.ApiResponse;
import com.history.ontology2.dto.NodeBatchRequest;
import com.history.ontology2.dto.NodeCreateRequest;
import com.history.ontology2.dto.NodeUpdateRequest;
import com.history.ontology2.dto.RelBatchRequest;
import com.history.ontology2.dto.RelRequest;
import com.history.ontology2.service.GraphImportService;
import com.history.ontology2.service.GraphWriteService;

@RestController
@RequestMapping("/api/graph")
public class GraphImportController {

    private final GraphImportService importService;
    private final GraphWriteService writeService;

    public GraphImportController(GraphImportService importService, GraphWriteService writeService) {
        this.importService = importService;
        this.writeService = writeService;
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
