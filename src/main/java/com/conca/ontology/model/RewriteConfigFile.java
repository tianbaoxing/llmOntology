package com.conca.ontology.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "rewrite-config.json 文件结构：语义改写 4 项功能（semantic/typo/term/context）配置")
public record RewriteConfigFile(
        @Schema(description = "版本号（每次写入 +1）", example = "1") int version,
        @Schema(description = "功能配置，key 为 semantic/typo/term/context") Map<String, Feature> features) {

    @Schema(description = "单项功能配置")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feature(
            @Schema(description = "是否启用") boolean enabled,
            @Schema(description = "功能提示词（页面可修改，保存后生效）") String prompt) {
    }
}
