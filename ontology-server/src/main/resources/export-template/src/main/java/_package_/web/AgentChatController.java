package {{PACKAGE_NAME}}.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import {{PACKAGE_NAME}}.config.AgentProperties;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天入口（SSE 流式），事件契约见 AgentEventWriter。
 *
 * GET  /api/chat 前置的 /api/meta 返回名称/描述/欢迎语（页面初始化用）。
 * POST /api/chat {message, sessionId?} → text/event-stream；
 * sessionId 留空则生成新会话并经 session 帧回传（前端持久化，复用框架会话记忆）。
 */
@RestController
public class AgentChatController {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final HarnessAgent agent;
    private final AgentProperties props;

    public AgentChatController(HarnessAgent agent, AgentProperties props) {
        this.agent = agent;
        this.props = props;
    }

    /** 页面元信息（标题与欢迎语） */
    public record Meta(String name, String description, String greeting) {}

    public record ChatRequest(String message, String sessionId) {}

    @GetMapping("/api/meta")
    public Meta meta() {
        return new Meta(orDefault(props.name(), "agent"), orDefault(props.description(), ""),
                orDefault(props.greeting(), ""));
    }

    /** 流式问答：session 帧同步首发，事件流异步消费 */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "[参数错误] message 不能为空");
        }
        String session = request.sessionId() == null || request.sessionId().isBlank()
                ? "run-" + UUID.randomUUID()
                : request.sessionId();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sendQuietly(emitter, new AgentEventWriter.Frame("session",
                "{\"sessionId\":" + jsonQuote(session) + "}"));

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(request.message()).build();
        RuntimeContext ctx = RuntimeContext.builder().userId("web").sessionId(session).build();

        Disposable disposable = agent.streamEvents(userMsg, ctx)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> emitter.complete())
                .subscribe(
                        event -> AgentEventWriter.frames(event).forEach(f -> sendQuietly(emitter, f)),
                        error -> sendQuietly(emitter, new AgentEventWriter.Frame("error",
                                "{\"message\":" + jsonQuote(safeMessage(error)) + "}")));
        emitter.onError(t -> disposable.dispose());
        return emitter;
    }

    /** 参数错误统一 JSON：{"message": "..."}（前端错误展示读取该字段） */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> statusError(ResponseStatusException ex) {
        String reason = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", reason));
    }

    // ---------------- 内部 ----------------

    /** 客户端断开等 IO 异常静默（onError 钩子已 dispose 上游事件流） */
    private static void sendQuietly(SseEmitter emitter, AgentEventWriter.Frame frame) {
        try {
            AgentEventWriter.send(emitter, frame);
        } catch (Exception ignore) {
            // 忽略写出异常
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** JSON 字符串字面量（sessionId / error message 转义，防引号破坏帧 JSON） */
    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }

    private static String orDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
