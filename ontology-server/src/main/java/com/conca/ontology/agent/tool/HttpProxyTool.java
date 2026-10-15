package com.conca.ontology.agent.tool;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 本体服务 /api/llm 只读平面的哑 HTTP 管道（单工具 http_request_ontology_query）。
 *
 * 定位：无业务语义的基础设施，与 read_file / grep_files 同级——
 * 不认识 schema、不预置端点、不做 Cypher 预检；查询协议（何时调哪个端点、
 * 如何组装只读 Cypher）全部由 skills/ontology-query/SKILL.md 承载，
 * 不加载 skill 就无法正确使用，从而保证"查询经由 skills"。
 *
 * 安全边界：
 * - base-url 服务端固定（构造注入），agent 只能传 path，无 SSRF 面；
 * - path 前缀白名单 /api/llm/（该平面全只读、免登录、无管理面端点）；
 * - 方法白名单 GET/POST；写语句由服务端只读红线兜底拦截。
 *
 * 错误返回为确定性中文文案并带层前缀，便于模型机械判层：
 * [连接失败] 服务未启动 / [参数错误] 本地校验拒绝 / [服务错误] 服务端 4xx/5xx
 *
 * @author tianbaoxing
 * @date 2026-08-24
 */
public class HttpProxyTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String PATH_PREFIX = "/api/llm/";
    private static final int MAX_BODY_CHARS = 16 * 1024;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;

    public HttpProxyTool(String baseUrl) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9876"
                : baseUrl.trim().replaceAll("/+$", "");
    }

    @Tool(name = "http_request_ontology_query", readOnly = true, concurrencySafe = true,
            description = "向本体服务只读平面发送 HTTP 请求的哑管道。"
                    + "适用：按 ontology-query skill 的端点速查表查询概念层、执行只读 Cypher、"
                    + "列出已上传文件、查询构建背景 profile。"
                    + "path 必须以 /api/llm/ 开头；GET 传 params，POST 传 body（JSON 对象字符串）。"
                    + "示例：查概念层 → path=/api/llm/schema；执行查询 → "
                    + "path=/api/llm/ontology-query/query, method=POST, "
                    + "body={\"question\":\"有多少文物\",\"cypher\":\"MATCH (n:`CulturalRelic`) RETURN count(n) AS c\",\"params\":{}}")
    public String httpRequest(
            RuntimeContext ctx,
            @ToolParam(name = "path", description = "API 路径，必须以 /api/llm/ 开头，如 /api/llm/schema") String path,
            @ToolParam(name = "method", required = false, description = "GET（默认）或 POST") String method,
            @ToolParam(name = "params", required = false,
                    description = "URL 查询参数，JSON 对象字符串如 {\"name\":\"x\"}；无参数不传") String params,
            @ToolParam(name = "body", required = false,
                    description = "POST 的 JSON 请求体字符串，仅 POST 时可传，上限 16KB") String body) {

        // ---- 参数校验（本地拒绝，返回 [参数错误]）----
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) {
            return "[参数错误] path 不能为空（必须以 /api/llm/ 开头，可用端点见 skill 端点速查表）";
        }
        if (!p.startsWith("/")) {
            return "[参数错误] path 必须以 / 开头，收到：" + p;
        }
        if (!p.startsWith(PATH_PREFIX)) {
            return "[参数错误] path 越界：仅允许 /api/llm/ 前缀内的只读端点，收到：" + p;
        }
        if (p.contains("..")) {
            return "[参数错误] path 不允许包含 ..：" + p;
        }

        String m = (method == null || method.isBlank()) ? "GET" : method.trim().toUpperCase();
        if (!m.equals("GET") && !m.equals("POST")) {
            return "[参数错误] method 仅支持 GET / POST，收到：" + method;
        }

        String query = buildQuery(params);
        if (query.startsWith("[")) {
            return query;
        }

        String jsonBody = null;
        if (body != null && !body.isBlank()) {
            if (m.equals("GET")) {
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

        // ---- 发送请求 ----
        URI uri = URI.create(baseUrl + p + query);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT);
        if (m.equals("POST")) {
            rb.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            rb.GET();
        }
        return send(rb.build());
    }

    /** 解析 params 为 URL query string；非法输入返回带 [参数错误] 前缀的文案 */
    private String buildQuery(String params) {
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
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "[服务错误] HTTP " + resp.statusCode() + "：" + clip(resp.body(), 500);
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return "未查询到（服务返回空响应）";
            }
            return capOutput(body);
        } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
            return "[连接失败] 无法连接 " + baseUrl + "，本体服务可能未启动";
        } catch (Exception e) {
            return "[连接失败] 请求异常：" + e.getMessage();
        }
    }

    /** 总长度兜底截断，防止超长输出刷爆上下文 */
    private String capOutput(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n[响应已截断，共 " + s.length() + " 字符]";
    }

    private String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
