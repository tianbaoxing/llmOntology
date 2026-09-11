package com.conca.ontology.model;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "disjoint-rules.json 文件结构：互斥约束词典的唯一事实源（简化版，扁平规则列表）")
public record DisjointRuleFile(
        @Schema(description = "版本号（每次写入 +1）", example = "1") int version,
        @Schema(description = "互斥约束规则列表") List<DisjointRule> rules) {
}
