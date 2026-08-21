package com.conca.ontology.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 类摘要（列表项）：不含属性与关系明细，只有计数 */
@Schema(description = "类摘要（列表项）：只有计数，不含属性与关系明细")
public record ClassSummary(
        @Schema(description = "类名（唯一标识）", example = "Era") String name,
        @Schema(description = "中文名", example = "年代") String cnName,
        @Schema(description = "概念层归属：concept=概念层，instance=实例层", example = "concept",
                allowableValues = {"concept", "instance"}) String layer,
        @Schema(description = "唯一键属性名", example = "name") String keyProp,
        @Schema(description = "语义说明") String description,
        @Schema(description = "库中该类节点的实时数量", example = "298") long count,
        @Schema(description = "属性定义个数（含 keyProp）", example = "1") int propertyCount,
        @Schema(description = "出边关系声明个数", example = "1") int relationCount) {
}
