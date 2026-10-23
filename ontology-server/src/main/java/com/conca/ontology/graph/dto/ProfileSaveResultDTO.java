package com.conca.ontology.graph.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 问询记录批量保存结果（按 key upsert）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "问询记录批量保存结果（按 key upsert：同 key 覆盖）")
public record ProfileSaveResultDTO(
        @Schema(description = "保存后的条目总数（全量，非本次提交数）", example = "12") int count) {
}
