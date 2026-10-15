package com.conca.ontology.quality.dto.view;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "悬挂/异常连接明细：终端节点连接（终点无出边）或源节点连接（起点无入边）的关系记录")
public record DanglingEdgeView(
        @Schema(description = "起点节点标签", example = "Person") String fromLabel,
        @Schema(description = "终点节点标签", example = "Organization") String toLabel,
        @Schema(description = "关系类型", example = "WORKS_FOR") String relType,
        @Schema(description = "起点类 keyProp 属性名") String fromKeyProp,
        @Schema(description = "起点 keyProp 对应值") Object fromKeyValue,
        @Schema(description = "起点展示名") String fromDisplayName,
        @Schema(description = "起点 Neo4j elementId") String fromElementId,
        @Schema(description = "终点类 keyProp 属性名") String toKeyProp,
        @Schema(description = "终点 keyProp 对应值") Object toKeyValue,
        @Schema(description = "终点展示名") String toDisplayName,
        @Schema(description = "终点 Neo4j elementId") String toElementId,
        @Schema(description = "关系自身属性（JSON 对象）") Map<String, Object> relProps) {
}
