package com.conca.ontology.disjoint.dto;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 互斥约束词典全量视图（每条规则一行，无全局 version）
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@Schema(description = "互斥约束词典全量视图（每条规则一行，无全局 version）")
public record DisjointRulesDTO(
        @Schema(description = "互斥约束规则列表（按规则 ID 字典序）") List<DisjointRule> rules) {
}
