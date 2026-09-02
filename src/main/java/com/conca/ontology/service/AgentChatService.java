package com.conca.ontology.service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.Disposable;

/**
 * AI 问答服务：把 HarnessAgent 的 AgentEvent 流映射为 SSE 事件推给页面。
 * 事件契约（前端 chat.html 同步实现）：
 * session → 会话号；round → 轮次边界（ReAct 每轮模型调用一对
 * start/end，end 携带该轮 tokens；前端按轮分块渲染，与历史渲染一致）；
 * thinking → 思考增量；tool → 工具调用与执行
 * （phase=start/args/end 为调用阶段，args 为参数增量；
 * phase=result-start/result-delta/result-end 为执行结果阶段，
 * result-delta 为结果文本增量，result-end 携带 state（success/error 等），
 * id 全程用于配对同一次调用）；
 * answer → 正文增量；done → 结束；error → 错误。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final long HEARTBEAT_SECONDS = 15;

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    /** 进行中会话的所属用户（sessionId → userId），供停止接口校验归属并作用域中断 */
    private final Map<String, String> sessionOwners = new ConcurrentHashMap<>();

    /** 反射缓存：HarnessAgent 持有的底层 ReActAgent（interrupt 需按其会话作用域触发） */
    private volatile ReActAgent delegateRef;

    public AgentChatService(ObjectProvider<HarnessAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    /** agent 是否已装配（OPENROUTER_API_KEY 已配置） */
    public boolean isAvailable() {
        return agentProvider.getIfAvailable() != null;
    }

    /** 会话是否有流式请求进行中（供历史会话删除前互斥检查） */
    public boolean isRunning(String sessionId) {
        return sessionId != null && runningSessions.contains(sessionId);
    }

    /**
     * 优雅停止某会话进行中的回答：向框架触发该会话作用域的 USER 中断（而非拆断连接），
     * 框架在 handleInterrupt 时调用 saveStateToSession，把「用户问题 + 已生成的部分回答」落盘，
     * 随后该轮流正常收尾（因此刷新后问题不再消失）。
     * 仅允许该会话的发起用户操作；无进行中的回答返回 false。
     */
    public boolean stop(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // 非当前用户发起的会话或已无进行中的流 → 拒绝
        String owner = sessionOwners.get(sessionId);
        if (owner == null || !owner.equals(userId)) {
            return false;
        }
        if (!runningSessions.contains(sessionId)) {
            return false;
        }
        ReActAgent delegate = delegate();
        if (delegate == null) {
            log.warn("无法获取底层 ReActAgent，停止功能不可用（session={}）", sessionId);
            return false;
        }
        // 作用域到 (userId, sessionId) 的 USER 源中断；当前进行中的流观察到后走 handleInterrupt → 落盘
        delegate.interrupt(userId, sessionId, null);
        return true;
    }

    /** 反射读取 HarnessAgent 持有的底层 ReActAgent（结果缓存，agent 为单例） */
    private ReActAgent delegate() {
        ReActAgent d = delegateRef;
        if (d == null) {
            synchronized (this) {
                d = delegateRef;
                if (d == null) {
                    HarnessAgent agent = agentProvider.getIfAvailable();
                    if (agent == null) {
                        return null;
                    }
                    try {
                        Field f = HarnessAgent.class.getDeclaredField("delegate");
                        f.setAccessible(true);
                        d = (ReActAgent) f.get(agent);
                        delegateRef = d;
                    } catch (Exception e) {
                        log.warn("反射读取 HarnessAgent.delegate 失败：{}", e.getMessage());
                        return null;
                    }
                }
            }
        }
        return d;
    }

    /** 对外提示未配置 key 时的错误文案 */
    public static String missingKeyMessage() {
        return "未配置 OPENROUTER_API_KEY：请在项目根目录 .env 中填写后重启服务（模板见 .env.example）。";
    }

    /**
     * 工具清单：从 HarnessAgent 的 Toolkit 实时读取
     * （页面展示的工具 = 模型本次实际可见的工具集，单一事实来源；
     * 记忆工具开关切换后 Toolkit 增减，本清单随之变化）。
     */
    public List<Map<String, String>> listTools() {
        List<Map<String, String>> tools = new ArrayList<>();
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return tools;
        }
        List<String> names = new ArrayList<>(agent.getToolkit().getToolNames());
        Collections.sort(names);
        for (String name : names) {
            AgentTool tool = agent.getToolkit().getTool(name);
            if (tool == null) {
                continue;
            }
            String desc = tool.getDescription();
            tools.add(Map.of(
                    "name", name,
                    "description", desc == null ? "" : desc));
        }
        return tools;
    }

    /**
     * 技能清单：从 HarnessAgent 已装配的技能仓库读取
     * （页面展示的技能 = 模型实际可用的技能，单一事实来源）。
     * 跨仓库按 name 去重（workspace skills 与全局目录可能同名，取先注册的）。
     */
    public List<Map<String, String>> listSkills() {
        List<Map<String, String>> skills = new ArrayList<>();
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return skills;
        }
        Set<String> seen = new HashSet<>();
        for (AgentSkillRepository repo : agent.getSkillRepositories()) {
            for (AgentSkill skill : repo.getAllSkills()) {
                if (skill == null || skill.getName() == null || !seen.add(skill.getName())) {
                    continue;
                }
                String desc = skill.getDescription();
                skills.add(Map.of(
                        "name", skill.getName(),
                        "description", desc == null ? "" : desc));
            }
        }
        return skills;
    }

    /**
     * 流式问答：订阅 streamEvents 并逐事件转发到 SseEmitter。
     * userId 为当前登录用户（会话/记忆按用户隔离，落盘在 workspace/{userId}/ 下）。
     */
    public SseEmitter chat(String sessionId, String message, String userId) {
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
        sessionOwners.put(session, userId);

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
            sessionOwners.remove(session);
            heartbeat.shutdownNow();
        });

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(message).build();
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(session)
                .build();

        Disposable disposable = agent.streamEvents(userMsg, ctx)
                .doFinally(signal -> {
                    runningSessions.remove(session);
                    sessionOwners.remove(session);
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
            case MODEL_CALL_START -> send(emitter, "round", Map.of(
                    "phase", "start", "replyId", safeStr(((ModelCallStartEvent) event).getReplyId())));
            case MODEL_CALL_END -> send(emitter, "round", roundEndPayload((ModelCallEndEvent) event));
            case THINKING_BLOCK_DELTA -> send(emitter, "thinking",
                    Map.of("delta", ((ThinkingBlockDeltaEvent) event).getDelta()));
            case TEXT_BLOCK_DELTA -> send(emitter, "answer",
                    Map.of("delta", ((TextBlockDeltaEvent) event).getDelta()));
            case TOOL_CALL_START -> send(emitter, "tool", Map.of(
                    "phase", "start", "id", ((ToolCallStartEvent) event).getToolCallId(),
                    "name", ((ToolCallStartEvent) event).getToolCallName()));
            case TOOL_CALL_DELTA -> send(emitter, "tool", Map.of(
                    "phase", "args", "id", ((ToolCallDeltaEvent) event).getToolCallId(),
                    "delta", safeDelta(((ToolCallDeltaEvent) event).getDelta())));
            case TOOL_CALL_END -> send(emitter, "tool", Map.of(
                    "phase", "end", "id", ((ToolCallEndEvent) event).getToolCallId(),
                    "name", ((ToolCallEndEvent) event).getToolCallName()));
            case TOOL_RESULT_START -> send(emitter, "tool", Map.of(
                    "phase", "result-start", "id", ((ToolResultStartEvent) event).getToolCallId(),
                    "name", ((ToolResultStartEvent) event).getToolCallName()));
            case TOOL_RESULT_TEXT_DELTA -> send(emitter, "tool", Map.of(
                    "phase", "result-delta", "id", ((ToolResultTextDeltaEvent) event).getToolCallId(),
                    "delta", safeDelta(((ToolResultTextDeltaEvent) event).getDelta())));
            case TOOL_RESULT_DATA_DELTA -> send(emitter, "tool", Map.of(
                    "phase", "result-delta", "id", ((ToolResultDataDeltaEvent) event).getToolCallId(),
                    "delta", safeDelta(dataDeltaText(((ToolResultDataDeltaEvent) event).getData()))));
            case TOOL_RESULT_END -> send(emitter, "tool", Map.of(
                    "phase", "result-end", "id", ((ToolResultEndEvent) event).getToolCallId(),
                    "name", ((ToolResultEndEvent) event).getToolCallName(),
                    "state", safeState(((ToolResultEndEvent) event).getState())));
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

    /** 轮次结束：携带该轮模型调用的 tokens（usage 可能缺失，如异常截断） */
    private static Map<String, Object> roundEndPayload(ModelCallEndEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "end");
        payload.put("replyId", safeStr(event.getReplyId()));
        ChatUsage usage = event.getUsage();
        if (usage != null) {
            payload.put("promptTokens", usage.getInputTokens());
            payload.put("completionTokens", usage.getOutputTokens());
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

    /** Map.of 不接受 null 值，delta 可能为 null 时兜底空串 */
    private static String safeDelta(String delta) {
        return delta == null ? "" : delta;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    /** DataDelta 事件携带结构化 block：文本直接透出，其余以类型名占位 */
    private static String dataDeltaText(ContentBlock data) {
        if (data instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText();
        }
        return data == null ? "" : "[" + data.getClass().getSimpleName() + "]";
    }

    private static String safeState(ToolResultState state) {
        return state == null ? "" : state.name();
    }
}
