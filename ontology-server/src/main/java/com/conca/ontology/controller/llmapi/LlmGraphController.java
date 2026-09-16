package com.conca.ontology.controller.llmapi;

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
import com.conca.ontology.dto.result.BatchNodesResult;
import com.conca.ontology.dto.result.BatchRelsResult;
import com.conca.ontology.dto.view.GraphStatsView;
import com.conca.ontology.dto.view.NeighborsView;
import com.conca.ontology.dto.request.NodeBatchRequest;
import com.conca.ontology.dto.request.NodeCreateRequest;
import com.conca.ontology.dto.result.NodeCreateResult;
import com.conca.ontology.dto.result.NodeDeleteResult;
import com.conca.ontology.dto.request.NodeUpdateRequest;
import com.conca.ontology.dto.result.NodeUpdateResult;
import com.conca.ontology.dto.request.QueryRequest;
import com.conca.ontology.dto.result.QueryResult;
import com.conca.ontology.dto.request.RelBatchRequest;
import com.conca.ontology.dto.result.RelCreateResult;
import com.conca.ontology.dto.result.RelDeleteResult;
import com.conca.ontology.dto.request.RelRequest;
import com.conca.ontology.dto.result.RelUpdateResult;
import com.conca.ontology.service.GraphImportService;
import com.conca.ontology.service.GraphQueryService;
import com.conca.ontology.service.GraphWriteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * LLM 专属入口：图数据读写，供构建期实例抽取、批量灌数与对账校验。
 * 与 admin 入口（/api/graph 单条 CRUD/stats/node）同源同逻辑，双入口隔离调用方；
 * query/neighbors/batch 仅 LLM 使用，只在此暴露。
 */
