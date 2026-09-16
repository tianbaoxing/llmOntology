package com.conca.ontology.service.ontologyquery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.conca.ontology.dto.result.QueryResult;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.service.GraphQueryService;

/**
 * 本体查询专用只读执行入口：额外收紧通用 graph query 的使用边界，
 * 明确拒绝多语句与任何写操作关键字。
 */
@Service
public class OntologyQueryExecutionService {

    private static final Pattern WRITE_KEYWORD_RE = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|LOAD|FOREACH|CALL|USE|INSERT|UPDATE)\\b");
    private static final Pattern READ_HINT_RE = Pattern.compile("(?i)\\b(MATCH|OPTIONAL\\s+MATCH|RETURN|WITH|UNWIND)\\b");

    private final GraphQueryService graphQueryService;

    public OntologyQueryExecutionService(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    public Map<String, Object> execute(String question, String cypher, Map<String, Object> params) {
        String normalized = normalize(cypher);
        QueryResult graph = graphQueryService.query(normalized, params);
        List<?> rows = graph.rows();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("cypher", normalized);
        result.put("params", params == null ? Map.of() : params);
        result.put("keys", graph.keys());
        result.put("rows", rows);
        result.put("count", graph.count());
        result.put("found", rows != null && !rows.isEmpty());
        result.put("message", rows != null && !rows.isEmpty() ? "ok" : "当前本体中未查询到相关结果");
        return result;
    }

    public String normalize(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            throw new ApiException(400, "cypher must not be blank");
        }
        String normalized = cypher.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.contains(";")) {
            throw new ApiException(400, "only one read-only cypher statement is allowed");
        }
        if (WRITE_KEYWORD_RE.matcher(normalized).find()) {
            throw new ApiException(400, "read-only endpoint: write statement rejected");
        }
        if (!READ_HINT_RE.matcher(normalized).find()) {
            throw new ApiException(400, "cypher must be a read-only query");
        }
        return normalized;
    }
}
