package com.conca.ontology.dedup.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "去重扫描请求：指定 label、算法阈值、返回限制")
public record DedupScanRequest(
        @Schema(description = "要扫描的节点 label；留空或 \"*\" 表示扫描全部 label（按 label 分别扫描，不跨类合并）", example = "Place", defaultValue = "*") String label,
        @Schema(description = "综合分阈值（0~1），≥ 此值才纳入候选组；M1 两阶段都是精确匹配，建议默认 0.95 兜底", example = "0.95", defaultValue = "0.95") Double threshold,
        @Schema(description = "最多返回多少个候选组（用于分页/限流；0 或空 = 不限制）", example = "500", defaultValue = "0") Integer limitGroups,
        @Schema(description = "跳过前 N 组（分页用；与 limitGroups 配合）", example = "0", defaultValue = "0") Integer skipGroups) {

    public String labelSafe() {
        return label == null || label.isBlank() || "*".equals(label) ? "*" : label.trim();
    }

    public double thresholdSafe() {
        return threshold == null ? 0.95 : Math.max(0.0, Math.min(1.0, threshold));
    }

    public int limitGroupsSafe() {
        return limitGroups == null ? 0 : Math.max(0, limitGroups);
    }

    public int skipGroupsSafe() {
        return skipGroups == null ? 0 : Math.max(0, skipGroups);
    }
}
