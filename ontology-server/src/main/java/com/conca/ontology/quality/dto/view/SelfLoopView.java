package com.conca.ontology.quality.dto.view;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "自环关系明细：节点通过某关系指向自身")
public record SelfLoopView(
        @Schema(description = "节点标签（两端相同）", example = "Person") String label,
        @Schema(description = "关系类型", example = "KNOWS") String relType,
        @Schema(description = "该类 keyProp 属性名") String keyProp,
        @Schema(description = "keyProp 对应值") Object keyValue,
        @Schema(description = "节点展示名") String displayName,
        @Schema(description = "Neo4j elementId") String elementId,
        @Schema(description = "关系自身属性（JSON 对象）") Map<String, Object> relProps) {
}
