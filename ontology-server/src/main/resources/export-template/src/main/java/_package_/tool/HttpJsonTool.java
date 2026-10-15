package {{PACKAGE_NAME}}.tool;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通用 HTTP JSON 工具（行为对齐 llmOntology 平台 HttpToolAdapter）：
 * 端点固定在 application.yml，模型仅可控 path（须以 pathPrefix 开头，禁 ..）与查询参数。
 * 仅 GET / POST；POST body 须为合法 JSON（上限 16KB）；响应超 8000 字符截断。
 * 错误文案确定性中文，按前缀分层：[参数错误]（本地校验拒绝）/ [服务错误]（HTTP 非 200）/
 * [连接失败]（目标服务未启动或网络异常）。
 *
 * @author tianbaoxing
 * @date 2026-09-29
 */
public final class HttpJsonTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_BODY_CHARS = 16384;
    private static final int MAX_OUTPUT_CHARS = 8000;

    private final String toolName;
    private final String description;
    private final String endpoint;
    private final String pathPrefix;
    private final HttpClient http;

    public HttpJsonTool(String toolName, String description, String endpoint, String pathPrefix) {
        this.toolName = toolName;
        this.pathPrefix = normalizePrefix(pathPrefix);
        this.description = description == null || description.isBlank()
                ? "向 " + endpoint + " 发送 HTTP 请求的通用管道（path 需以 " + this.pathPrefix + " 开头）"
                : description;
        this.endpoint = endpoint.trim().replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 白名单前缀统一为首尾带 / 的形态（"/api" 与 "api/" 均归一为 "/api/"） */
    private static String normalizePrefix(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "/" : prefix.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string",
                "description", "API 路径，必须以 " + pathPrefix + " 开头"));
        props.put("method", Map.of("type", "string",
                "description", "GET（默认）或 POST"));
        props.put("params", Map.of("type", "string",
                "description", "URL 查询参数，JSON 对象字符串如 {\"name\":\"x\"}；无参数不传"));
        props.put("body", Map.of("type", "string",
                "description", "POST 的 JSON 请求体字符串，仅 POST 时可传，上限 16KB"));
        schema.put("properties", props);
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param.getInput()))
                .map(out -> ToolResultBlock.text(out)
                        .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ---------------- 校验链：path 白名单 → method → params → body ----------------

    private String execute(Map<String, Object> input) {
        String path = str(input.get("path"));
        if (!path.startsWith("/")) {
            return "[参数错误] path 必须以 / 开头，收到：" + path;
        }
        if (!path.startsWith(pathPrefix) && !path.concat("/").startsWith(pathPrefix)) {
            return "[参数错误] path 越界：仅允许 " + pathPrefix + " 前缀内，收到：" + path;
        }
        if (path.contains("..")) {
            return "[参数错误] path 不允许包含 ..：" + path;
        }
        String method = str(input.get("method"));
        method = method.isBlank() ? "GET" : method.toUpperCase();
        if (!method.equals("GET") && !method.equals("POST")) {
            return "[参数错误] method 仅支持 GET / POST，收到：" + method;
        }
        String query = buildQuery(str(input.get("params")));
        if (query.startsWith("[")) {
            return query; // buildQuery 的 [参数错误] 文案原样透出
        }
        String body = str(input.get("body"));
        String jsonBody = null;
        if (!body.isBlank()) {
            if (method.equals("GET")) {
                return "[参数错误] GET 请求不支持 body，查询参数请放 params";
            }
            if (body.length() > MAX_BODY_CHARS) {
                return "[参数错误] body 超长：当前 " + body.length() + " 字符，上限 " + MAX_BODY_CHARS + " 字符";
            }
            try {
                MAPPER.readTree(body);
            } catch (Exception e) {
                return "[参数错误] body 不是合法的 JSON：" + e.getMessage();
            }
            jsonBody = body.trim();
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint + path + query))
                .timeout(REQUEST_TIMEOUT);
        if (method.equals("POST")) {
            rb.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            rb.GET();
        }
        return send(rb.build());
    }

    /** params（JSON 对象字符串）→ URL 查询串；非法输入返回 [参数错误] 文案 */
    private static String buildQuery(String params) {
        if (params == null || params.isBlank() || "{}".equals(params.trim())) {
            return "";
        }
        Map<String, Object> map;
        try {
            map = MAPPER.readValue(params,
                    MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            return "[参数错误] params 不是合法的 JSON 对象：" + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (sb.length() == 0) {
                sb.append('?');
            } else {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(String.valueOf(e.getKey()), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "[服务错误] HTTP " + resp.statusCode() + "：" + clip(resp.body(), 500);
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return "未查询到（服务返回空响应）";
            }
            return capOutput(body);
        } catch (ConnectException | HttpTimeoutException e) {
            return "[连接失败] 无法连接 " + endpoint + "，目标服务可能未启动";
        } catch (Exception e) {
            return "[连接失败] 请求异常：" + e.getMessage();
        }
    }

    private static String capOutput(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n[响应已截断，共 " + s.length() + " 字符]";
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
