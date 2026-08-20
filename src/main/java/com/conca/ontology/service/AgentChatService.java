package com.conca.ontology.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.conca.ontology.config.AgentScopeConfig;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.Disposable;

/**
 * AI 问答服务：把 HarnessAgent 的 AgentEvent 流映射为 SSE 事件推给页面。
 * 事件契约（前端 chat.html 同步实现）：
 * session → 会话号；thinking → 思考增量；tool → 工具执行；
 * answer → 正文增量；done → 结束；error → 错误。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final long HEARTBEAT_SECONDS = 15;

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    public AgentChatService(ObjectProvider<HarnessAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    /** agent 是否已装配（OPENROUTER_API_KEY 已配置） */
    public boolean isAvailable() {
        return agentProvider.getIfAvailable() != null;
    }

    /** 对外提示未配置 key 时的错误文案 */
    public static String missingKeyMessage() {
        return "未配置 OPENROUTER_API_KEY：请在项目根目录 .env 中填写后重启服务（模板见 .env.example）。";
    }

    /**
     * 流式问答：订阅 streamEvents 并逐事件转发到 SseEmitter。
     */
    public SseEmitter chat(String sessionId, String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String session = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;

        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            send(emitter, "error", Map.of("message", missingKeyMessage()));
            emitter.complete();
            return emitter;
        }
        if (!runningSessions.add(session)) {
            send(emitter, "error", Map.of("message", "上一条消息还在处理中，请稍候。"));
            emitter.complete();
            return emitter;
        }

        send(emitter, "session", Map.of("sessionId", session));

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ignored) {
                // 连接已断开，忽略
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        emitter.onTimeout(emitter::complete);
        emitter.onCompletion(() -> {
            runningSessions.remove(session);
            heartbeat.shutdownNow();
        });

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(message).build();
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("admin")
                .sessionId(session)
                .build();

        Disposable disposable = agent.streamEvents(userMsg, ctx)
                .doFinally(signal -> {
                    runningSessions.remove(session);
                    heartbeat.shutdownNow();
                    emitter.complete();
                })
                .subscribe(
                        event -> handleEvent(emitter, event),
                        error -> {
                            log.error("agent 事件流异常", error);
                            send(emitter, "error", Map.of("message", safeMessage(error)));
                        });
        emitter.onError(t -> disposable.dispose());
        return emitter;
    }

    private void handleEvent(SseEmitter emitter, AgentEvent event) {
        switch (event.getType()) {
            case THINKING_BLOCK_DELTA -> send(emitter, "thinking",
                    Map.of("delta", ((ThinkingBlockDeltaEvent) event).getDelta()));
            case TEXT_BLOCK_DELTA -> send(emitter, "answer",
                    Map.of("delta", ((TextBlockDeltaEvent) event).getDelta()));
            case TOOL_CALL_START -> send(emitter, "tool", Map.of(
                    "phase", "start", "name", ((ToolCallStartEvent) event).getToolCallName()));
            case TOOL_CALL_END -> send(emitter, "tool", Map.of(
                    "phase", "end", "name", ((ToolCallEndEvent) event).getToolCallName()));
            case AGENT_RESULT -> send(emitter, "done", resultPayload((AgentResultEvent) event));
            default -> { /* 其余事件不透出，保持前端契约精简 */ }
        }
    }

    private Map<String, Object> resultPayload(AgentResultEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Msg result = event.getResult();
        if (result != null) {
            if (result.getUsage() != null) {
                payload.put("promptTokens", result.getUsage().getInputTokens());
                payload.put("completionTokens", result.getUsage().getOutputTokens());
            }
        }
        return payload;
    }

    private void send(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            log.debug("SSE 发送失败（连接可能已断开）：{}", e.getMessage());
        }
    }

    private String safeMessage(Throwable error) {
        String msg = error.getMessage();
        return (msg == null || msg.isBlank()) ? error.getClass().getSimpleName() : msg;
    }
}
