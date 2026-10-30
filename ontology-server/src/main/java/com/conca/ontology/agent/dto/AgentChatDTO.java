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
        String agentId,
        /** OpenRouter 模型 slug（可选，chat 页底部模型切换）：空或等于默认模型 = 主智能体单例；
         *  仅主智能体生效（自建智能体忽略），服务端经 ModelSlugService 校验后才装配模型级实例 */
        @Size(max = 160, message = "模型标识过长")
        String model) {
}
