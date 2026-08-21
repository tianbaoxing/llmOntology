package com.conca.ontology.dto;

import java.util.List;

import com.conca.ontology.model.PropertyDef;

import io.swagger.v3.oas.annotations.media.Schema;

/** 类（节点类型）详情视图：ClassView 全字段 + 推导入边 */
@Schema(description = "类详情视图：类定义全字段 + 入边关系（推导）")
public record ClassDetailView(
        @Schema(description = "类名（唯一标识）", example = "Era") String name,
        @Schema(description = "中文名", example = "年代") String cnName,
        @Schema(description = "概念层归属：concept=概念层，instance=实例层", example = "concept",
                allowableValues = {"concept", "instance"}) String layer,
        @Schema(description = "唯一键属性名（创建后不可变）", example = "name") String keyProp,
        @Schema(description = "语义说明") String description,
        @Schema(description = "库中该类节点的实时数量", example = "298") long count,
        @Schema(description = "属性定义列表（含 keyProp）") List<PropertyDef> properties,
        @Schema(description = "出边关系声明列表") List<RelationDetail> relations,
        @Schema(description = "入边关系列表（由其他类声明推导）") List<IncomingRelation> incomingRelations) {
}
