package com.history.ontology2.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.EagerResult;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import com.history.ontology2.exception.ApiException;
import com.history.ontology2.model.GraphSchema;

/**
 * 只读查询封装：通用 Cypher（只读校验）、统计、节点详情、邻域。
 * 值统一经 Value.asObject() 转为 Java 友好类型（Node/Rel/Path 也转 Map/List）。
 */
@Service
public class GraphQueryService {

    /** 只读红线：拒绝任何写操作开头语句 */
    private static final Pattern WRITE_RE = Pattern.compile(
            "(?i)^\\s*(CREATE|MERGE|DELETE|DETACH\\s+DELETE|SET|REMOVE|DROP|LOAD|FOREACH|INSERT|CALL|USE)\\b.*");

    private final Driver driver;
    private final GraphSchema schema;

    public GraphQueryService(Driver driver, GraphSchema schema) {
        this.driver = driver;
        this.schema = schema;
    }

    public Map<String, Object> query(String cypher, Map<String, Object> params) {
        if (WRITE_RE.matcher(cypher).matches()) {
            throw new ApiException(400, "read-only endpoint: write statement rejected");
        }
        EagerResult result = driver.executableQuery(cypher)
                .withParameters(params == null ? Map.of() : params)
                .execute();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Record rec : result.records()) {
            Map<String, Object> row = new LinkedHashMap<>();
            rec.fields().forEach(f -> row.put(f.key(), f.value().asObject()));
            rows.add(row);
        }
        return Map.of("keys", result.keys(), "rows", rows, "count", rows.size());
    }

    public Map<String, Object> stats() {
        Map<String, Object> nodes = query(
                "MATCH (n) RETURN labels(n)[0] AS label, count(*) AS count ORDER BY label", null);
        Map<String, Object> rels = query(
                "MATCH ()-[r]->() RETURN type(r) AS type, count(*) AS count ORDER BY type", null);
        return Map.of("nodes", nodes.get("rows"), "relationships", rels.get("rows"));
    }

    public Map<String, Object> nodeDetail(String label, String key) {
        String keyProp = schema.keyPropOf(label);
        return query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) "
                        + "OPTIONAL MATCH (n)-[r]-(m) "
                        + "RETURN properties(n) AS node, labels(n) AS labels, "
                        + "type(r) AS relType, properties(r) AS relProps, "
                        + "labels(m) AS otherLabels, properties(m) AS other "
                        + "LIMIT 200",
                Map.of("key", key));
    }

    public Map<String, Object> neighbors(String label, String key, int depth) {
        String keyProp = schema.keyPropOf(label);
        int d = Math.max(1, Math.min(depth, 3));
        Map<String, Object> start = query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) "
                        + "RETURN elementId(n) AS eid, labels(n) AS labels, properties(n) AS props",
                Map.of("key", key));
        if (((List<?>) start.get("rows")).isEmpty()) {
            throw new ApiException(404, "node not found: " + label + "/" + key);
        }
        Map<String, Object> edges = query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key})-[r*1.." + d + "]-(m) "
                        + "UNWIND r AS e "
                        + "RETURN DISTINCT elementId(startNode(e)) AS fromEid, "
                        + "labels(startNode(e)) AS fromLabels, properties(startNode(e)) AS fromProps, "
                        + "elementId(endNode(e)) AS toEid, "
                        + "labels(endNode(e)) AS toLabels, properties(endNode(e)) AS toProps, "
                        + "type(e) AS relType, properties(e) AS relProps "
                        + "LIMIT 500",
                Map.of("key", key));
        return Map.of("start", ((List<?>) start.get("rows")).get(0),
                "edges", edges.get("rows"));
    }
}
