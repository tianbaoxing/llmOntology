package com.conca.ontology.file.dto.view;
import io.swagger.v3.oas.annotations.media.Schema;

/** LLM 专属文件条目：path 为"应读路径"（二进制 → MD 绝对路径；文本 → 原路径） */
@Schema(description = "LLM 专属文件条目：path 恒为可直接读取的 Markdown/文本文件路径")
public record LlmFileEntryView(
        @Schema(description = "文件名（二进制文件为其 MD 名，如 报告.docx.md）",
                example = "报告.docx.md") String name,
        @Schema(description = "应读绝对路径（Markdown/文本，LLM 直接用文件读取工具打开）",
                example = "C:\\me\\kaiyuan\\history\\llmOntology\\files-md\\报告.docx.md") String path,
        @Schema(description = "原始文件名（仅二进制转换文件有值，用于溯源）",
                example = "报告.docx") String originName,
        @Schema(description = "原文件大小（字节）", example = "1048576") long size,
        @Schema(description = "原文件扩展名（小写）", example = "docx") String ext,
        @Schema(description = "文件说明（可为空字符串）", example = "国博年度报告") String description,
        @Schema(description = "上传时间", example = "2026-09-14 10:00:00") String uploadedAt) {
}
