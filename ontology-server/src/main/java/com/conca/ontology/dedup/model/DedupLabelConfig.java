package com.conca.ontology.dedup.model;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-22
 */
@Schema(description = "单 label 的去重规则配置")
public record DedupLabelConfig(
        @Schema(description = "name 属性源（如 CulturalRelic 用 title，其他一般用 name）", example = "name") String nameProp,
        @Schema(description = "NAME_AUX 模式的辅助属性列表", example = "[\"province\", \"kind\"]") List<String> auxProps,
        @Schema(description = "启用的精确匹配模式列表") List<DedupExactMode> exactModes,
        @Schema(description = "该 label 私有的冲突检测关键属性（与 globalConflictProps 取并集）", example = "[\"site\", \"province\"]") List<String> conflictProps) {

    public static DedupLabelConfig empty(String nameProp) {
        return new DedupLabelConfig(nameProp, List.of(), List.of(), List.of());
    }
}
