package com.conca.ontology.graph.dto;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量写入关系结果（MATCH 两端 + MERGE 幂等）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "批量写入关系结果（MATCH 两端 + MERGE 幂等）")
public record BatchRelsResultDTO(
        @Schema(description = "关系类型", example = "OF_ERA") String type,
        @Schema(description = "实际写入条数（含新建与覆盖）", example = "100") int written) {
}
