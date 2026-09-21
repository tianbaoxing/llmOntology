package com.conca.ontology.quality.dto.view;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "拓扑异常诊断模块：孤立点、自环、悬挂边、出入度异常")
public record TopologyAnomalyView(
        @Schema(description = "孤立节点总数（无任何关系连接）", example = "23") long isolatedCount,
        @Schema(description = "自环关系总数（节点指向自身的关系）", example = "2") long selfLoopCount,
        @Schema(description = "悬挂边/异常连接数（一端零出/入度，理论 0）", example = "0") long danglingCount,
        @Schema(description = "入度异常节点数（度值 > 均值+3σ）", example = "7") long inDegreeAnomalyCount,
        @Schema(description = "出度异常节点数（度值 > 均值+3σ）", example = "5") long outDegreeAnomalyCount,
        @Schema(description = "孤立节点明细（默认 LIMIT 500，按 label 升序）") List<IsolatedNodeView> isolatedTop,
        @Schema(description = "自环关系明细（全量）") List<SelfLoopView> selfLoops,
        @Schema(description = "入度异常 Top-K（默认 K=20）") List<DegreeAnomalyView> inDegreeTop,
        @Schema(description = "出度异常 Top-K（默认 K=20）") List<DegreeAnomalyView> outDegreeTop) {
}
