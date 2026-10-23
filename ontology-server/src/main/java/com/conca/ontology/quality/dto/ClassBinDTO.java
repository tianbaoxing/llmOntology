package com.conca.ontology.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "节点类分布直方桶：单类的节点数")
public record ClassBinDTO(
        @Schema(description = "标签（类名）", example = "CulturalRelic") String label,
        @Schema(description = "中文名", example = "文物") String cnName,
        @Schema(description = "层归属：concept / instance", example = "instance") String layer,
        @Schema(description = "节点数量", example = "1287") long count,
        @Schema(description = "占节点总数的百分比（0-100）", example = "27.3") double pctOfTotal) {
}
