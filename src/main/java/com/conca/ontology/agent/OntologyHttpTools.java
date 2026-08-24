package com.conca.ontology.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 本体服务原生 HTTP 工具集：注册进 HarnessAgent 的 Toolkit，
 * 模型以结构化工具调用访问 /api/llm 只读平面，替代手写 curl，
 * 从根本上绕开 execute（shell 包装层）在 Windows 下的缺陷。
 *
 * 错误返回为确定性中文文案并带层前缀，便于模型机械判层：
 * [连接失败] 服务未启动 / [参数错误] 本地校验拒绝 / [服务错误] 服务端 4xx/5xx
 */
public class OntologyHttpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 只读预检红线，与 OntologyQueryExecutionService 服务端保持一致（双保险） */
    private static final Pattern WRITE_RE = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|LOAD|FOREACH|CALL|USE|INSERT|UPDATE)\\b");
    private static final Pattern READ_HINT_RE = Pattern.compile(
            "(?i)\\b(MATCH|OPTIONAL\\s+MATCH|RETURN|WITH|UNWIND)\\b");
    private static final Pattern LIMIT_RE = Pattern.compile("(?i)\\blimit\\b");

    private static final int MAX_ROWS = 20;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private static final int AUTO_LIMIT = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;

    public OntologyHttpTools(String baseUrl) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9876"
                : baseUrl.trim().replaceAll("/+$", "");
    }

    // ==================== 工具：概念层 ====================

    @Tool(name = "ontology_schema", readOnly = true, concurrencySafe = true,
            description = "查询本体概念层（schema）。action=all 返回全部类与关系总览；"
                    + "action=class 返回指定类的属性/keyProp/中文名（需同时传 className）；"
                    + "action=relations 返回关系类型与方向。查询图谱前必须先用本工具确认类名、属性名、关系名。")
    public String ontologySchema(
            RuntimeContext ctx,
            @ToolParam(name = "action", description = "all=概念层总览；class=单个类详情；relations=关系清单") String action,
            @ToolParam(name = "className", required = false,
                    description = "类名，如 CulturalRelic、Place、Era；仅 action=class 时必填") String className) {
        String a = action == null ? "" : action.trim().toLowerCase();
        String path;
        switch (a) {
            case "all" -> path = "/api/llm/schema";
            case "class" -> {
                if (className == null || className.isBlank()) {
                    return "[参数错误] action=class 时必须提供 className（可先用 action=all 查看全部类名）";
                }
                path = "/api/llm/schema/classes/" + className.trim();
            }
            case "relations" -> path = "/api/llm/schema/relations";
            default -> {
                return "[参数错误] action 仅支持 all / class / relations，收到：" + action;
            }
        }
        return getAndPassThrough(path);
    }

    // ==================== 工具：图谱查询 ====================

    @Tool(name = "graph_query", readOnly = true, concurrencySafe = true,
            description = "执行只读 Cypher 查询本体图谱并返回 JSON（keys/rows/count/found）。"
                    + "仅允许 MATCH/OPTIONAL MATCH/WHERE/WITH/RETURN/ORDER BY/LIMIT/UNWIND，"
                    + "label、属性、关系名必须来自 ontology_schema 的结果；写语句会被拒绝。"
                    + "未写 LIMIT 时会自动追加 LIMIT 100；结果超过 20 行仅返回前 20 行。"
                    + "示例：查询名称含\"墓\"的地点数量 → cypher: MATCH (n:`Place`) WHERE n.name CONTAINS '墓' RETURN count(n) AS c")
    public String graphQuery(
            RuntimeContext ctx,
            @ToolParam(name = "question", description = "本次查询对应的用户问题，用于结果溯源") String question,
            @ToolParam(name = "cypher", description = "只读 Cypher 语句") String cypher,
            @ToolParam(name = "paramsJson", required = false,
                    description = "Cypher 参数，JSON 对象字符串如 {\"name\":\"x\"}；无参数传空串或 {}") String paramsJson) {
        // 本地只读预检（与服务端红线一致）
        if (cypher == null || cypher.isBlank()) {
            return "[参数错误] cypher 不能为空";
        }
        String normalized = cypher.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.contains(";")) {
            return "[参数错误] 仅允许单条只读 Cypher 语句（检出分号）";
        }
        if (WRITE_RE.matcher(normalized).find()) {
            return "[参数错误] 只读查询被拒绝：包含写关键字（CREATE/MERGE/DELETE/SET/CALL 等）";
        }
        if (!READ_HINT_RE.matcher(normalized).find()) {
            return "[参数错误] 不是可识别的只读查询（未检出 MATCH/RETURN 等读关键字）";
        }
        boolean autoLimit = !LIMIT_RE.matcher(normalized).find();
        if (autoLimit) {
            normalized = normalized + " LIMIT " + AUTO_LIMIT;
        }

        Map<String, Object> params = Map.of();
        if (paramsJson != null && !paramsJson.isBlank() && !"{}".equals(paramsJson.trim())) {
            try {
                params = MAPPER.readValue(paramsJson,
                        MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            } catch (Exception e) {
                return "[参数错误] paramsJson 不是合法的 JSON 对象：" + e.getMessage();
            }
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("question", question == null ? "" : question);
        body.put("cypher", normalized);
        body.set("params", MAPPER.valueToTree(params));

        String resp = post("/api/llm/ontology-query/query", body.toString());
        if (resp.startsWith("[")) {
            return resp; // 错误文案直接透传
        }
        try {
            JsonNode root = MAPPER.readTree(resp);
            if (root.path("code").asInt(-1) != 0) {
                return "[服务错误] " + root.path("msg").asText(resp);
            }
            JsonNode data = root.get("data");
            if (data != null && data.isObject()) {
                ObjectNode d = (ObjectNode) data;
                if (autoLimit) {
                    d.put("autoLimit", "原查询未写 LIMIT，已自动追加 LIMIT " + AUTO_LIMIT);
                }
                JsonNode rows = d.get("rows");
                if (rows != null && rows.isArray() && rows.size() > MAX_ROWS) {
                    ArrayNode kept = MAPPER.createArrayNode();
                    for (int i = 0; i < MAX_ROWS; i++) {
                        kept.add(rows.get(i));
                    }
                    d.set("rows", kept);
                    d.put("truncated", "共 " + rows.size() + " 行，仅返回前 " + MAX_ROWS + " 行");
                }
            }
            return capOutput(MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            return capOutput(resp);
        }
    }

    // ==================== 工具：自然语言直问 ====================

    @Tool(name = "ontology_ask", readOnly = true, concurrencySafe = true,
            description = "自然语言问题直问本体（服务端自动走 schema→graph→profile 完整流程）。"
                    + "适合简单事实问题；复杂统计或多步排查请改用 ontology_schema + graph_query 组合。"
                    + "示例：question=Place 有多少节点")
    public String ontologyAsk(
            RuntimeContext ctx,
            @ToolParam(name = "question", description = "自然语言问题") String question) {
        if (question == null || question.isBlank()) {
            return "[参数错误] question 不能为空";
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("question", question.trim());
        return postAndPassThrough("/api/llm/ontology-query/ask", body.toString());
    }

    // ==================== 工具：文件兜底 ====================

    @Tool(name = "list_uploaded_files", readOnly = true, concurrencySafe = true,
            description = "列出知识库已上传文件（name/description/path 等）。"
                    + "图谱证据不足进入文件兜底时必须先调用本工具，只有此处返回的文件才能作为 file 证据；"
                    + "随后用 grep_files 搜索关键词、read_file 分页读取命中内容。")
    public String listUploadedFiles(RuntimeContext ctx) {
        return getAndPassThrough("/api/llm/files");
    }

    // ==================== 工具：构建背景 ====================

    @Tool(name = "get_profile", readOnly = true, concurrencySafe = true,
            description = "查询本体构建背景 profile（domain/purpose/data_source/concept_design/extract_rules/final_report 等）。"
                    + "仅在用户询问构建背景且图谱证据不足时作为兜底使用。field 传空串返回全部字段。")
    public String getProfile(
            RuntimeContext ctx,
            @ToolParam(name = "field", required = false,
                    description = "字段名如 data_source；空串=返回全部") String field) {
        String resp = getAndPassThrough("/api/llm/profile");
        if (resp.startsWith("[")) {
            return resp;
        }
        if (field == null || field.isBlank()) {
            return resp;
        }
        try {
            JsonNode root = MAPPER.readTree(resp);
            JsonNode wanted = root.path("data").path(field.trim());
            if (wanted.isMissingNode()) {
                return "[参数错误] profile 中不存在字段 " + field + "（可用字段见 get_profile(\"\") 返回）";
            }
            return capOutput(wanted.toString());
        } catch (Exception e) {
            return capOutput(resp);
        }
    }

    // ==================== HTTP 基础设施 ====================

    private String getAndPassThrough(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return send(req);
    }

    private String postAndPassThrough(String path, String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(req);
    }

    private String post(String path, String jsonBody) {
        return postAndPassThrough(path, jsonBody);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "[服务错误] HTTP " + resp.statusCode() + "：" + clip(resp.body(), 500);
            }
            String body = resp.body();
            // 信封 code != 0：统一转为服务层错误文案
            try {
                JsonNode root = MAPPER.readTree(body);
                int code = root.path("code").asInt(0);
                if (code != 0) {
                    return "[服务错误] " + root.path("msg").asText(body);
                }
            } catch (Exception ignore) {
                // 非 JSON 响应按原文返回
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
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n[输出超长，已截断，共 " + s.length() + " 字符]";
    }

    private String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
