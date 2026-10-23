package com.conca.ontology.wiki.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 编辑 Wiki 元信息结果：返回更新后的文档视图。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Schema(description = "编辑 Wiki 元信息结果")
public record WikiMetaUpdateResultDTO(
        @Schema(description = "元数据版本", example = "4") int version,
        @Schema(description = "更新后的文档视图") WikiDocDTO doc) {
}
