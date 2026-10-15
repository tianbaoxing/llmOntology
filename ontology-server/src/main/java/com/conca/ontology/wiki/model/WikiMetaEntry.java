package com.conca.ontology.wiki.model;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wiki 手工元数据条目：data/wiki-metadata.json 的单元，以 mdName 为 key 关联转换产物。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Schema(description = "Wiki 手工元数据条目（分类/标签）")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WikiMetaEntry(
        @Schema(description = "MD 文件名（唯一 key，如 报告.docx.md）", example = "报告.docx.md") String mdName,
        @Schema(description = "分类（可为空）", example = "考古发现") String category,
        @Schema(description = "标签（可为空列表）", example = "[\"陨铁\", \"三星堆\"]") java.util.List<String> tags) {
}
