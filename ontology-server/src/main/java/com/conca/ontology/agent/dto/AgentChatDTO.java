package com.conca.ontology.agent.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 问答消息请求（会话 id 在路径中：POST /api/agent/sessions/{id}/messages）。
 *
 * @author tianbaoxing
 * @date 2026-08-20
 */
public record AgentChatDTO(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容过长")
        String message,
        /** 自建智能体 id（可选）：空 = 平台主智能体，非空路由到 AgentRegistry 实例 */
        String agentId) {
}
