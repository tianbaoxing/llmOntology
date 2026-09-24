/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JavaType
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  io.agentscope.core.message.ToolResultBlock
 *  io.agentscope.core.tool.AgentTool
 *  io.agentscope.core.tool.ToolCallParam
 *  reactor.core.publisher.Mono
 *  reactor.core.scheduler.Schedulers
 */
package com.conca.agentkit;

import com.conca.agentkit.AgentDefinition;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class HttpToolAdapter
implements AgentTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15L);
    private static final int MAX_BODY_CHARS = 16384;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private final String toolName;
    private final String description;
    private final String endpoint;
    private final String pathPrefix;
    private final HttpClient http;

    public HttpToolAdapter(AgentDefinition.HttpToolConfig cfg) {
        this.toolName = cfg.name();
        this.description = cfg.description().isBlank() ? "\u5411 " + cfg.endpoint() + " \u53d1\u9001 HTTP \u8bf7\u6c42\u7684\u901a\u7528\u7ba1\u9053\uff08path \u9700\u4ee5 " + cfg.pathPrefix() + " \u5f00\u5934\uff09" : cfg.description();
        this.endpoint = cfg.endpoint().trim().replaceAll("/+$", "");
        this.pathPrefix = HttpToolAdapter.normalizePrefix(cfg.pathPrefix());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
    }

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

    public String getName() {
        return this.toolName;
    }

    public String getDescription() {
        return this.description;
    }

    public boolean isReadOnly() {
        return true;
    }

    public Map<String, Object> getParameters() {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        LinkedHashMap<String, Map<String, Object>> props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("path", Map.of("type", "string", "description", "API \u8def\u5f84\uff0c\u5fc5\u987b\u4ee5 " + this.pathPrefix + " \u5f00\u5934"));
        props.put("method", Map.of("type", "string", "description", "GET\uff08\u9ed8\u8ba4\uff09\u6216 POST"));
        props.put("params", Map.of("type", "string", "description", "URL \u67e5\u8be2\u53c2\u6570\uff0cJSON \u5bf9\u8c61\u5b57\u7b26\u4e32\u5982 {\"name\":\"x\"}\uff1b\u65e0\u53c2\u6570\u4e0d\u4f20"));
        props.put("body", Map.of("type", "string", "description", "POST \u7684 JSON \u8bf7\u6c42\u4f53\u5b57\u7b26\u4e32\uff0c\u4ec5 POST \u65f6\u53ef\u4f20\uff0c\u4e0a\u9650 16KB"));
        schema.put("properties", props);
        schema.put("required", List.of("path"));
        return schema;
    }

    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> this.execute(param.getInput())).map(out -> ToolResultBlock.text((String)out).withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName())).subscribeOn(Schedulers.boundedElastic());
    }

    private String execute(Map<String, Object> input) {
        String path = HttpToolAdapter.str(input.get("path"));
        if (!path.startsWith("/")) {
            return "[\u53c2\u6570\u9519\u8bef] path \u5fc5\u987b\u4ee5 / \u5f00\u5934\uff0c\u6536\u5230\uff1a" + path;
        }
        if (!path.startsWith(this.pathPrefix) && !path.concat("/").startsWith(this.pathPrefix)) {
            return "[\u53c2\u6570\u9519\u8bef] path \u8d8a\u754c\uff1a\u4ec5\u5141\u8bb8 " + this.pathPrefix + " \u524d\u7f00\u5185\uff0c\u6536\u5230\uff1a" + path;
        }
        if (path.contains("..")) {
            return "[\u53c2\u6570\u9519\u8bef] path \u4e0d\u5141\u8bb8\u5305\u542b ..\uff1a" + path;
        }
        String method = HttpToolAdapter.str(input.get("method"));
        String string = method = method.isBlank() ? "GET" : method.toUpperCase();
        if (!method.equals("GET") && !method.equals("POST")) {
            return "[\u53c2\u6570\u9519\u8bef] method \u4ec5\u652f\u6301 GET / POST\uff0c\u6536\u5230\uff1a" + method;
        }
        String query = this.buildQuery(HttpToolAdapter.str(input.get("params")));
        if (query.startsWith("[")) {
            return query;
        }
        String body = HttpToolAdapter.str(input.get("body"));
        String jsonBody = null;
        if (!body.isBlank()) {
            if (method.equals("GET")) {
                return "[\u53c2\u6570\u9519\u8bef] GET \u8bf7\u6c42\u4e0d\u652f\u6301 body\uff0c\u67e5\u8be2\u53c2\u6570\u8bf7\u653e params";
            }
            if (body.length() > 16384) {
                return "[\u53c2\u6570\u9519\u8bef] body \u8d85\u957f\uff1a\u5f53\u524d " + body.length() + " \u5b57\u7b26\uff0c\u4e0a\u9650 16384 \u5b57\u7b26";
            }
            try {
                MAPPER.readTree(body);
            }
            catch (Exception e) {
                return "[\u53c2\u6570\u9519\u8bef] body \u4e0d\u662f\u5408\u6cd5\u7684 JSON\uff1a" + e.getMessage();
            }
            jsonBody = body.trim();
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(this.endpoint + path + query)).timeout(REQUEST_TIMEOUT);
        if (method.equals("POST")) {
            rb.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            rb.GET();
        }
        return this.send(rb.build());
    }

    private String buildQuery(String params) {
        Map<String, Object> map;
        if (params == null || params.isBlank() || "{}".equals(params.trim())) {
            return "";
        }
        try {
            map = (Map<String, Object>) MAPPER.readValue(params,
                    MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        }
        catch (Exception e) {
            return "[\u53c2\u6570\u9519\u8bef] params \u4e0d\u662f\u5408\u6cd5\u7684 JSON \u5bf9\u8c61\uff1a" + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (sb.length() == 0) {
                sb.append('?');
            } else {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(String.valueOf(e.getKey()), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "[\u670d\u52a1\u9519\u8bef] HTTP " + resp.statusCode() + "\uff1a" + HttpToolAdapter.clip(resp.body(), 500);
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return "\u672a\u67e5\u8be2\u5230\uff08\u670d\u52a1\u8fd4\u56de\u7a7a\u54cd\u5e94\uff09";
            }
            return HttpToolAdapter.capOutput(body);
        }
        catch (ConnectException | HttpTimeoutException e) {
            return "[\u8fde\u63a5\u5931\u8d25] \u65e0\u6cd5\u8fde\u63a5 " + this.endpoint + "\uff0c\u76ee\u6807\u670d\u52a1\u53ef\u80fd\u672a\u542f\u52a8";
        }
        catch (Exception e) {
            return "[\u8fde\u63a5\u5931\u8d25] \u8bf7\u6c42\u5f02\u5e38\uff1a" + e.getMessage();
        }
    }

    private static String capOutput(String s) {
        if (s.length() <= 8000) {
            return s;
        }
        return s.substring(0, 8000) + "\n[\u54cd\u5e94\u5df2\u622a\u65ad\uff0c\u5171 " + s.length() + " \u5b57\u7b26]";
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

