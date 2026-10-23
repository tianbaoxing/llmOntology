package com.conca.ontology.wiki.dto;
import java.util.List;


import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wiki 文档读取结果：合并元信息 + MD 原文（前端渲染）+ 正文命中的图谱实体（前端包裹链接）。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Schema(description = "Wiki 单篇文档（元信息 + MD 原文 + 命中实体）")
public record WikiDocResultDTO(
        @Schema(description = "MD 文件名", example = "报告.docx.md") String mdName,
        @Schema(description = "来源上传文件名", example = "报告.docx") String sourceName,
        @Schema(description = "标题") String title,
        @Schema(description = "发布时间（可空）", example = "2026-05-11") String publishDate,
        @Schema(description = "来源（可空）", example = "新华网") String source,
        @Schema(description = "作者（可空）") String author,
        @Schema(description = "分类（手工维护，可空）") String category,
        @Schema(description = "标签（手工维护，可为空列表）") List<String> tags,
        @Schema(description = "转换完成时间", example = "2026-09-14 17:21:58") String convertedAt,
        @Schema(description = "MD 原文（marked + DOMPurify 前端渲染）") String content,
        @Schema(description = "正文实际命中的图谱实体（概念层 + 实例层，后端已筛；"
                + "前端渲染后包裹链接跳 /instances）") List<WikiEntityDTO> entities) {
}
