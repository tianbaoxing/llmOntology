package com.conca.ontology.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 问答消息请求（会话 id 在路径中：POST /api/agent/sessions/{id}/messages）。
 */
public record AgentChatRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容过长")
        String message) {
}
