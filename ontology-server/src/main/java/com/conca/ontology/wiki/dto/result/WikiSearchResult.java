package com.conca.ontology.wiki.dto.result;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Wiki 全文搜索结果：命中文档按加权分降序（标题 ×40 > 元信息 ×20 > 正文单次 ×1，同分按命中次数）。 */
@Schema(description = "Wiki 全文搜索结果")
public record WikiSearchResult(
        @Schema(description = "回显关键词") String q,
        @Schema(description = "命中文档列表，最多 50 篇") List<Doc> docs) {

    /** 单篇命中：WikiDocView 关键字段 + 命中统计与片段 */
    @Schema(description = "单篇命中")
    public record Doc(
            @Schema(description = "MD 文件名（打开文档的 key）", example = "报告.docx.md") String mdName,
            @Schema(description = "来源上传文件名", example = "报告.docx") String sourceName,
            @Schema(description = "标题") String title,
            @Schema(description = "发布时间（可空）", example = "2026-05-11") String publishDate,
            @Schema(description = "来源（可空）", example = "新华网") String source,
            @Schema(description = "分类（手工维护，可空）") String category,
            @Schema(description = "标签（手工维护，可为空列表）") List<String> tags,
            @Schema(description = "该文档命中总次数") long hits,
            @Schema(description = "命中片段（每篇最多 3 个，命中点前后约 40 字符，原文截取）") List<Snippet> snippets) {
    }

    @Schema(description = "命中片段")
    public record Snippet(@Schema(description = "片段原文") String text) {
    }
}
