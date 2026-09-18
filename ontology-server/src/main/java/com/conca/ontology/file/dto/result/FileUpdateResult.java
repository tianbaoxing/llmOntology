package com.conca.ontology.file.dto.result;
import com.conca.ontology.file.model.FileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/** 编辑文件说明结果 */
@Schema(description = "编辑文件说明结果")
public record FileUpdateResult(
        @Schema(description = "元数据文件版本号（每次变更 +1）", example = "20") int version,
        @Schema(description = "更新后的文件条目") FileEntry file) {
}
