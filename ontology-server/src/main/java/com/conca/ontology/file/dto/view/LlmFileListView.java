package com.conca.ontology.file.dto.view;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** LLM 专属文件列表（二进制原件已转为 MD，FAILED 不返回） */
@Schema(description = "LLM 专属文件列表：path 恒为可直接读取的 Markdown/文本文件路径")
public record LlmFileListView(
        @Schema(description = "元数据文件版本号（每次变更 +1）", example = "18") int version,
        @Schema(description = "文件列表（path 可直接 Read/Grep）") List<LlmFileEntryView> files) {
}
