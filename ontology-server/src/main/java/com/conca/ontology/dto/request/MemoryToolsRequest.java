package com.conca.ontology.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 记忆工具开关切换请求（PUT /api/agent/memory-tools）。
 */
public record MemoryToolsRequest(
        @NotNull(message = "enabled 不能为空")
        Boolean enabled) {
}
