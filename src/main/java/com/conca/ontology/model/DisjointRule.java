package com.conca.ontology.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单条互斥约束规则：用户自由文本，约定【】包裹候选项；命中同一条内 ≥2 个候选项即触发冲突")
public record DisjointRule(
        @Schema(description = "规则ID（唯一，R 前缀递增，如 R001）", example = "R001") String id,
        @Schema(description = "规则原文（自由文本；候选项用【】包裹，可含多个。例：材质互斥：一件器物主体不能同时是【青铜】且【瓷】）",
                example = "材质互斥：一件器物主体不能同时是【青铜】且【瓷】")
        String text) {
}
