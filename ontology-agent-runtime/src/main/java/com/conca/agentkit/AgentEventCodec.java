package com.conca.agentkit;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;

/**
 * AgentEvent → SSE 帧编解码（runner 独立运行时用）。
 *
 * 事件契约与平台 AgentChatService 完全一致（session/round/thinking/tool/answer/done/error，
 * 前端 chat.html 同步实现的那套）——导出 jar 的内嵌页与平台 chat 页共用同一渲染语义。
 * 平台侧沿用 SseEmitter 原路径（零改动），本类只服务无 Spring 的 runner。
 */
public final class AgentEventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单帧：事件名 + data 行 JSON */
    public record Frame(String event, String data) {}

    private AgentEventCodec() {
    }

    /** 事件 → 0..n 帧（大部分事件一帧；未知事件不透出，保持契约精简） */
    public static List<Frame> frames(AgentEvent event) {
        List<Frame> out = new ArrayList<>(1);
        switch (event.getType()) {
            case MODEL_CALL_START -> out.add(frame("round", json(java.util.Map.of(
                    "phase", "start",
                    "replyId", str(((ModelCallStartEvent) event).getReplyId())))));
            case MODEL_CALL_END -> {
                ModelCallEndEvent e = (ModelCallEndEvent) event;
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("phase", "end");
                payload.put("replyId", str(e.getReplyId()));
                if (e.getUsage() != null) {
                    payload.put("promptTokens", e.getUsage().getInputTokens());
                    payload.put("completionTokens", e.getUsage().getOutputTokens());
                }
                out.add(frame("round", json(payload)));
            }
            case THINKING_BLOCK_DELTA -> out.add(frame("thinking", json(java.util.Map.of(
                    "delta", str(((ThinkingBlockDeltaEvent) event).getDelta())))));
            case TEXT_BLOCK_DELTA -> out.add(frame("answer", json(java.util.Map.of(
                    "delta", str(((TextBlockDeltaEvent) event).getDelta())))));
            case TOOL_CALL_START -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "start",
                    "id", str(((ToolCallStartEvent) event).getToolCallId()),
                    "name", str(((ToolCallStartEvent) event).getToolCallName())))));
            case TOOL_CALL_DELTA -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "args",
                    "id", str(((ToolCallDeltaEvent) event).getToolCallId()),
                    "delta", str(((ToolCallDeltaEvent) event).getDelta())))));
            case TOOL_CALL_END -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "end",
                    "id", str(((ToolCallEndEvent) event).getToolCallId()),
                    "name", str(((ToolCallEndEvent) event).getToolCallName())))));
            case TOOL_RESULT_START -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "result-start",
                    "id", str(((ToolResultStartEvent) event).getToolCallId()),
                    "name", str(((ToolResultStartEvent) event).getToolCallName())))));
            case TOOL_RESULT_TEXT_DELTA -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "result-delta",
                    "id", str(((ToolResultTextDeltaEvent) event).getToolCallId()),
                    "delta", str(((ToolResultTextDeltaEvent) event).getDelta())))));
            case TOOL_RESULT_DATA_DELTA -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "result-delta",
                    "id", str(((ToolResultDataDeltaEvent) event).getToolCallId()),
                    "delta", dataText(((ToolResultDataDeltaEvent) event).getData())))));
            case TOOL_RESULT_END -> out.add(frame("tool", json(java.util.Map.of(
                    "phase", "result-end",
                    "id", str(((ToolResultEndEvent) event).getToolCallId()),
                    "name", str(((ToolResultEndEvent) event).getToolCallName()),
                    "state", ((ToolResultEndEvent) event).getState() == null
                            ? "" : ((ToolResultEndEvent) event).getState().name()))));
            case AGENT_RESULT -> {
                AgentResultEvent e = (AgentResultEvent) event;
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                if (e.getResult() != null && e.getResult().getUsage() != null) {
                    payload.put("promptTokens", e.getResult().getUsage().getInputTokens());
                    payload.put("completionTokens", e.getResult().getUsage().getOutputTokens());
                }
                out.add(frame("done", json(payload)));
            }
            default -> { /* 其余事件不透出 */}
        }
        return out;
    }

    private static Frame frame(String event, String data) {
        return new Frame(event, data);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** DataDelta 结构化 block：文本直接透出，其余以类型名占位（与平台同语义） */
    private static String dataText(ContentBlock data) {
        if (data instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText();
        }
        return data == null ? "" : "[" + data.getClass().getSimpleName() + "]";
    }

    private static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
