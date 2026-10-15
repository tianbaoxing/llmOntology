package com.conca.ontology.graph.dto.view;
import java.util.List;

import com.conca.ontology.graph.model.PropertyDef;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 类（节点类型）视图：定义 + 属性 + 出边声明 + 实时 count
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "类（节点类型）视图：定义 + 属性 + 出边声明 + 实时 count")
public record ClassView(
        @Schema(description = "类名（唯一标识，[A-Za-z_][A-Za-z0-9_]*）", example = "Era") String name,
        @Schema(description = "中文名", example = "年代") String cnName,
        @Schema(description = "概念层归属：concept=概念层，instance=实例层", example = "concept",
                allowableValues = {"concept", "instance"}) String layer,
        @Schema(description = "唯一键属性名（创建后不可变）", example = "name") String keyProp,
        @Schema(description = "语义说明", example = "历史年代/朝代概念，支持细分层级（明永乐→明）") String description,
        @Schema(description = "库中该类节点的实时数量", example = "298") long count,
        @Schema(description = "属性定义列表（含 keyProp）") List<PropertyDef> properties,
        @Schema(description = "出边关系声明列表（入边不入本字段，见类详情接口）") List<RelationDetail> relations) {
}
