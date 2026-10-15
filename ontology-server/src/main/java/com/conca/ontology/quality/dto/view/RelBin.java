package com.conca.ontology.quality.dto.view;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "关系类分布直方桶：单关系类型的边数")
public record RelBin(
        @Schema(description = "关系类型名", example = "BELONGS_TO_ERA") String relType,
        @Schema(description = "中文名", example = "属于时代") String cnName,
        @Schema(description = "起点类名", example = "CulturalRelic") String fromClass,
        @Schema(description = "终点类名", example = "Era") String toClass,
        @Schema(description = "关系数量", example = "1192") long count,
        @Schema(description = "占关系总数的百分比（0-100）", example = "22.1") double pctOfTotal) {
}
