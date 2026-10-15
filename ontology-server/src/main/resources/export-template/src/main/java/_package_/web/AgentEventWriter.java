package {{PACKAGE_NAME}}.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
 * AgentEvent → SSE 帧（事件契约与 llmOntology 平台 chat 页完全一致）：
 * session → round ×N（每轮模型调用 start/end，end 携带该轮 tokens）
 * → thinking / tool / answer → done / error。未知事件不透出，保持契约精简。
 *
 * @author tianbaoxing
 * @date 2026-09-29
 */
public final class AgentEventWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单帧：事件名 + data 行 JSON */
    public record Frame(String event, String data) {}

    private AgentEventWriter() {
    }

    /** 事件 → 0..n 帧（大部分事件一帧） */
    public static List<Frame> frames(AgentEvent event) {
        List<Frame> out = new ArrayList<>(1);
        switch (event.getType()) {
            case MODEL_CALL_START -> out.add(new Frame("round", json(Map.of(
                    "phase", "start",
                    "replyId", str(((ModelCallStartEvent) event).getReplyId())))));
            case MODEL_CALL_END -> {
                ModelCallEndEvent e = (ModelCallEndEvent) event;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("phase", "end");
                payload.put("replyId", str(e.getReplyId()));
                if (e.getUsage() != null) {
                    payload.put("promptTokens", e.getUsage().getInputTokens());
                    payload.put("completionTokens", e.getUsage().getOutputTokens());
                }
                out.add(new Frame("round", json(payload)));
            }
            case THINKING_BLOCK_DELTA -> out.add(new Frame("thinking", json(Map.of(
                    "delta", str(((ThinkingBlockDeltaEvent) event).getDelta())))));
            case TEXT_BLOCK_DELTA -> out.add(new Frame("answer", json(Map.of(
                    "delta", str(((TextBlockDeltaEvent) event).getDelta())))));
            case TOOL_CALL_START -> out.add(new Frame("tool", json(Map.of(
                    "phase", "start",
                    "id", str(((ToolCallStartEvent) event).getToolCallId()),
                    "name", str(((ToolCallStartEvent) event).getToolCallName())))));
            case TOOL_CALL_DELTA -> out.add(new Frame("tool", json(Map.of(
                    "phase", "args",
                    "id", str(((ToolCallDeltaEvent) event).getToolCallId()),
                    "delta", str(((ToolCallDeltaEvent) event).getDelta())))));
            case TOOL_CALL_END -> out.add(new Frame("tool", json(Map.of(
                    "phase", "end",
                    "id", str(((ToolCallEndEvent) event).getToolCallId()),
                    "name", str(((ToolCallEndEvent) event).getToolCallName())))));
            case TOOL_RESULT_START -> out.add(new Frame("tool", json(Map.of(
                    "phase", "result-start",
                    "id", str(((ToolResultStartEvent) event).getToolCallId()),
                    "name", str(((ToolResultStartEvent) event).getToolCallName())))));
            case TOOL_RESULT_TEXT_DELTA -> out.add(new Frame("tool", json(Map.of(
                    "phase", "result-delta",
                    "id", str(((ToolResultTextDeltaEvent) event).getToolCallId()),
                    "delta", str(((ToolResultTextDeltaEvent) event).getDelta())))));
            case TOOL_RESULT_DATA_DELTA -> out.add(new Frame("tool", json(Map.of(
                    "phase", "result-delta",
                    "id", str(((ToolResultDataDeltaEvent) event).getToolCallId()),
                    "delta", dataText(((ToolResultDataDeltaEvent) event).getData())))));
            case TOOL_RESULT_END -> out.add(new Frame("tool", json(Map.of(
                    "phase", "result-end",
                    "id", str(((ToolResultEndEvent) event).getToolCallId()),
                    "name", str(((ToolResultEndEvent) event).getToolCallName()),
                    "state", ((ToolResultEndEvent) event).getState() == null
                            ? "" : ((ToolResultEndEvent) event).getState().name()))));
            case AGENT_RESULT -> {
                AgentResultEvent e = (AgentResultEvent) event;
                Map<String, Object> payload = new LinkedHashMap<>();
                if (e.getResult() != null && e.getResult().getUsage() != null) {
                    payload.put("promptTokens", e.getResult().getUsage().getInputTokens());
                    payload.put("completionTokens", e.getResult().getUsage().getOutputTokens());
                }
                out.add(new Frame("done", json(payload)));
            }
            default -> { /* 其余事件不透出 */ }
        }
        return out;
    }

    /** 帧经 SseEmitter 写出（event 名 + data 行） */
    public static void send(SseEmitter emitter, Frame frame) throws IOException {
        emitter.send(SseEmitter.event().name(frame.event()).data(frame.data()));
    }

    // ---------------- 内部 ----------------

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
