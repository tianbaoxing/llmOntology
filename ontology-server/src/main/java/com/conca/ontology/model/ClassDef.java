package com.conca.ontology.model;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 类（节点类型）定义。关系声明于发起方（fromClass = 本类）一侧，入边由全量定义推导。 */
@Schema(description = "类（节点类型）定义：属性 + 出边关系声明；关系声明于发起方一侧，入边由全量定义推导")
public record ClassDef(
        @Schema(description = "类名（唯一标识，[A-Za-z_][A-Za-z0-9_]*，创建后不可变）", example = "Era") String name,
        @Schema(description = "中文名（必填）", example = "年代") String cnName,
        @Schema(description = "概念层归属：concept=概念层，instance=实例层", example = "concept",
                allowableValues = {"concept", "instance"}) String layer,
        @Schema(description = "唯一键属性名（必须出现在 properties 中，创建后不可变）", example = "name") String keyProp,
        @Schema(description = "语义说明") String description,
        @Schema(description = "属性定义列表（非空，必须含 keyProp）") List<PropertyDef> properties,
        @Schema(description = "出边关系声明列表（可为空；入边由其他类声明推导）") List<RelationDef> relations) {
}
