package com.conca.ontology.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 问答请求：sessionId 为空时服务端生成新会话；message 为用户输入。
 */
public record AgentChatRequest(
        String sessionId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容过长")
        String message) {
}
