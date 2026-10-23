package com.conca.ontology.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "孤立节点明细行：无任何关系连接的节点")
public record IsolatedNodeDTO(
        @Schema(description = "节点标签（类名）", example = "CulturalRelic") String label,
        @Schema(description = "该类 keyProp 属性名", example = "name") String keyProp,
        @Schema(description = "keyProp 对应值") Object keyValue,
        @Schema(description = "展示名（title/name/cnName 优先，fallback 到 keyValue）", example = "四羊方尊") String displayName,
        @Schema(description = "Neo4j elementId，用于详情跳转") String elementId) {
}
