package com.conca.ontology.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 邻居分组统计：按 方向×关系类型×对端标签 聚合的边数（不分页，全量） */
@Schema(description = "邻居分组统计：按 方向×关系类型×对端标签 聚合的边数")
public record NeighborGroupsView(
        @Schema(description = "分组列表（按边数降序）") List<NeighborGroup> groups) {

    @Schema(description = "单个分组统计项")
    public record NeighborGroup(
            @Schema(description = "方向相对起点：out=出边，in=入边", example = "in",
                    allowableValues = {"in", "out"}) String dir,
            @Schema(description = "关系类型", example = "OF_ERA") String relType,
            @Schema(description = "对端节点标签", example = "CulturalRelic") String otherLabel,
            @Schema(description = "该分组的边数", example = "182") long count) {
    }
}
