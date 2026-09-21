package com.conca.ontology.quality.dto.view;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "出入度异常节点明细：度值超过 均值 + N * 标准差 视为异常")
public record DegreeAnomalyView(
        @Schema(description = "节点标签") String label,
        @Schema(description = "该类 keyProp 属性名") String keyProp,
        @Schema(description = "keyProp 对应值") Object keyValue,
        @Schema(description = "节点展示名") String displayName,
        @Schema(description = "Neo4j elementId") String elementId,
        @Schema(description = "实际度值（入度或出度）", example = "127") long degree,
        @Schema(description = "偏离均值的 σ 倍数（degree - avg）/ std；越大越异常", example = "4.72") double deviation) {
}
