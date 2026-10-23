package com.conca.ontology.quality.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "节点类分布直方图：含分桶 + 汇总统计")
public record ClassHistogramDTO(
        @Schema(description = "每个类的分布桶（按节点数降序）") List<ClassBinDTO> bins,
        @Schema(description = "最少节点类的数量", example = "1") long minCount,
        @Schema(description = "最多节点类的数量", example = "1287") long maxCount,
        @Schema(description = "平均每类节点数", example = "256.4") double avgCount,
        @Schema(description = "中位数每类节点数", example = "128") long medianCount) {
}
