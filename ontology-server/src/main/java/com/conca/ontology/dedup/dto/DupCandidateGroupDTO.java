package com.conca.ontology.dedup.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "疑似重复候选组：≥2 个节点判定为同一实体")
public record DupCandidateGroupDTO(
        @Schema(description = "组 ID（稳定 = 成员 eid 字典序拼接的短 hash，便于前端记忆选中态）", example = "G-0ab12ef4") String groupId,
        @Schema(description = "组内节点数（≥2）", example = "2") int size,
        @Schema(description = "组内节点共同的 label；跨 label 重复暂不支持（M1 按 label 分组扫描）", example = "Era") String label,
        @Schema(description = "组内综合分最低值（取保守值，代表整组重复置信下限）", example = "1.0") double minComposite,
        @Schema(description = "组内综合分最高值（取乐观值）", example = "1.0") double maxComposite,
        @Schema(description = "冲突级别：NONE=无冲突可自动合并；ATTRIBUTE=属性冲突需人工；DISJOINT=disjoint 冲突禁止合并", example = "NONE") ConflictLevel conflictLevel,
        @Schema(description = "冲突描述（conflictLevel != NONE 时可读说明）", example = "年代属性冲突：商代 vs 西周") String conflictReason,
        @Schema(description = "是否高置信可自动合并（综合分 ≥ 高置信阈值 + 无冲突）", example = "true") boolean autoMergeReady,
        @Schema(description = "触发阶段名称列表（用于前端展示命中路径）", example = "[\"normalizeAlias\"]") List<String> hitStages,
        @Schema(description = "候选节点明细（按 survivorPriority 升序：0=推荐保留者在前）") List<DupCandidateDTO> candidates,
        @Schema(description = "合并后预计可节省节点数 = size - 1", example = "1") int estimatedSaved,
        @Schema(description = "M1 组级：所有节点命中的子策略并集（值来自 DedupAlgorithmScoreDTO.SUB_* 常量）。顺序：norm → exact-nameAux", example = "[\"norm\",\"exact-nameAux\"]") List<String> triggeredSubStages,
        @Schema(description = "M1 组级：triggeredSubStages 的长度（≥2 表示该组被多子策略共同命中，即「多层命中」最可靠组）", example = "2") int multiHitCount) {

    public enum ConflictLevel {
        NONE, ATTRIBUTE, DISJOINT
    }
}
