package com.conca.ontology.dedup.dto.view;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "疑似重复候选节点：单节点信息 + 各算法得分")
public record DupCandidate(
        @Schema(description = "Neo4j elementId（节点内部唯一 ID，用于跳转与合并）", example = "4:ef0a...") String eid,
        @Schema(description = "节点 label", example = "Era") String label,
        @Schema(description = "Schema 定义的 keyProp 属性名", example = "name") String keyProp,
        @Schema(description = "keyProp 对应的值（展示名）", example = "北宋") String keyValue,
        @Schema(description = "节点全部属性（含 keyProp），用于详情对齐表") Map<String, Object> props,
        @Schema(description = "度中心性（入度+出度），用于 survivor 建议：度高者保留") long totalDegree,
        @Schema(description = "属性个数，用于 survivor 建议：属性多者保留") int propCount,
        @Schema(description = "本节点在组内作为 survivor 保留的推荐优先级（0 最高，越大越不推荐；-1 表示暂未计算）", example = "0") int survivorPriority,
        @Schema(description = "推荐理由（当 survivorPriority=0 时给出）", example = "规范化别名完全匹配，且属性最多") String survivorReason,
        @Schema(description = "本节点各算法得分明细") DedupAlgorithmScore score) {
}
