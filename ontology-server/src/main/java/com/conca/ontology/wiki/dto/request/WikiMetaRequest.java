package com.conca.ontology.wiki.dto.request;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 编辑 Wiki 元信息入参：category 置空、tags 置空数组即清除。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Schema(description = "编辑 Wiki 元信息（分类/标签）入参")
public record WikiMetaRequest(
        @Schema(description = "分类（空=清除）", example = "考古发现")
        @Size(max = 50, message = "分类不能超过 50 字") String category,
        @Schema(description = "标签列表（空=清除）", example = "[\"陨铁\", \"三星堆\"]")
        @Size(max = 20, message = "标签不能超过 20 个") List<
                @Size(max = 30, message = "单个标签不能超过 30 字") String> tags) {
}
