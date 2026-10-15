package com.conca.ontology.file.dto.result;
import com.conca.ontology.file.model.FileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 编辑文件说明结果
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "编辑文件说明结果")
public record FileUpdateResult(
        @Schema(description = "更新后的文件条目") FileEntry file) {
}
