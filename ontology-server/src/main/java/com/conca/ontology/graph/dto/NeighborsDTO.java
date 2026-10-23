package com.conca.ontology.graph.dto;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 邻域查询结果：起点节点 + 去重边分页（按 eid 排序）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "邻域查询结果：起点节点 + 去重边分页")
public record NeighborsDTO(
        @Schema(description = "起点节点（eid / labels / props）") NodeRef start,
        @Schema(description = "当前页边列表（按 fromEid,relType,toEid 排序去重）") List<Edge> edges,
        @Schema(description = "去重后的边总数（全量，用于分页）", example = "182") long total,
        @Schema(description = "当前页起始偏移（作用于边）", example = "0") int skip,
        @Schema(description = "当前页大小（1..500，实际生效值）", example = "100") int limit) {

    @Schema(description = "节点引用（邻域起点）")
    public record NodeRef(
            @Schema(description = "Neo4j 内部元素 id（elementId，非业务键）") String eid,
            @Schema(description = "节点标签列表（单标签场景长度为 1）") List<String> labels,
            @Schema(description = "节点全属性（键值对，动态结构）") Map<String, Object> props) {
    }

    @Schema(description = "一条边（两端节点 + 关系类型与属性）")
    public record Edge(
            @Schema(description = "起点节点的 Neo4j elementId") String fromEid,
            @Schema(description = "起点节点标签列表") List<String> fromLabels,
            @Schema(description = "起点节点全属性") Map<String, Object> fromProps,
            @Schema(description = "终点节点的 Neo4j elementId") String toEid,
            @Schema(description = "终点节点标签列表") List<String> toLabels,
            @Schema(description = "终点节点全属性") Map<String, Object> toProps,
            @Schema(description = "关系类型（如 OF_ERA）", example = "OF_ERA") String relType,
            @Schema(description = "关系自身属性（无属性时为空对象）") Map<String, Object> relProps) {
    }
}
