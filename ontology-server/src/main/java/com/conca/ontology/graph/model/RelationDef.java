package com.conca.ontology.graph.model;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 关系定义：声明在 fromClass（发起方）一侧，toClass 为目标类；props 为关系自身属性 */
@Schema(description = "关系定义：声明在发起方（fromClass）一侧，toClass 为目标类")
public record RelationDef(
        @Schema(description = "关系类型名（全 schema 内唯一）", example = "SUB_ERA_OF") String name,
        @Schema(description = "中文名", example = "细分属于") String cnName,
        @Schema(description = "目标类名（必须已存在；自引用可写本类名）", example = "Era") String toClass,
        @Schema(description = "语义说明", example = "细分朝代归属主代") String description,
        @Schema(description = "示例（from -> to）", example = "北宋->宋") String example,
        @Schema(description = "关系自身属性定义（可为空数组）") List<PropertyDef> props) {
}
