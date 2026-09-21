package com.conca.ontology.quality.dto.view;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "未使用的 Schema 关系定义：声明了但零实例")
public record UnusedRelationView(
        @Schema(description = "关系类型名", example = "HAS_INSCRIPTION_STYLE") String relType,
        @Schema(description = "中文名", example = "有铭文风格") String cnName,
        @Schema(description = "起点类名", example = "CulturalRelic") String fromClass,
        @Schema(description = "终点类名", example = "InscriptionStyle") String toClass,
        @Schema(description = "语义说明") String description,
        @Schema(description = "示例（from -> to）", example = "四羊方尊 -> 鸟篆") String example) {
}
