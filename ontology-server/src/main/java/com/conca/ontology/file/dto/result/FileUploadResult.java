package com.conca.ontology.file.dto.result;
import com.conca.ontology.file.model.FileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 上传文件结果（同名覆盖）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "上传文件结果（同名覆盖）")
public record FileUploadResult(
        @Schema(description = "上传后的文件条目（含绝对路径）") FileEntry file) {
}
