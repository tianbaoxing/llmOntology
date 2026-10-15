package com.conca.ontology.dedup.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-22
 */
@Schema(description = "规则集索引条目（轻量：列表下拉使用）")
public record DedupRulesIndexItem(
        @Schema(description = "规则集 ID", example = "default") String id,
        @Schema(description = "规则集名称", example = "默认规则") String name,
        @Schema(description = "版本号", example = "1") int version,
        @Schema(description = "最后更新时间（ms）", example = "1758355200000") long updatedAt,
        @Schema(description = "是否当前激活（data.html 下拉默认值）") boolean active) {
}
