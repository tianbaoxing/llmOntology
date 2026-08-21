package com.conca.ontology.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 问询记录批量保存结果（按 key upsert） */
@Schema(description = "问询记录批量保存结果（按 key upsert：同 key 覆盖）")
public record ProfileSaveResult(
        @Schema(description = "保存后的画像文件版本号（每次保存 +1）", example = "6") int version,
        @Schema(description = "保存后的条目总数（全量，非本次提交数）", example = "12") int count) {
}
