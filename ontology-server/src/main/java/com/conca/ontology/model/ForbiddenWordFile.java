package com.conca.ontology.model;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "forbidden-words.json 文件结构：禁用词列表（简化版，扁平字符串列表）")
public record ForbiddenWordFile(
        @Schema(description = "版本号（每次写入 +1）", example = "1") int version,
        @Schema(description = "禁用词字符串列表") List<String> words) {
}
