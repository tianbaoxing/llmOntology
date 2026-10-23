package com.conca.ontology.graph.dto;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 分页实例列表：某 label 下按唯一键排序的节点全属性分页
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "分页实例列表：某 label 下按唯一键排序的节点全属性分页")
public record NodesPageDTO(
        @Schema(description = "节点标签（须在 schema 白名单内）", example = "Era") String label,
        @Schema(description = "该类唯一键属性名（服务端按 schema 决定）", example = "name") String keyProp,
        @Schema(description = "该 label 的节点总数", example = "298") long total,
        @Schema(description = "当前页码（从 1 起）", example = "1") int page,
        @Schema(description = "页大小（1..200，实际生效值）", example = "50") int size,
        @Schema(description = "总页数 = ceil(total / size)", example = "6") long pages,
        @Schema(description = "当前页节点列表（含全属性）") List<NodeRow> rows) {

    @Schema(description = "单个节点行")
    public record NodeRow(
            @Schema(description = "该 label 的节点总数（每行冗余返回，便于单行解析）", example = "298") long total,
            @Schema(description = "唯一键值（类型由 schema 的 keyProp 属性决定，通常为字符串）") Object key,
            @Schema(description = "节点全属性（键值对，动态结构）") Map<String, Object> props) {
    }
}
