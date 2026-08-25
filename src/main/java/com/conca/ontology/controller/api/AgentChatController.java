package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.conca.ontology.dto.request.AgentChatRequest;
import com.conca.ontology.dto.request.MemoryToolsRequest;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.AgentChatService;
import com.conca.ontology.service.AgentMemoryToolsToggle;
import com.conca.ontology.service.AgentSessionService;

import jakarta.validation.Valid;

/**
 * AI 问答入口（SSE 流式）+ 历史会话管理：
 * POST /api/agent/sessions/{id}/messages {message} → text/event-stream（在会话下创建消息）。
 * 事件序列：session → round ×N（每轮模型调用 start/end，end 携带该轮 tokens）
 * → thinking / tool / answer → done 或 error。
 * GET  /api/agent/sessions                 → 会话列表（updateTime 倒序）
 * GET  /api/agent/sessions/{id}/messages   → 单会话消息
 * DELETE /api/agent/sessions/{id}          → 删除会话（含 AI 记忆）
 * GET  /api/agent/skills                   → 技能清单（chat 页输入区快捷插入）
 * GET  /api/agent/memory-tools             → 记忆工具开关状态（activeTools 与开关值对账）
 * PUT  /api/agent/memory-tools             → 切换 memory_search/get/save 对模型可见性
 */
@RestController
@RequestMapping("/api/agent")
@Validated
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentSessionService agentSessionService;
    private final AgentMemoryToolsToggle memoryToolsToggle;

    public AgentChatController(AgentChatService agentChatService,
            AgentSessionService agentSessionService,
            AgentMemoryToolsToggle memoryToolsToggle) {
        this.agentChatService = agentChatService;
        this.agentSessionService = agentSessionService;
        this.memoryToolsToggle = memoryToolsToggle;
    }

    /** 技能清单；agent 未装配（未配置 key）时 agentAvailable=false、skills 为空列表 */
    @GetMapping("/skills")
    public ApiResponse<Map<String, Object>> skills() {
        return ApiResponse.ok(Map.of(
                "agentAvailable", agentChatService.isAvailable(),
                "skills", agentChatService.listSkills()));
    }

    /** 记忆工具开关状态；activeTools 为 Toolkit 实际存活集（与开关值对账，前端以它为准） */
    @GetMapping("/memory-tools")
    public ApiResponse<Map<String, Object>> memoryTools() {
        return ApiResponse.ok(memoryToolsToggle.status());
    }

    /** 切换记忆工具可见性；影响下一次模型请求的工具 schema（进行中的流式轮次不受控） */
    @PutMapping("/memory-tools")
    public ApiResponse<Map<String, Object>> updateMemoryTools(@Valid @RequestBody MemoryToolsRequest request) {
        return ApiResponse.ok(memoryToolsToggle.setEnabled(request.enabled()));
    }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String id, @Valid @RequestBody AgentChatRequest request) {
        return agentChatService.chat(id, request.message());
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions() {
        return ApiResponse.ok(agentSessionService.list());
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable String id) {
        return ApiResponse.ok(agentSessionService.messages(id));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        return ApiResponse.ok(agentSessionService.delete(id));
    }
}
