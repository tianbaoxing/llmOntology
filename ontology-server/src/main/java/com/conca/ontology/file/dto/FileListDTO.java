package com.conca.ontology.file.dto;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 上传文件全量列表（与磁盘对账后返回）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "上传文件全量列表（与磁盘对账后返回）")
public record FileListDTO(
        @Schema(description = "文件列表（含绝对路径，LLM 按路径用自身工具读取内容）") List<FileEntryDTO> files) {
}
