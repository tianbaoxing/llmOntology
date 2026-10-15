package com.conca.ontology.dedup.dto.result;

import java.util.List;

import com.conca.ontology.dedup.dto.view.DupCandidateGroup;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Schema(description = "去重扫描结果：KPI 汇总 + 候选组列表")
public record DedupScanResult(
        @Schema(description = "扫描开始时间戳（毫秒，epoch）", example = "1758348120000") long scanStartMs,
        @Schema(description = "扫描结束时间戳（毫秒，epoch）", example = "1758348125320") long scanEndMs,
        @Schema(description = "扫描耗时（毫秒）", example = "5320") long scanDurationMs,
        @Schema(description = "本次扫描的 label；* = 全量逐类扫描", example = "Place") String scannedLabel,
        @Schema(description = "扫描到的节点总数", example = "1428") long scannedNodes,
        @Schema(description = "扫描涉及的 label 数（全量扫描时 ≥ 1）", example = "10") int scannedLabels,
        @Schema(description = "综合分阈值（本次扫描使用）", example = "0.95") double threshold,
        @Schema(description = "候选组总数（含全部级别，无 limit/skip 裁剪）", example = "37") long totalGroups,
        @Schema(description = "高置信可自动合并的组数（autoMergeReady=true + 无冲突）", example = "28") long autoReadyGroups,
        @Schema(description = "需人工确认的组数（conflictLevel=ATTRIBUTE 或 中低置信）", example = "7") long manualConfirmGroups,
        @Schema(description = "冲突阻塞禁止合并的组数（conflictLevel=DISJOINT 或严重属性冲突）", example = "2") long blockedGroups,
        @Schema(description = "合并完成后预计总共可节省节点数（Σ (size-1) of all groups）", example = "45") long estimatedTotalSaved,
        @Schema(description = "候选组列表（按 maxComposite 降序，应用 skip/limit 裁剪）") List<DupCandidateGroup> groups,
        @Schema(description = "是否还有更多组（分页用：skip+limit < totalGroups）", example = "false") boolean hasMore) {
}
