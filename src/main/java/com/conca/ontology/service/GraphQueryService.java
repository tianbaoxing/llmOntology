package com.conca.ontology.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.EagerResult;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.ClassDef;
import com.conca.ontology.model.GraphSchema;
import com.conca.ontology.model.PropertyDef;

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

    /** 分页列出某 label 的实例（key + 全属性，供管理页实例列表） */
    public Map<String, Object> nodesPage(String label, int page, int size) {
        schema.checkLabel(label);
        String keyProp = schema.keyPropOf(label);
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 200));
        Map<String, Object> result = query(
                "MATCH (n:`" + label + "`) WITH count(n) AS total "
                        + "MATCH (n:`" + label + "`) "
                        + "RETURN total AS total, n.`" + keyProp + "` AS key, properties(n) AS props "
                        + "ORDER BY key SKIP $skip LIMIT $limit",
                Map.of("skip", (p - 1) * s, "limit", s));
        List<?> rows = (List<?>) result.get("rows");
        long total = rows.isEmpty() ? 0L
                : ((Number) ((Map<?, ?>) rows.get(0)).get("total")).longValue();
        return Map.of("label", label, "keyProp", keyProp, "total", total,
                "page", p, "size", s, "pages", (total + s - 1) / s, "rows", rows);
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
        return neighbors(label, key, depth, 0, 500, null, null, null, null);
    }

    /**
     * 分页邻域：skip/limit 作用于按 eid 排序去重后的边，total 为边总数（图浏览页增量加载防卡顿）。
     * 过滤参数（dir/relType/otherLabel/f）任一存在即进入过滤分支：固定单跳 + 按分组方向/类型/对端属性筛选。
     */
    public Map<String, Object> neighbors(String label, String key, int depth, int skip, int limit,
                                         String dir, String relType, String otherLabel, List<String> filters) {
        boolean hasFilter = dir != null || relType != null || otherLabel
                != null || (filters != null && !filters.isEmpty());
        if (hasFilter) {
            if (dir == null || relType == null || otherLabel == null) {
                throw new ApiException(400, "filtered neighbors require dir, relType and otherLabel together");
            }
            return neighborsFiltered(label, key, dir, relType, otherLabel, filters, skip, limit);
        }
        String keyProp = schema.keyPropOf(label);
        int d = Math.max(1, Math.min(depth, 3));
        int sk = Math.max(0, skip);
        int lm = Math.max(1, Math.min(limit, 500));
        Map<String, Object> start = query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) "
                        + "RETURN elementId(n) AS eid, labels(n) AS labels, properties(n) AS props",
                Map.of("key", key));
        if (((List<?>) start.get("rows")).isEmpty()) {
            throw new ApiException(404, "node not found: " + label + "/" + key);
        }
        String match = "MATCH (n:`" + label + "` {`" + keyProp + "`: $key})-[r*1.." + d + "]-(m) "
                + "UNWIND r AS e ";
        Map<String, Object> total = query(match + "RETURN count(DISTINCT e) AS total", Map.of("key", key));
        long totalCount = ((Number) ((Map<?, ?>) ((List<?>) total.get("rows")).get(0)).get("total")).longValue();
        Map<String, Object> edges = query(
                match + "RETURN DISTINCT elementId(startNode(e)) AS fromEid, "
                        + "labels(startNode(e)) AS fromLabels, properties(startNode(e)) AS fromProps, "
                        + "elementId(endNode(e)) AS toEid, "
                        + "labels(endNode(e)) AS toLabels, properties(endNode(e)) AS toProps, "
                        + "type(e) AS relType, properties(e) AS relProps "
                        + "ORDER BY fromEid, relType, toEid SKIP $skip LIMIT $limit",
                Map.of("key", key, "skip", sk, "limit", lm));
        return Map.of("start", ((List<?>) start.get("rows")).get(0),
                "edges", edges.get("rows"), "total", totalCount, "skip", sk, "limit", lm);
    }

    /** 过滤条件（解析并类型化后） */
    private record FilterCond(String prop, String op, Object typedValue) {
    }

    /**
     * 邻居分组全局统计（不分页）：按 方向×关系类型×对端标签 聚合边数。
     * graph.html 分组表数据源，修正"仅当前页 100 条边统计"的数量失真问题。
     */
    public Map<String, Object> neighborGroups(String label, String key) {
        String keyProp = schema.keyPropOf(label);
        Map<String, Object> start = query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) RETURN elementId(n) AS eid",
                Map.of("key", key));
        if (((List<?>) start.get("rows")).isEmpty()) {
            throw new ApiException(404, "node not found: " + label + "/" + key);
        }
        Map<String, Object> groups = query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key})-[r]-(m) "
                        + "RETURN CASE WHEN elementId(startNode(r)) = elementId(n) THEN 'out' ELSE 'in' END AS dir, "
                        + "type(r) AS relType, labels(m)[0] AS otherLabel, count(*) AS count "
                        + "ORDER BY count DESC",
                Map.of("key", key));
        return Map.of("groups", groups.get("rows"));
    }

    /**
     * 过滤版邻域（固定单跳）：按分组（方向+关系类型+对端标签）缩小范围，再按对端节点属性筛选。
     * 安全：label/relType/属性名均过 GraphSchema 白名单，值全参数绑定。
     */
    private Map<String, Object> neighborsFiltered(String label, String key, String dir, String relType,
                                                  String otherLabel, List<String> filters, int skip, int limit) {
        if (!"in".equals(dir) && !"out".equals(dir)) {
            throw new ApiException(400, "dir must be in|out: " + dir);
        }
        schema.checkLabel(label);
        schema.checkLabel(otherLabel);
        // 签名按方向换位校验：in → (otherLabel)-[r]->(label)；out → (label)-[r]->(otherLabel)
        String from = "out".equals(dir) ? label : otherLabel;
        String to = "out".equals(dir) ? otherLabel : label;
        schema.checkRelSignature(relType, from, to);

        List<FilterCond> conds = parseFilters(otherLabel, filters);

        String keyProp = schema.keyPropOf(label);
        Map<String, Object> start = query(
                "MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) "
                        + "RETURN elementId(n) AS eid, labels(n) AS labels, properties(n) AS props",
                Map.of("key", key));
        if (((List<?>) start.get("rows")).isEmpty()) {
            throw new ApiException(404, "node not found: " + label + "/" + key);
        }
        int sk = Math.max(0, skip);
        int lm = Math.max(1, Math.min(limit, 500));

        // 方向化 MATCH + 属性 WHERE（值全参数绑定）
        StringBuilder match = new StringBuilder("out".equals(dir)
                ? "MATCH (n:`" + label + "` {`" + keyProp + "`: $key})-[r:`" + relType + "`]->(m:`" + otherLabel + "`)"
                : "MATCH (m:`" + otherLabel + "`)-[r:`" + relType + "`]->(n:`" + label + "` {`" + keyProp + "`: $key})");
        Map<String, Object> params = new HashMap<>();
        params.put("key", key);
        for (int i = 0; i < conds.size(); i++) {
            FilterCond c = conds.get(i);
            match.append(i == 0 ? " WHERE " : " AND ");
            String v = "v" + i;
            if ("like".equals(c.op())) {
                match.append("m.`").append(c.prop()).append("` CONTAINS $").append(v);
            } else {
                match.append("m.`").append(c.prop()).append("` = $").append(v);
            }
            params.put(v, c.typedValue());
        }

        Map<String, Object> total = query(match + " RETURN count(r) AS total", params);
        long totalCount = ((Number) ((Map<?, ?>) ((List<?>) total.get("rows")).get(0)).get("total")).longValue();
        Map<String, Object> edges = query(
                match + " RETURN elementId(startNode(r)) AS fromEid, "
                        + "labels(startNode(r)) AS fromLabels, properties(startNode(r)) AS fromProps, "
                        + "elementId(endNode(r)) AS toEid, "
                        + "labels(endNode(r)) AS toLabels, properties(endNode(r)) AS toProps, "
                        + "type(r) AS relType, properties(r) AS relProps "
                        + "ORDER BY fromEid, relType, toEid SKIP $skip LIMIT $limit",
                concat(params, Map.of("skip", sk, "limit", lm)));
        return Map.of("start", ((List<?>) start.get("rows")).get(0),
                "edges", edges.get("rows"), "total", totalCount, "skip", sk, "limit", lm);
    }

    /** 解析 f=prop:op:value（value 可含冒号）；属性必须声明于 otherLabel 的 schema，op 白名单 eq/like */
    private List<FilterCond> parseFilters(String otherLabel, List<String> filters) {
        ClassDef cls = schema.classOf(otherLabel);
        if (cls == null) {
            throw new ApiException(400, "unknown label: " + otherLabel);
        }
        List<FilterCond> conds = new ArrayList<>();
        for (String f : filters == null ? List.<String>of() : filters) {
            if (f == null || f.isBlank()) {
                continue;
            }
            int i1 = f.indexOf(':');
            int i2 = f.indexOf(':', i1 + 1);
            if (i1 < 0 || i2 < 0) {
                throw new ApiException(400, "invalid filter (expect prop:op:value): " + f);
            }
            String prop = f.substring(0, i1);
            String op = f.substring(i1 + 1, i2);
            String value = f.substring(i2 + 1);
            PropertyDef def = cls.properties().stream()
                    .filter(p -> p.name().equals(prop)).findFirst()
                    .orElseThrow(() -> new ApiException(400,
                            "unknown property '" + prop + "' on " + otherLabel));
            boolean isLike = "like".equals(op);
            if (!isLike && !"eq".equals(op)) {
                throw new ApiException(400, "invalid op (expect eq|like): " + op);
            }
            if ("number".equals(def.dataType())) {
                if (isLike) {
                    throw new ApiException(400, "like is not allowed on number property: " + prop);
                }
                try {
                    conds.add(new FilterCond(prop, op, Double.parseDouble(value)));
                } catch (NumberFormatException e) {
                    throw new ApiException(400, "not a number for " + prop + ": " + value);
                }
            } else {
                conds.add(new FilterCond(prop, op, value));
            }
        }
        if (conds.isEmpty()) {
            throw new ApiException(400, "empty filters");
        }
        return conds;
    }

    private static Map<String, Object> concat(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> m = new HashMap<>(a);
        m.putAll(b);
        return m;
    }
}
