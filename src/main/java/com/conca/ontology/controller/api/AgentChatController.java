package com.conca.ontology.controller.api;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.conca.ontology.dto.AgentChatRequest;
import com.conca.ontology.service.AgentChatService;

import jakarta.validation.Valid;

/**
 * AI 问答入口（SSE 流式）：
 * POST /api/agent/chat {sessionId?, message} → text/event-stream。
 * 事件序列：session → thinking / tool → answer → done 或 error。
 */
@RestController
@RequestMapping("/api/agent")
@Validated
public class AgentChatController {

    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AgentChatRequest request) {
        return agentChatService.chat(request.sessionId(), request.message());
    }
}
