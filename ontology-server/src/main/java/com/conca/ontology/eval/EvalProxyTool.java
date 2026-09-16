package com.conca.ontology.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.conca.ontology.agent.HttpProxyTool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * Agentic 取证评测专用的只读 HTTP 管道（三期模块一）：复用主问答 agent 同款
 * HttpProxyTool 通道（/api/llm/ 只读平面 + 服务端红线双保险），叠加两个评测诉求：
 * 1. 成本控制——单样本取证查询次数上限（app.eval.agent.max-queries），超限返回
 *    [参数错误] 确定性文案，agent 停止取证按已获证据判分（对齐设计方案 6.1）；
 * 2. 证据留痕——记录每次实际调用（path/body/返回摘要）供结果 metadata 回查，
 *    使 evidence 中自报的 Cypher 可与实际调用对照核查（验收 C2）。
 *
 * 计数与记录为实例状态：评测 worker 单线程顺序执行样本，由 grader 在每次
 * 判分前 reset()，判分后读取 records()/queryCount()。
 */
public class EvalProxyTool {

    private static final int RECORD_RESULT_CHARS = 300;

    private final HttpProxyTool proxy;
    private final int maxQueries;
    private final AtomicInteger queryCount = new AtomicInteger();
    private final List<Map<String, Object>> records = new ArrayList<>();

    public EvalProxyTool(String baseUrl, int maxQueries) {
        this.proxy = new HttpProxyTool(baseUrl);
        this.maxQueries = maxQueries;
    }

    /** 每次判分前重置计数与调用记录 */
    public void reset() {
        queryCount.set(0);
        records.clear();
    }

    /** 本次判分实际发起的取证查询次数 */
    public int queryCount() {
        return queryCount.get();
    }

    /** 实际调用记录（只读视图，供结果 metadata 留痕） */
    public List<Map<String, Object>> records() {
        return List.copyOf(records);
    }

    @Tool(name = "http_request_ontology_query", readOnly = true, concurrencySafe = true,
            description = "向本体服务只读平面发送 HTTP 请求的取证管道。"
                    + "适用：核查断言时查询概念层、执行只读 Cypher、查看节点详情。"
                    + "path 必须以 /api/llm/ 开头；GET 传 params，POST 传 body（JSON 对象字符串）。"
                    + "示例：path=/api/llm/graph/query, method=POST, "
                    + "body={\"cypher\":\"MATCH (n:`CulturalRelic` {name:'定窑白釉莲纹大碗'})-[r]->(v) RETURN type(r), v.name\",\"params\":{}}")
    public String httpRequest(
            RuntimeContext ctx,
            @ToolParam(name = "path", description = "API 路径，必须以 /api/llm/ 开头，如 /api/llm/graph/stats") String path,
            @ToolParam(name = "method", required = false, description = "GET（默认）或 POST") String method,
            @ToolParam(name = "params", required = false,
                    description = "URL 查询参数，JSON 对象字符串；无参数不传") String params,
            @ToolParam(name = "body", required = false,
                    description = "POST 的 JSON 请求体字符串，仅 POST 时可传") String body) {
        int n = queryCount.incrementAndGet();
        if (n > maxQueries) {
            return "[参数错误] 取证查询次数已达上限（" + maxQueries + " 次）：请停止查询，"
                    + "按已获证据对剩余断言计为「未证实」并输出最终 JSON。";
        }
        String result = proxy.httpRequest(ctx, path, method, params, body);
        Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("seq", n);
        rec.put("path", path);
        rec.put("method", (method == null || method.isBlank()) ? "GET" : method.trim().toUpperCase());
        if (body != null && !body.isBlank()) {
            rec.put("body", body.length() > 300 ? body.substring(0, 300) + "…" : body);
        }
        rec.put("result", result == null ? "" :
                (result.length() > RECORD_RESULT_CHARS ? result.substring(0, RECORD_RESULT_CHARS) + "…" : result));
        records.add(rec);
        return result;
    }
}
