package com.conca.ontology.quality.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "关系类分布直方图：含分桶 + 汇总统计")
public record RelationHistogramDTO(
        @Schema(description = "每个关系类型的分布桶（按边数降序）") List<RelBinDTO> bins,
        @Schema(description = "最少边关系类型的边数", example = "0") long minCount,
        @Schema(description = "最多边关系类型的边数", example = "2501") long maxCount,
        @Schema(description = "平均每类关系数", example = "312.5") double avgCount,
        @Schema(description = "中位数每类关系数", example = "97") long medianCount) {
}
