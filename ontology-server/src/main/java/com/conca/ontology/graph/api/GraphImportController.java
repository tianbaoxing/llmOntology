package com.conca.ontology.graph.api;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.graph.dto.NodeCreateDTO;
import com.conca.ontology.graph.dto.NodeCreateResultDTO;
import com.conca.ontology.graph.dto.NodeDeleteResultDTO;
import com.conca.ontology.graph.dto.NodeUpdateDTO;
import com.conca.ontology.graph.dto.NodeUpdateResultDTO;
import com.conca.ontology.graph.dto.RelCreateResultDTO;
import com.conca.ontology.graph.dto.RelDeleteResultDTO;
import com.conca.ontology.graph.dto.RelDTO;
import com.conca.ontology.graph.dto.RelUpdateResultDTO;
import com.conca.ontology.graph.service.GraphWriteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 管理端入口：图数据单条 CRUD（LLM 专用批量写入已迁 /api/llm/graph） */
@Tag(name = "Admin-GraphWrite", description = "管理页图数据单条 CRUD：节点（MERGE 幂等/SET +=/DETACH DELETE）、"
        + "关系（type + 两端键定位）。与 LLM 平面同源同逻辑。")

/**
 * @author tianbaoxing
 * @date 2026-08-19
 */
@RestController
@RequestMapping("/api/graph")
public class GraphImportController {

    private final GraphWriteService writeService;

    public GraphImportController(GraphWriteService writeService) {
        this.writeService = writeService;
    }

    @Operation(summary = "创建单个节点", description = "MERGE 幂等，存在则覆盖属性；props 必须含 keyProp 值。")
    @PostMapping("/nodes")
    public ApiResponse<NodeCreateResultDTO> createNode(@Valid @RequestBody NodeCreateDTO req) {
        return ApiResponse.ok(writeService.createNode(req.label(), req.props()));
    }

    @Operation(summary = "更新节点属性", description = "SET += 增量合并；keyProp 不可改；节点不存在 404。")
    @PutMapping("/nodes/{label}/{key}")
    public ApiResponse<NodeUpdateResultDTO> updateNode(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key,
            @Valid @RequestBody NodeUpdateDTO req) {
        return ApiResponse.ok(writeService.updateNode(label, key, req.props()));
    }

    @Operation(summary = "删除节点", description = "detach 默认 true 连带删关系；false 时仍有关系则 409。")
    @DeleteMapping("/nodes/{label}/{key}")
    public ApiResponse<NodeDeleteResultDTO> deleteNode(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key,
            @Parameter(description = "是否连带删除关系", example = "true")
            @RequestParam(defaultValue = "true") boolean detach) {
        return ApiResponse.ok(writeService.deleteNode(label, key, detach));
    }

    @Operation(summary = "创建关系", description = "MERGE 幂等；签名须匹配 schema；两端节点须存在（404）。")
    @PostMapping("/relations")
    public ApiResponse<RelCreateResultDTO> createRel(@Valid @RequestBody RelDTO req) {
        return ApiResponse.ok(writeService.createRel(req));
    }

    @Operation(summary = "更新关系属性", description = "按 type + 两端键定位，SET += 属性。")
    @PutMapping("/relations")
    public ApiResponse<RelUpdateResultDTO> updateRel(@Valid @RequestBody RelDTO req) {
        return ApiResponse.ok(writeService.updateRel(req));
    }

    @Operation(summary = "删除关系", description = "按 type + 两端键定位并删除。")
    @DeleteMapping("/relations")
    public ApiResponse<RelDeleteResultDTO> deleteRel(@Valid @RequestBody RelDTO req) {
        return ApiResponse.ok(writeService.deleteRel(req));
    }
}
