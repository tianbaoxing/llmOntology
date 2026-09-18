package com.conca.ontology.file.dto.result;
import io.swagger.v3.oas.annotations.media.Schema;

/** 删除文件结果（实体 + 元数据记录一并移除） */
@Schema(description = "删除文件结果（实体 + 元数据记录一并移除）")
public record FileDeleteResult(
        @Schema(description = "元数据文件版本号（每次变更 +1）", example = "21") int version,
        @Schema(description = "已删除的文件名", example = "artifact.csv") String deleted) {
}
