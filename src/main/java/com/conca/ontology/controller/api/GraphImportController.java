package com.conca.ontology.controller.api;

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

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.NodeCreateRequest;
import com.conca.ontology.dto.NodeUpdateRequest;
import com.conca.ontology.dto.RelRequest;
import com.conca.ontology.service.GraphWriteService;

/** 管理端入口：图数据单条 CRUD（LLM 专用批量写入已迁 /api/llm/graph） */
@RestController
@RequestMapping("/api/graph")
public class GraphImportController {

    private final GraphWriteService writeService;

    public GraphImportController(GraphWriteService writeService) {
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
    @PostMapping("/relations")
    public ApiResponse<Map<String, Object>> createRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.createRel(req));
    }

    /** 更新关系属性（type + 两端键定位） */
    @PutMapping("/relations")
    public ApiResponse<Map<String, Object>> updateRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.updateRel(req));
    }

    /** 删除关系（type + 两端键定位） */
    @DeleteMapping("/relations")
    public ApiResponse<Map<String, Object>> deleteRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.deleteRel(req));
    }
}
