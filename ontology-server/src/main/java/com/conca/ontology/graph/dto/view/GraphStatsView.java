package com.conca.ontology.graph.dto.view;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 图谱统计：各 label 节点数 / 各 type 关系数
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "图谱统计：各 label 节点数 / 各 type 关系数")
public record GraphStatsView(
        @Schema(description = "各节点标签的数量列表（按 label 字典序）") List<LabelCount> nodes,
        @Schema(description = "各关系类型的数量列表（按 type 字典序）") List<TypeCount> relationships) {

    @Schema(description = "节点标签统计项")
    public record LabelCount(
            @Schema(description = "节点标签名（如 Era / CulturalRelic）", example = "Era") String label,
            @Schema(description = "该标签的节点总数", example = "298") long count) {
    }

    @Schema(description = "关系类型统计项")
    public record TypeCount(
            @Schema(description = "关系类型名（如 OF_ERA）", example = "OF_ERA") String type,
            @Schema(description = "该类型的的关系总数", example = "5016") long count) {
    }
}
