package com.conca.ontology.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 历史会话服务：复用框架 agent-state 持久化（HarnessAgent 轮末自动落盘），
 * 只读 + 删除，全部经 AgentStateStore 官方 API，不解析 state 文件本身。
 * 删除仅清 agent 记忆（stateStore.delete）；workspace 侧 jsonl 日志为无害残留，不清理。
 */
@Service
public class AgentSessionService {

    /** 与 AgentChatService 中 RuntimeContext.userId 一致 */
    static final String USER_ID = "admin";
    /** 框架 ReActAgent 落盘 state 用的 key（JsonFileAgentStateStore 对应 agent_state.json） */
    static final String STATE_KEY = "agent_state";
    /** 与框架同款 sessionId 合法字符集，先挡路径穿越 */
    private static final Pattern SESSION_ID = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");
    private static final int TITLE_MAX = 30;
    private static final int TOOL_SUMMARY_MAX = 300;

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final AgentChatService agentChatService;
    private final ObjectMapper mapper;

    public AgentSessionService(ObjectProvider<HarnessAgent> agentProvider,
            AgentChatService agentChatService, ObjectMapper mapper) {
        this.agentProvider = agentProvider;
        this.agentChatService = agentChatService;
        this.mapper = mapper;
    }

    /** 会话列表（updateTime 倒序）；未配置 key 或目录为空均返回空列表 */
    public Map<String, Object> list() {
        AgentStateStore store = storeOrNull();
        if (store == null) {
            return Map.of("sessions", List.of());
        }
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String id : store.listSessionIds(USER_ID)) {
            store.get(USER_ID, id, STATE_KEY, AgentState.class).ifPresent(state -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", id);
                m.put("title", titleOf(state));
                m.put("updateTime", updateTimeOf(state));
                m.put("messageCount", state.getContext() == null ? 0 : state.getContext().size());
                sessions.add(m);
            });
        }
        sessions.sort(Comparator.comparing(
                s -> String.valueOf(s.get("updateTime")), Comparator.reverseOrder()));
        return Map.of("sessions", sessions);
    }

    /** 单会话全部消息，映射为前端可直接渲染的结构 */
    public Map<String, Object> messages(String sessionId) {
        String id = validate(sessionId);
        AgentState state = requireStore().get(USER_ID, id, STATE_KEY, AgentState.class)
                .orElseThrow(() -> new ApiException(404, "会话不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        result.put("messages", mapMessages(state));
        return result;
    }

    /** 删除会话：进行中拒绝（409）→ 清 agent 记忆（官方 delete） */
    public Map<String, Object> delete(String sessionId) {
        String id = validate(sessionId);
        AgentStateStore store = requireStore();
        if (agentChatService.isRunning(id)) {
            throw new ApiException(409, "会话正在处理中，请稍后再删除");
        }
        if (!store.exists(USER_ID, id)) {
            throw new ApiException(404, "会话不存在");
        }
        store.delete(USER_ID, id);
        return Map.of("deleted", id);
    }

    // ---------------- 内部 ----------------

    private AgentStateStore storeOrNull() {
        HarnessAgent agent = agentProvider.getIfAvailable();
        return agent == null ? null : agent.getStateStore();
    }

    private AgentStateStore requireStore() {
        AgentStateStore store = storeOrNull();
        if (store == null) {
            throw new ApiException(503, AgentChatService.missingKeyMessage());
        }
        return store;
    }

    private static String validate(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new ApiException(400, "非法会话 ID");
        }
        return sessionId;
    }

    private static String titleOf(AgentState state) {
        if (state.getContext() != null) {
            for (Msg msg : state.getContext()) {
                if (msg.getRole() == MsgRole.USER) {
                    String text = textOf(msg).strip();
                    if (!text.isEmpty()) {
                        return text.length() > TITLE_MAX ? text.substring(0, TITLE_MAX) + "…" : text;
                    }
                }
            }
        }
        return "(空会话)";
    }

    private static String updateTimeOf(AgentState state) {
        if (state.getContext() != null) {
            for (int i = state.getContext().size() - 1; i >= 0; i--) {
                String ts = state.getContext().get(i).getTimestamp();
                if (ts != null && !ts.isBlank()) {
                    return ts;
                }
            }
        }
        return "";
    }

    private static String textOf(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    /** context → 前端消息结构：user / assistant（thinking、tools、answer、tokens） */
    private List<Map<String, Object>> mapMessages(AgentState state) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> pendingTools = new LinkedHashMap<>();
        if (state.getContext() == null) {
            return result;
        }
        for (Msg msg : state.getContext()) {
            if (msg.getRole() == MsgRole.USER) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "user");
                m.put("content", textOf(msg));
                m.put("ts", msg.getTimestamp());
                result.add(m);
            } else if (msg.getRole() == MsgRole.ASSISTANT) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("ts", msg.getTimestamp());
                StringBuilder thinking = new StringBuilder();
                StringBuilder answer = new StringBuilder();
                List<Map<String, Object>> tools = new ArrayList<>();
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ThinkingBlock tb) {
                        thinking.append(tb.getThinking());
                    } else if (b instanceof TextBlock tb) {
                        answer.append(tb.getText());
                    } else if (b instanceof ToolUseBlock tb) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", tb.getName());
                        t.put("input", summarizeObject(tb.getInput()));
                        tools.add(t);
                        pendingTools.put(tb.getId(), t);
                    }
                }
                m.put("thinking", thinking.toString());
                m.put("answer", answer.toString());
                m.put("tools", tools);
                ChatUsage usage = msg.getUsage();
                if (usage != null) {
                    m.put("promptTokens", usage.getInputTokens());
                    m.put("completionTokens", usage.getOutputTokens());
                }
                result.add(m);
            } else if (msg.getRole() == MsgRole.TOOL) {
                // 工具结果按 id 回填到上一条 assistant 消息的 tools 条目
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ToolResultBlock tb) {
                        Map<String, Object> t = pendingTools.get(tb.getId());
                        if (t != null) {
                            t.put("output", summarizeOutput(tb.getOutput()));
                        }
                    }
                }
            }
        }
        return result;
    }

    private String summarizeObject(Object input) {
        if (input == null) {
            return "";
        }
        String s;
        try {
            s = mapper.writeValueAsString(input);
        } catch (Exception e) {
            s = String.valueOf(input);
        }
        return truncate(s);
    }

    private String summarizeOutput(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : output) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return truncate(sb.toString());
    }

    private static String truncate(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > TOOL_SUMMARY_MAX ? s.substring(0, TOOL_SUMMARY_MAX) + "…" : s;
    }
}
