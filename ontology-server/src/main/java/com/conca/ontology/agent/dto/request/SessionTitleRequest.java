package com.conca.ontology.agent.dto.request;
import jakarta.validation.constraints.NotBlank;

/**
 * 会话重命名请求（PUT /api/agent/sessions/{id}/title）。
 */
public record SessionTitleRequest(
        @NotBlank(message = "标题不能为空")
        String title) {
}
