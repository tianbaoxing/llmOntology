package com.conca.ontology.agent.dto;
import jakarta.validation.constraints.NotBlank;

/**
 * 会话重命名请求（PUT /api/agent/sessions/{id}/title）。
 *
 * @author tianbaoxing
 * @date 2026-09-10
 */
public record SessionTitleDTO(
        @NotBlank(message = "标题不能为空")
        String title) {
}
