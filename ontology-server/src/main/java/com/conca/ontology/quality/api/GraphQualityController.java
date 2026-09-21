package com.conca.ontology.quality.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.quality.dto.view.ClassHistogramView;
import com.conca.ontology.quality.dto.view.QualityReportView;
import com.conca.ontology.quality.dto.view.RelationHistogramView;
import com.conca.ontology.quality.dto.view.SchemaCoverageView;
import com.conca.ontology.quality.dto.view.TopologyAnomalyView;
import com.conca.ontology.quality.service.GraphDiagnosticsService;
import com.conca.ontology.shared.web.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 图谱质量诊断 REST 接口：拓扑异常、Schema 覆盖率、分布直方图。
 * 全部为只读查询，通过 GraphQueryService WRITE_RE 正则防写。
 */
@RestController
@RequestMapping("/api/graph/quality")
@Tag(name = "Admin-GraphQuality", description = "图谱质量诊断：拓扑异常（孤立点/自环/出入度极端值）、"
        + "Schema 覆盖率（声明但零实例的类/关系）、节点/关系类分布直方图。管理员只读接口。")
public class GraphQualityController {

    private final GraphDiagnosticsService service;

    public GraphQualityController(GraphDiagnosticsService service) {
        this.service = service;
    }

    @Operation(summary = "全量质量诊断报告", description = "一次性返回 4 大模块：拓扑异常 + Schema 覆盖率 + "
            + "节点分布直方图 + 关系分布直方图，供页面初次加载或手动刷新。")
    @GetMapping("/report")
    public ApiResponse<QualityReportView> report() {
        return ApiResponse.ok(service.fullReport());
    }

    @Operation(summary = "仅拓扑异常模块", description = "孤立点、自环、悬挂边、出入度 3σ 异常 Top-K。")
    @GetMapping("/topology")
    public ApiResponse<TopologyAnomalyView> topology() {
        return ApiResponse.ok(service.topologyAnomaly(20, 500));
    }

    @Operation(summary = "仅 Schema 覆盖率模块", description = "对比 graph-schema.json 的声明与库中实际实例，"
            + "输出未使用（零实例）的类/关系明细及覆盖率百分比。")
    @GetMapping("/coverage")
    public ApiResponse<SchemaCoverageView> coverage() {
        return ApiResponse.ok(service.schemaCoverage());
    }

    @Operation(summary = "仅节点类分布直方图", description = "各 label 的节点数 + 占比 + min/avg/median/max 统计。")
    @GetMapping("/histogram/nodes")
    public ApiResponse<ClassHistogramView> classHistogram() {
        return ApiResponse.ok(service.classHistogram());
    }

    @Operation(summary = "仅关系类分布直方图", description = "各关系类型的边数 + 占比 + min/avg/median/max 统计。")
    @GetMapping("/histogram/relations")
    public ApiResponse<RelationHistogramView> relHistogram() {
        return ApiResponse.ok(service.relationHistogram());
    }
}
