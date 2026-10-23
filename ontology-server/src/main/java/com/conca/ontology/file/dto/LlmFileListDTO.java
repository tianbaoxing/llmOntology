package com.conca.ontology.file.dto;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * LLM 专属文件列表（二进制原件已转为 MD，FAILED 不返回）
 *
 * @author tianbaoxing
 * @date 2026-09-14
 */
@Schema(description = "LLM 专属文件列表：path 恒为可直接读取的 Markdown/文本文件路径")
public record LlmFileListDTO(
        @Schema(description = "文件列表（path 可直接 Read/Grep）") List<LlmFileEntryDTO> files) {
}
