package com.conca.ontology.file.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;

/** 编辑文件说明请求体 */
@Schema(description = "编辑文件说明请求体")
public record FileDescRequest(
        @Schema(description = "新的文件说明（覆盖旧值）", example = "中国国家博物馆藏品信息") String description) {
}
