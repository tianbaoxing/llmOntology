package com.conca.ontology.dto.view;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单条冲突：哪条规则的哪几个候选项同时被文本命中")
public record DisjointConflictView(
        @Schema(description = "规则 ID", example = "R001") String ruleId,
        @Schema(description = "规则原文", example = "材质互斥：一件器物主体不能同时是【青铜】且【瓷】") String ruleText,
        @Schema(description = "同时命中的候选项（≥2 个）", example = "[\"青铜\",\"瓷\"]") List<String> hitItems) {
}
