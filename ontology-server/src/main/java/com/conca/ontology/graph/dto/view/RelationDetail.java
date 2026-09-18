package com.conca.ontology.graph.dto.view;
import java.util.List;

import com.conca.ontology.graph.model.PropertyDef;

import io.swagger.v3.oas.annotations.media.Schema;

/** 类详情视图内的出边关系声明（宿主类视角） */
@Schema(description = "出边关系声明（宿主类视角，含实时实例数）")
public record RelationDetail(
        @Schema(description = "关系类型名", example = "SUB_ERA_OF") String name,
        @Schema(description = "中文名", example = "细分属于") String cnName,
        @Schema(description = "目标类", example = "Era") String toClass,
        @Schema(description = "语义说明", example = "细分朝代归属主代") String description,
        @Schema(description = "示例（from -> to）", example = "北宋->宋") String example,
        @Schema(description = "关系自身属性定义（可为空数组）") List<PropertyDef> props,
        @Schema(description = "库中该类型关系的实时数量", example = "214") long count) {
}
