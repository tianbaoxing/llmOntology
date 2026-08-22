package com.conca.ontology.dto.result;

import com.conca.ontology.model.FileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上传文件结果（同名覆盖） */
@Schema(description = "上传文件结果（同名覆盖）")
public record FileUploadResult(
        @Schema(description = "元数据文件版本号（每次变更 +1）", example = "19") int version,
        @Schema(description = "上传后的文件条目（含绝对路径）") FileEntry file) {
}
