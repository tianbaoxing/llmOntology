package com.conca.ontology.wiki.dto;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wiki 文档列表项：自动抽取元信息 + 手工维护分类/标签 合并后的视图。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Schema(description = "Wiki 文档列表项（元信息合并视图）")
public record WikiDocDTO(
        @Schema(description = "MD 文件名（打开文档的 key）", example = "报告.docx.md") String mdName,
        @Schema(description = "来源上传文件名", example = "报告.docx") String sourceName,
        @Schema(description = "标题（MD 内标题/加粗行，兜底文件名）") String title,
        @Schema(description = "发布时间（MD 正文抽取，可空）", example = "2026-05-11") String publishDate,
        @Schema(description = "来源（MD 正文抽取，可空）", example = "新华网") String source,
        @Schema(description = "作者（MD 正文抽取，可空）") String author,
        @Schema(description = "分类（手工维护，可空）") String category,
        @Schema(description = "标签（手工维护，可为空列表）") List<String> tags,
        @Schema(description = "文件说明（上传时填写）") String description,
        @Schema(description = "转换完成时间", example = "2026-09-14 17:21:58") String convertedAt,
        @Schema(description = "MD 大小（字节）", example = "2232") long size) {
}