@Tag(name = "LLM-Graph", description = "图数据读写：通用只读查询、统计、节点/关系单条 CRUD 与批量幂等写入。"
        + "安全约束：label/type/属性名须在 schema 白名单内（校验后才拼接 Cypher），数据值全参数绑定。"
        + "与 admin 入口 /api/graph 同源同逻辑；query/neighbors/batch 仅本平面暴露。")
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

    @Operation(summary = "通用只读 Cypher 查询",
            description = "执行任意只读 Cypher（对账校验用）。红线：CREATE/MERGE/DELETE/SET/DROP/CALL 等"
                    + "写语句开头即拒（400）。params 中的键作为 $参数 绑定。返回 keys（列名）+ rows（动态对象数组）+ count。")
    @PostMapping("/query")
    public ApiResponse<QueryResult> query(@Valid @RequestBody QueryRequest req) {
        return ApiResponse.ok(queryService.query(req.cypher(), req.params()));
    }

    @Operation(summary = "图谱统计",
            description = "各 label 节点数 + 各 type 关系数（均按名称字典序）。")
    @GetMapping("/stats")
    public ApiResponse<GraphStatsView> stats() {
        return ApiResponse.ok(queryService.stats());
    }

    @Operation(summary = "节点详情 + 出入边",
            description = "返回该节点的全部出入边（每行含本节点属性、关系类型与属性、对端节点属性），上限 200 行。"
                    + "对账抽查用。label 须在白名单内，节点不存在时 rows 为空。")
    @GetMapping("/node/{label}/{key}")
    public ApiResponse<QueryResult> node(
            @Parameter(description = "节点标签（schema 白名单内）", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值（keyProp 对应属性的值）", example = "战国") @PathVariable String key) {
        return ApiResponse.ok(queryService.nodeDetail(label, key));
    }

    @Operation(summary = "邻域查询",
            description = "以节点为起点的 N 跳邻域（depth 1..3，默认 1）。返回起点节点引用 + 去重边分页"
                    + "（skip/limit 作用于按 eid 排序后的边，limit 上限 500）+ 边总数。"
                    + "节点不存在返回 404。")
    @GetMapping("/neighbors")
    public ApiResponse<NeighborsView> neighbors(
            @Parameter(description = "起点节点标签", example = "Era") @RequestParam String label,
            @Parameter(description = "起点唯一键值", example = "战国") @RequestParam String key,
            @Parameter(description = "遍历深度 1..3（超界自动收敛）", example = "1") @RequestParam(defaultValue = "1") int depth) {
        return ApiResponse.ok(queryService.neighbors(label, key, depth));
    }

    @Operation(summary = "创建单个节点",
            description = "MERGE 幂等：节点已存在则覆盖属性，不存在则新建。props 必须含该类 keyProp 的值"
                    + "（缺失返回 400）。返回 existed 标识本次是新建还是覆盖。")
    @PostMapping("/nodes")
    public ApiResponse<NodeCreateResult> createNode(@Valid @RequestBody NodeCreateRequest req) {
        return ApiResponse.ok(writeService.createNode(req.label(), req.props()));
    }

    @Operation(summary = "更新节点属性",
            description = "对已有节点 SET += 属性（增量合并）。keyProp 不可改：body 中若带 keyProp 会被剔除；"
                    + "剔除后无可更新属性返回 400；节点不存在返回 404。")
    @PutMapping("/nodes/{label}/{key}")
    public ApiResponse<NodeUpdateResult> updateNode(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key,
            @Valid @RequestBody NodeUpdateRequest req) {
        return ApiResponse.ok(writeService.updateNode(label, key, req.props()));
    }

    @Operation(summary = "删除节点",
            description = "detach=true（默认）连带删除节点上的全部关系；detach=false 时若仍有关系返回 409。"
                    + "节点不存在返回 404。")
    @DeleteMapping("/nodes/{label}/{key}")
    public ApiResponse<NodeDeleteResult> deleteNode(
            @Parameter(description = "节点标签", example = "Era") @PathVariable String label,
            @Parameter(description = "唯一键值", example = "战国") @PathVariable String key,
            @Parameter(description = "是否连带删除关系（默认 true）", example = "true")
            @RequestParam(defaultValue = "true") boolean detach) {
        return ApiResponse.ok(writeService.deleteNode(label, key, detach));
    }

    @Operation(summary = "创建关系",
            description = "MERGE 幂等：关系已存在则覆盖属性。前置条件：签名（type + fromLabel→toLabel）"
                    + "须与 schema 声明一致（否则 400）；两端节点必须已存在（否则 404，先建节点再连边）。"
                    + "返回 existed 标识本次是新建还是覆盖。")
    @PostMapping("/rels")
    public ApiResponse<RelCreateResult> createRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.createRel(req));
    }

    @Operation(summary = "更新关系属性",
            description = "按 type + 两端键定位关系，SET += 属性。props 为空返回 400；关系不存在返回 404。")
    @PutMapping("/rels")
    public ApiResponse<RelUpdateResult> updateRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.updateRel(req));
    }

    @Operation(summary = "删除关系",
            description = "按 type + 两端键定位并删除。关系不存在返回 404。")
    @DeleteMapping("/rels")
    public ApiResponse<RelDeleteResult> deleteRel(@Valid @RequestBody RelRequest req) {
        return ApiResponse.ok(writeService.deleteRel(req));
    }

    @Operation(summary = "批量写入节点",
            description = "UNWIND + MERGE 幂等批量写入（构建期灌数主入口）。keyProp 以服务端 schema 为准"
                    + "（body 中的 keyProp 字段被忽略）；每条 item 必须含 keyProp 值（缺失返回 400，整体失败）；"
                    + "单批上限 graph.batch-limit（默认 10000，超限 400）。")
    @PostMapping("/nodes/batch")
    public ApiResponse<BatchNodesResult> batchNodes(@Valid @RequestBody NodeBatchRequest req) {
        return ApiResponse.ok(importService.batchNodes(req));
    }

    @Operation(summary = "批量写入关系",
            description = "UNWIND + MATCH 两端 + MERGE 幂等批量写入。签名校验同单条创建；"
                    + "items 中 from/to 为两端节点的唯一键值，任一端节点不存在则该条静默跳过（MATCH 不中）；"
                    + "单批上限 graph.batch-limit（默认 10000）。")
    @PostMapping("/rels/batch")
    public ApiResponse<BatchRelsResult> batchRels(@Valid @RequestBody RelBatchRequest req) {
        return ApiResponse.ok(importService.batchRels(req));
    }
}
