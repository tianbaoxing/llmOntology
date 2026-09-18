package com.conca.ontology.graph.dto.view;
import io.swagger.v3.oas.annotations.media.Schema;

/** 概念层（schema）视图项：关系定义（扁平，含两端类与实时 count） */
@Schema(description = "关系定义视图：含两端类、属性定义与实时实例数")
public record RelationView(
        @Schema(description = "关系类型名（唯一，全 schema 内不可重复声明）", example = "OF_ERA") String name,
        @Schema(description = "中文名", example = "属于年代") String cnName,
        @Schema(description = "发起方类（关系声明的宿主类）", example = "CulturalRelic") String fromClass,
        @Schema(description = "目标类", example = "Era") String toClass,
        @Schema(description = "语义说明") String description,
        @Schema(description = "示例（from -> to）", example = "定窑白釉莲纹大碗->北宋") String example,
        @Schema(description = "关系自身属性定义（可为空数组）") java.util.List<com.conca.ontology.graph.model.PropertyDef> props,
        @Schema(description = "库中该类型关系的实时数量", example = "5016") long count) {
}
