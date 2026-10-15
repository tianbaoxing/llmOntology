package com.conca.ontology.dedup.dto.view;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "去重算法得分明细：6 阶段流水线各阶段单独得分 + 综合分 + M1 子策略命中标记")
public record DedupAlgorithmScore(
        @Schema(description = "规范化别名阶段命中：1.0 = 完全匹配；0.0 = 未命中", example = "1.0") double normalizeAlias,
        @Schema(description = "精确 key 归一化（name+era 等组合）命中：1.0 = 匹配任一模式；0.0 = 未命中", example = "0.0") double exactKey,
        @Schema(description = "MinHash LSH Jaccard 近似相似度（0~1，M2 实现，M1 固定 0）", example = "0.0") double minHashLsh,
        @Schema(description = "Jaro-Winkler 编辑相似度（0~1，M2 实现，M1 固定 0）", example = "0.0") double jaroWinkler,
        @Schema(description = "阻塞+SN 命中分（共命中桶数归一化，0~1；M2 实现，M1 固定 0）", example = "0.0") double blocking,
        @Schema(description = "综合加权分（0~1）：≥ 高置信阈值可自动合并", example = "0.85") double composite,
        @Schema(description = "命中阶段名称列表（用于前端展示触发原因）", example = "[\"normalizeAlias\"]") java.util.List<String> hitStages,
        @Schema(description = "M1 子策略命中：规范化别名（subStage=norm）", example = "true") boolean normHit,
        @Schema(description = "M1 子策略命中：精确 key·name+辅助属性（subStage=exact-nameAux）", example = "false") boolean exactNameAuxHit,
        @Schema(description = "命中的子策略位掩码（bit0=norm bit1=exactNameAux），用于 O(1) 判断多层命中", example = "3") int subStageMask) {

    /** 子策略编号常量：与 mask 位对应 */
    public static final int MASK_NORM              = 1 << 0;
    public static final int MASK_EXACT_NAME_AUX    = 1 << 1;

    /** 统一子策略字符串常量（组级 / 节点级 triggeredSubStages 统一用这些值，前端聚合直接匹配） */
    public static final String SUB_NORM               = "norm";
    public static final String SUB_EXACT_NAME_AUX     = "exact-nameAux";

    public static DedupAlgorithmScore exactNormalizeOnly() {
        return new DedupAlgorithmScore(1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
                java.util.List.of("normalizeAlias"),
                true, false,
                MASK_NORM);
    }

    public static DedupAlgorithmScore exactKeyOnly(String subStage) {
        boolean nAux = SUB_EXACT_NAME_AUX.equals(subStage);
        int mask = nAux ? MASK_EXACT_NAME_AUX : 0;
        return new DedupAlgorithmScore(0.0, 1.0, 0.0, 0.0, 0.0, 1.0,
                java.util.List.of("exactKey"),
                false, nAux,
                mask);
    }

    /** 多阶段合并用：把两份得分合并（取每维最大值，子策略命中做 OR） */
    public DedupAlgorithmScore merge(DedupAlgorithmScore other) {
        if (other == null) return this;
        java.util.Set<String> mergedStages = new java.util.LinkedHashSet<>(this.hitStages);
        mergedStages.addAll(other.hitStages);
        int newMask = this.subStageMask | other.subStageMask;
        return new DedupAlgorithmScore(
                Math.max(this.normalizeAlias, other.normalizeAlias),
                Math.max(this.exactKey,       other.exactKey),
                Math.max(this.minHashLsh,     other.minHashLsh),
                Math.max(this.jaroWinkler,    other.jaroWinkler),
                Math.max(this.blocking,       other.blocking),
                Math.max(this.composite,      other.composite),
                java.util.List.copyOf(mergedStages),
                (newMask & MASK_NORM) != 0,
                (newMask & MASK_EXACT_NAME_AUX) != 0,
                newMask);
    }
}
