package com.conca.ontology.agent.dto;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author tianbaoxing
 * @date 2026-09-11
 */
@Schema(description = "语义改写配置：4 项功能（semantic/typo/term/context）当前值（rewrite_config 表，无全局 version）")
public record RewriteConfig(
        @Schema(description = "功能配置，key 为 semantic/typo/term/context") Map<String, Feature> features) {

    /** 是否有任一功能启用（聊天管道是否需要执行改写轮） */
    public boolean anyEnabled() {
        return features().values().stream().anyMatch(Feature::enabled);
    }

    @Schema(description = "单项功能配置")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feature(
            @Schema(description = "是否启用") boolean enabled,
            @Schema(description = "功能提示词（页面可修改，保存后生效）") String prompt) {
    }
}
