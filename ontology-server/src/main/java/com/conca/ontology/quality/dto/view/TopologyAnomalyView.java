package com.conca.ontology.quality.dto.view;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "拓扑异常诊断模块：孤立点、自环、悬挂边（终端/源拆分）、出入度异常")
public record TopologyAnomalyView(
        @Schema(description = "孤立节点总数（无任何关系连接）", example = "23") long isolatedCount,
        @Schema(description = "自环关系总数（节点指向自身的关系）", example = "2") long selfLoopCount,
        @Schema(description = "悬挂边/异常连接总数（终端+源的并集，兼容旧字段）", example = "23048") long danglingCount,
        @Schema(description = "入度异常节点数（度值 > 均值+3σ）", example = "7") long inDegreeAnomalyCount,
        @Schema(description = "出度异常节点数（度值 > 均值+3σ）", example = "5") long outDegreeAnomalyCount,
        @Schema(description = "终端节点连接数：关系终点无任何出边的关系数", example = "15000") long danglingNoOutCount,
        @Schema(description = "源节点连接数：关系起点无任何入边的关系数", example = "12000") long danglingNoInCount,
        @Schema(description = "孤立节点明细（默认 LIMIT 500，按 label 升序）") List<IsolatedNodeView> isolatedTop,
        @Schema(description = "自环关系明细（全量）") List<SelfLoopView> selfLoops,
        @Schema(description = "终端节点连接明细（终点无出边，默认 LIMIT 10）") List<DanglingEdgeView> danglingNoOutTop,
        @Schema(description = "源节点连接明细（起点无入边，默认 LIMIT 10）") List<DanglingEdgeView> danglingNoInTop,
        @Schema(description = "入度异常 Top-K（默认 K=20）") List<DegreeAnomalyView> inDegreeTop,
        @Schema(description = "出度异常 Top-K（默认 K=20）") List<DegreeAnomalyView> outDegreeTop) {
}
