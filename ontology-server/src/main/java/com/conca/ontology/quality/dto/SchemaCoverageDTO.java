package com.conca.ontology.quality.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "Schema 覆盖率统计：对比声明 vs 实际有实例的类/关系")
public record SchemaCoverageDTO(
        @Schema(description = "类覆盖率百分比（0-100）= 有实例类数 / 总声明类数 * 100", example = "80.0") double classCoveragePct,
        @Schema(description = "关系覆盖率百分比（0-100）", example = "41.7") double relationCoveragePct,
        @Schema(description = "未使用（零实例）类的数量", example = "2") int unusedClassCount,
        @Schema(description = "未使用（零实例）关系的数量", example = "7") int unusedRelationCount,
        @Schema(description = "未使用类明细") List<UnusedClassDTO> unusedClasses,
        @Schema(description = "未使用关系明细") List<UnusedRelationDTO> unusedRelations) {
}
