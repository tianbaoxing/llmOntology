package com.conca.ontology.quality.dto.view;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "完整图谱质量诊断报告：三大模块汇总 + 全局 KPI")
public record QualityReportView(
        @Schema(description = "报告生成时间戳（毫秒，epoch）", example = "1758348120000") long generatedAtMs,
        @Schema(description = "节点总数", example = "4712") long totalNodes,
        @Schema(description = "关系总数", example = "11340") long totalRelations,
        @Schema(description = "Schema 声明的类总数", example = "10") int classCount,
        @Schema(description = "Schema 声明的关系类型总数", example = "12") int relationCount,
        @Schema(description = "模块一：拓扑异常诊断") TopologyAnomalyView topology,
        @Schema(description = "模块二：Schema 覆盖率统计") SchemaCoverageView coverage,
        @Schema(description = "模块三：节点类分布直方图") ClassHistogramView classHistogram,
        @Schema(description = "模块四：关系类分布直方图") RelationHistogramView relHistogram) {
}
