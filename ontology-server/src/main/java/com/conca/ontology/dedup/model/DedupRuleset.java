package com.conca.ontology.dedup.model;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-22
 */
@Schema(description = "一套完整的去重规则集（单 JSON 文件对应一个 DedupRuleset）")
public record DedupRuleset(
        @Schema(description = "规则集唯一 ID（用户创建，UUID 或自定义标识）", example = "default") String id,
        @Schema(description = "规则集展示名", example = "默认规则") String name,
        @Schema(description = "版本号（每次写入 +1，乐观锁用）", example = "1") int version,
        @Schema(description = "最后更新时间（ms 时间戳）", example = "1758355200000") long updatedAt,
        @Schema(description = "Normalize 后缀表：Map<label, 后缀数组>") Map<String, List<String>> suffixTable,
        @Schema(description = "各 label 的配置：Map<label, DedupLabelConfig>") Map<String, DedupLabelConfig> labelConfig,
        @Schema(description = "所有 label 通用的冲突检测关键属性", example = "[\"era\", \"site\"]") List<String> globalConflictProps) {
}
