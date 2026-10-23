package com.conca.ontology.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 删除文件结果（实体 + 元数据记录一并移除）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "删除文件结果（实体 + 元数据记录一并移除）")
public record FileDeleteResultDTO(
        @Schema(description = "已删除的文件名", example = "artifact.csv") String deleted) {
}
