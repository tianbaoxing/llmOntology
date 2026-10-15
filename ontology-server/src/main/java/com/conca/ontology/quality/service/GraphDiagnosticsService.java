package com.conca.ontology.quality.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.conca.ontology.graph.dto.view.GraphStatsView;
import com.conca.ontology.graph.dto.result.QueryResult;
import com.conca.ontology.graph.model.ClassDef;
import com.conca.ontology.graph.model.GraphSchema;
import com.conca.ontology.graph.model.RelationDef;
import com.conca.ontology.graph.service.GraphQueryService;
import com.conca.ontology.quality.dto.view.ClassBin;
import com.conca.ontology.quality.dto.view.ClassHistogramView;
import com.conca.ontology.quality.dto.view.DanglingEdgeView;
import com.conca.ontology.quality.dto.view.DegreeAnomalyView;
import com.conca.ontology.quality.dto.view.IsolatedNodeView;
import com.conca.ontology.quality.dto.view.QualityReportView;
import com.conca.ontology.quality.dto.view.RelBin;
import com.conca.ontology.quality.dto.view.RelationHistogramView;
import com.conca.ontology.quality.dto.view.SchemaCoverageView;
import com.conca.ontology.quality.dto.view.SelfLoopView;
import com.conca.ontology.quality.dto.view.TopologyAnomalyView;
import com.conca.ontology.quality.dto.view.UnusedClassView;
import com.conca.ontology.quality.dto.view.UnusedRelationView;
import com.conca.ontology.graph.model.GraphSchema.RelSignature;

/**
 * 图谱质量诊断服务：拓扑异常检查、Schema 覆盖率、分布直方图。
 * 全部查询走 GraphQueryService.query()，受只读红线保护（WRITE_RE 拒绝写语句）。
 *
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Service
public class GraphDiagnosticsService {

    private static final int DEFAULT_ISOLATED_LIMIT = 500;
    private static final int DEFAULT_DEGREE_TOP_K = 20;
    private static final int DEGREE_STATS_MIN_NODES = 50;
    private static final double DEGREE_ANOMALY_SIGMA = 3.0;

    private final GraphQueryService qs;
    private final GraphSchema schema;

    public GraphDiagnosticsService(GraphQueryService qs, GraphSchema schema) {
        this.qs = qs;
        this.schema = schema;
    }

    // ============================================================
    // 对外主入口
    // ============================================================

    public QualityReportView fullReport() {
        GraphStatsView stats = qs.stats();
        long totalNodes = stats.nodes().stream().mapToLong(GraphStatsView.LabelCount::count).sum();
        long totalRels = stats.relationships().stream().mapToLong(GraphStatsView.TypeCount::count).sum();
        return new QualityReportView(
                System.currentTimeMillis(),
                totalNodes,
                totalRels,
                schema.allClasses().size(),
                schema.allRelations().size(),
                topologyAnomaly(DEFAULT_DEGREE_TOP_K, DEFAULT_ISOLATED_LIMIT),
                schemaCoverage(stats),
                classHistogram(stats, totalNodes),
                relationHistogram(stats, totalRels));
    }

    // ============================================================
    // 分模块 API
    // ============================================================

    public TopologyAnomalyView topologyAnomaly(int topK, int isolatedLimit) {
        long isoCnt = countIsolated();
        List<IsolatedNodeView> isoList = isolatedList(Math.max(1, isolatedLimit));
        List<SelfLoopView> loops = selfLoops();
        long dangling = countDangling();
        long danglingNoOutCnt = countDanglingNoOut();
        long danglingNoInCnt = countDanglingNoIn();
        List<DanglingEdgeView> danglingNoOutList = danglingNoOutList(5);
        List<DanglingEdgeView> danglingNoInList = danglingNoInList(5);

        Map<String, double[]> degStats = degreeStats();
        List<DegreeAnomalyView> inTop = new ArrayList<>();
        List<DegreeAnomalyView> outTop = new ArrayList<>();
        long inAnomCnt = 0, outAnomCnt = 0;

        for (Map.Entry<String, double[]> e : degStats.entrySet()) {
            String label = e.getKey();
            double[] v = e.getValue(); // nCnt, avgOut, stdOut, avgIn, stdIn
            double avgOut = v[1], stdOut = v[2], avgIn = v[3], stdIn = v[4];
            int k = Math.max(1, topK);

            List<DegreeAnomalyView> curIn = degreeAnomalies(label, false, avgIn, stdIn, k);
            List<DegreeAnomalyView> curOut = degreeAnomalies(label, true, avgOut, stdOut, k);
            inAnomCnt += countAnomalies(label, false, avgIn, stdIn);
            outAnomCnt += countAnomalies(label, true, avgOut, stdOut);
            inTop.addAll(curIn);
            outTop.addAll(curOut);
        }

        inTop.sort((a, b) -> Long.compare(b.degree(), a.degree()));
        outTop.sort((a, b) -> Long.compare(b.degree(), a.degree()));
        int k = Math.max(1, topK);
        if (inTop.size() > k) inTop = inTop.subList(0, k);
        if (outTop.size() > k) outTop = outTop.subList(0, k);

        return new TopologyAnomalyView(
                isoCnt, loops.size(), dangling,
                inAnomCnt, outAnomCnt,
                danglingNoOutCnt, danglingNoInCnt,
                isoList, loops, danglingNoOutList, danglingNoInList, inTop, outTop);
    }

    public SchemaCoverageView schemaCoverage() {
        return schemaCoverage(qs.stats());
    }

    public ClassHistogramView classHistogram() {
        GraphStatsView stats = qs.stats();
        long total = stats.nodes().stream().mapToLong(GraphStatsView.LabelCount::count).sum();
        return classHistogram(stats, total);
    }

    public RelationHistogramView relationHistogram() {
        GraphStatsView stats = qs.stats();
        long total = stats.relationships().stream().mapToLong(GraphStatsView.TypeCount::count).sum();
        return relationHistogram(stats, total);
    }

    // ============================================================
    // 模块一：拓扑异常
    // ============================================================

    private long countIsolated() {
        QueryResult r = qs.query(
                "MATCH (n) WHERE NOT (n)--() RETURN count(n) AS cnt", null);
        return r.rows().isEmpty() ? 0L : ((Number) r.rows().get(0).get("cnt")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<IsolatedNodeView> isolatedList(int limit) {
        QueryResult r = qs.query(
                "MATCH (n) WHERE NOT (n)--() "
                        + "WITH labels(n)[0] AS label, n "
                        + "WITH label, n ORDER BY label ASC "
                        + "SKIP 0 LIMIT $limit "
                        + "RETURN label AS label, elementId(n) AS elementId, properties(n) AS props "
                        + "ORDER BY label ASC",
                Map.of("limit", limit));
        List<IsolatedNodeView> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            String label = (String) row.get("label");
            String eid = (String) row.get("elementId");
            Map<String, Object> props = (Map<String, Object>) row.getOrDefault("props", Map.of());
            String keyProp = schema.classOf(label) == null ? "" : schema.keyPropOf(label);
            Object keyValue = keyProp.isEmpty() ? null : props.get(keyProp);
            out.add(new IsolatedNodeView(label, keyProp, keyValue, displayName(label, props, keyProp, keyValue), eid));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<SelfLoopView> selfLoops() {
        QueryResult r = qs.query(
                "MATCH (n)-[r]->(n) "
                        + "RETURN labels(n)[0] AS label, type(r) AS relType, "
                        + "elementId(n) AS elementId, properties(n) AS nodeProps, properties(r) AS relProps "
                        + "ORDER BY label, relType", null);
        List<SelfLoopView> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            String label = (String) row.get("label");
            String relType = (String) row.get("relType");
            String eid = (String) row.get("elementId");
            Map<String, Object> nProps = (Map<String, Object>) row.getOrDefault("nodeProps", Map.of());
            Map<String, Object> rProps = (Map<String, Object>) row.getOrDefault("relProps", Map.of());
            String keyProp = schema.classOf(label) == null ? "" : schema.keyPropOf(label);
            Object keyValue = keyProp.isEmpty() ? null : nProps.get(keyProp);
            out.add(new SelfLoopView(label, relType, keyProp, keyValue,
                    displayName(label, nProps, keyProp, keyValue), eid, rProps));
        }
        return out;
    }

    /**
     * 保守检测「一端悬空」连接：关系 r 的起点 s 或终点 m 在该方向上是叶子（无继续扩展）。
     * 这不是 Neo4j 真实悬挂边（物理上不存在），而是业务意义上的「死胡同边」。
     *
     * Neo4j 5.x 语法变更：size((n)-[]->()) 模式表达式已废弃，必须用 COUNT { ... } 子查询。
     */
    private long countDangling() {
        QueryResult r = qs.query(
                "MATCH (s)-[r]->(m) "
                        + "WITH r, s, m, "
                        + "  COUNT { (m)-[]->() } AS mOut, "
                        + "  COUNT { ()-[]->(s) } AS sIn "
                        + "WHERE mOut = 0 OR sIn = 0 "
                        + "RETURN count(DISTINCT r) AS cnt", null);
        return r.rows().isEmpty() ? 0L : ((Number) r.rows().get(0).get("cnt")).longValue();
    }

    private long countDanglingNoOut() {
        QueryResult r = qs.query(
                "MATCH (s)-[r]->(m) "
                        + "WHERE COUNT { (m)-[]->() } = 0 "
                        + "RETURN count(DISTINCT r) AS cnt", null);
        return r.rows().isEmpty() ? 0L : ((Number) r.rows().get(0).get("cnt")).longValue();
    }

    private long countDanglingNoIn() {
        QueryResult r = qs.query(
                "MATCH (s)-[r]->(m) "
                        + "WHERE COUNT { ()-[]->(s) } = 0 "
                        + "RETURN count(DISTINCT r) AS cnt", null);
        return r.rows().isEmpty() ? 0L : ((Number) r.rows().get(0).get("cnt")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<DanglingEdgeView> danglingNoOutList(int limit) {
        QueryResult r = qs.query(
                "MATCH (s)-[r]->(m) "
                        + "WHERE COUNT { (m)-[]->() } = 0 "
                        + "WITH s, r, m "
                        + "ORDER BY labels(s)[0] ASC, type(r) ASC "
                        + "SKIP 0 LIMIT $limit "
                        + "RETURN labels(s)[0] AS fromLabel, labels(m)[0] AS toLabel, type(r) AS relType, "
                        + "elementId(s) AS fromEid, properties(s) AS fromProps, "
                        + "elementId(m) AS toEid, properties(m) AS toProps, "
                        + "properties(r) AS relProps",
                Map.of("limit", Math.max(1, limit)));
        return buildDanglingEdges(r);
    }

    @SuppressWarnings("unchecked")
    private List<DanglingEdgeView> danglingNoInList(int limit) {
        QueryResult r = qs.query(
                "MATCH (s)-[r]->(m) "
                        + "WHERE COUNT { ()-[]->(s) } = 0 "
                        + "WITH s, r, m "
                        + "ORDER BY labels(s)[0] ASC, type(r) ASC "
                        + "SKIP 0 LIMIT $limit "
                        + "RETURN labels(s)[0] AS fromLabel, labels(m)[0] AS toLabel, type(r) AS relType, "
                        + "elementId(s) AS fromEid, properties(s) AS fromProps, "
                        + "elementId(m) AS toEid, properties(m) AS toProps, "
                        + "properties(r) AS relProps",
                Map.of("limit", Math.max(1, limit)));
        return buildDanglingEdges(r);
    }

    @SuppressWarnings("unchecked")
    private List<DanglingEdgeView> buildDanglingEdges(QueryResult r) {
        List<DanglingEdgeView> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            String fromLabel = (String) row.get("fromLabel");
            String toLabel = (String) row.get("toLabel");
            String relType = (String) row.get("relType");
            String fromEid = (String) row.get("fromEid");
            String toEid = (String) row.get("toEid");
            Map<String, Object> fromProps = (Map<String, Object>) row.getOrDefault("fromProps", Map.of());
            Map<String, Object> toProps = (Map<String, Object>) row.getOrDefault("toProps", Map.of());
            Map<String, Object> relProps = (Map<String, Object>) row.getOrDefault("relProps", Map.of());

            String fromKeyProp = schema.classOf(fromLabel) == null ? "" : schema.keyPropOf(fromLabel);
            Object fromKeyValue = fromKeyProp.isEmpty() ? null : fromProps.get(fromKeyProp);
            String toKeyProp = schema.classOf(toLabel) == null ? "" : schema.keyPropOf(toLabel);
            Object toKeyValue = toKeyProp.isEmpty() ? null : toProps.get(toKeyProp);

            out.add(new DanglingEdgeView(
                    fromLabel, toLabel, relType,
                    fromKeyProp, fromKeyValue,
                    displayName(fromLabel, fromProps, fromKeyProp, fromKeyValue), fromEid,
                    toKeyProp, toKeyValue,
                    displayName(toLabel, toProps, toKeyProp, toKeyValue), toEid,
                    relProps));
        }
        return out;
    }

    /**
     * 每类的度统计：返回 label -> {nCnt, avgOut, stdOut, avgIn, stdIn}
     * 仅对 节点数 >= DEGREE_STATS_MIN_NODES 的类做统计，小样本无意义。
     */
    private Map<String, double[]> degreeStats() {
        QueryResult r = qs.query(
                "MATCH (n) "
                        + "WITH labels(n)[0] AS label, "
                        + "  COUNT { (n)-[]->() } AS outDeg, "
                        + "  COUNT { ()-[]->(n) } AS inDeg "
                        + "WITH label, count(*) AS nCnt, "
                        + "avg(toFloat(outDeg)) AS avgOut, stdev(toFloat(outDeg)) AS stdOut, "
                        + "avg(toFloat(inDeg)) AS avgIn, stdev(toFloat(inDeg)) AS stdIn "
                        + "WHERE nCnt >= $min "
                        + "RETURN label, nCnt, avgOut, stdOut, avgIn, stdIn "
                        + "ORDER BY nCnt DESC",
                Map.of("min", DEGREE_STATS_MIN_NODES));
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Map<String, Object> row : r.rows()) {
            String label = (String) row.get("label");
            double nCnt = num(row.get("nCnt"));
            double avgOut = num(row.get("avgOut"));
            double stdOut = num(row.get("stdOut"));
            double avgIn = num(row.get("avgIn"));
            double stdIn = num(row.get("stdIn"));
            m.put(label, new double[]{nCnt, avgOut, stdOut, avgIn, stdIn});
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<DegreeAnomalyView> degreeAnomalies(String label, boolean isOut,
                                                     double avg, double std, int topK) {
        if (!GraphSchema.isValidIdentifier(label)) return List.of();
        double threshold = avg + DEGREE_ANOMALY_SIGMA * (std > 0 ? std : Math.max(1.0, avg * 0.5));
        String degCount = isOut ? "COUNT { (n)-[]->() }" : "COUNT { ()-[]->(n) }";
        QueryResult r = qs.query(
                "MATCH (n:`" + label + "`) "
                        + "WITH n, " + degCount + " AS degree "
                        + "WHERE degree > toInteger($threshold) "
                        + "WITH n, degree ORDER BY degree DESC LIMIT $k "
                        + "RETURN elementId(n) AS elementId, properties(n) AS props, degree AS degree",
                Map.of("threshold", Math.max(1.0, Math.floor(threshold)), "k", topK));
        List<DegreeAnomalyView> out = new ArrayList<>(r.rows().size());
        String keyProp = schema.classOf(label) == null ? "" : schema.keyPropOf(label);
        for (Map<String, Object> row : r.rows()) {
            String eid = (String) row.get("elementId");
            Map<String, Object> props = (Map<String, Object>) row.getOrDefault("props", Map.of());
            long degree = ((Number) row.get("degree")).longValue();
            double dev = (std > 0) ? (degree - avg) / std : (degree - avg) / Math.max(1.0, avg);
            Object keyValue = keyProp.isEmpty() ? null : props.get(keyProp);
            out.add(new DegreeAnomalyView(label, keyProp, keyValue,
                    displayName(label, props, keyProp, keyValue), eid, degree, round2(dev)));
        }
        return out;
    }

    private long countAnomalies(String label, boolean isOut, double avg, double std) {
        if (!GraphSchema.isValidIdentifier(label)) return 0L;
        double threshold = avg + DEGREE_ANOMALY_SIGMA * (std > 0 ? std : Math.max(1.0, avg * 0.5));
        String degCount = isOut ? "COUNT { (n)-[]->() }" : "COUNT { ()-[]->(n) }";
        QueryResult r = qs.query(
                "MATCH (n:`" + label + "`) "
                        + "WHERE " + degCount + " > toInteger($threshold) "
                        + "RETURN count(n) AS cnt",
                Map.of("threshold", Math.max(1.0, Math.floor(threshold))));
        return r.rows().isEmpty() ? 0L : ((Number) r.rows().get(0).get("cnt")).longValue();
    }

    // ============================================================
    // 模块二：Schema 覆盖率
    // ============================================================

    private SchemaCoverageView schemaCoverage(GraphStatsView stats) {
        Set<String> liveLabels = stats.nodes().stream()
                .filter(lc -> lc.count() > 0)
                .map(GraphStatsView.LabelCount::label)
                .collect(Collectors.toSet());
        Set<String> liveTypes = stats.relationships().stream()
                .filter(tc -> tc.count() > 0)
                .map(GraphStatsView.TypeCount::type)
                .collect(Collectors.toSet());

        Collection<ClassDef> allClasses = schema.allClasses();
        Collection<RelSignature> allRels = schema.allRelations();

        int totalCls = allClasses.size();
        int usedCls = 0;
        List<UnusedClassView> unusedCls = new ArrayList<>();
        for (ClassDef c : allClasses) {
            if (liveLabels.contains(c.name())) usedCls++;
            else unusedCls.add(new UnusedClassView(c.name(), c.cnName(), c.layer(), c.keyProp(), c.description()));
        }

        int totalRel = allRels.size();
        int usedRel = 0;
        List<UnusedRelationView> unusedRels = new ArrayList<>();
        for (RelSignature sig : allRels) {
            RelationDef d = sig.def();
            if (liveTypes.contains(d.name())) usedRel++;
            else unusedRels.add(new UnusedRelationView(
                    d.name(), d.cnName(), sig.fromClass(), d.toClass(), d.description(), d.example()));
        }

        double clsCov = totalCls == 0 ? 0.0 : round2(usedCls * 100.0 / totalCls);
        double relCov = totalRel == 0 ? 0.0 : round2(usedRel * 100.0 / totalRel);
        return new SchemaCoverageView(clsCov, relCov, unusedCls.size(), unusedRels.size(), unusedCls, unusedRels);
    }

    // ============================================================
    // 模块三：分布直方图
    // ============================================================

    private ClassHistogramView classHistogram(GraphStatsView stats, long totalNodes) {
        Map<String, ClassDef> clsMap = new HashMap<>();
        for (ClassDef c : schema.allClasses()) clsMap.put(c.name(), c);

        List<ClassBin> bins = new ArrayList<>();
        for (GraphStatsView.LabelCount lc : stats.nodes()) {
            ClassDef c = clsMap.get(lc.label());
            bins.add(new ClassBin(
                    lc.label(),
                    c == null ? "" : c.cnName(),
                    c == null ? "" : c.layer(),
                    lc.count(),
                    totalNodes == 0 ? 0.0 : round2(lc.count() * 100.0 / totalNodes)));
        }
        // 补全声明但零实例的类（也在直方图里，高度为 0）
        for (ClassDef c : schema.allClasses()) {
            boolean found = stats.nodes().stream().anyMatch(n -> n.label().equals(c.name()));
            if (!found) {
                bins.add(new ClassBin(c.name(), c.cnName(), c.layer(), 0L, 0.0));
            }
        }
        bins.sort((a, b) -> Long.compare(b.count(), a.count()));
        long[] counts = bins.stream().mapToLong(ClassBin::count).sorted().toArray();
        return new ClassHistogramView(bins,
                counts.length == 0 ? 0 : counts[0],
                counts.length == 0 ? 0 : counts[counts.length - 1],
                counts.length == 0 ? 0 : round2(avg(counts)),
                counts.length == 0 ? 0 : median(counts));
    }

    private RelationHistogramView relationHistogram(GraphStatsView stats, long totalRels) {
        Map<String, RelSignature> relMap = new HashMap<>();
        for (RelSignature s : schema.allRelations()) relMap.put(s.type(), s);

        // 把 live 的 type -> count 先建索引（方便后补 0）
        Map<String, Long> liveCounts = new HashMap<>();
        for (GraphStatsView.TypeCount tc : stats.relationships()) liveCounts.put(tc.type(), tc.count());

        Set<String> covered = new HashSet<>();
        List<RelBin> bins = new ArrayList<>();
        for (GraphStatsView.TypeCount tc : stats.relationships()) {
            RelSignature sig = relMap.get(tc.type());
            String from = sig == null ? "" : sig.fromClass();
            String to = sig == null ? "" : sig.toClass();
            String cn = sig == null ? "" : sig.def().cnName();
            bins.add(new RelBin(tc.type(), cn, from, to, tc.count(),
                    totalRels == 0 ? 0.0 : round2(tc.count() * 100.0 / totalRels)));
            covered.add(tc.type());
        }
        for (RelSignature sig : schema.allRelations()) {
            if (covered.contains(sig.type())) continue;
            long cnt = liveCounts.getOrDefault(sig.type(), 0L);
            bins.add(new RelBin(
                    sig.type(), sig.def().cnName(), sig.fromClass(), sig.toClass(), cnt, 0.0));
        }
        bins.sort((a, b) -> Long.compare(b.count(), a.count()));
        long[] counts = bins.stream().mapToLong(RelBin::count).sorted().toArray();
        return new RelationHistogramView(bins,
                counts.length == 0 ? 0 : counts[0],
                counts.length == 0 ? 0 : counts[counts.length - 1],
                counts.length == 0 ? 0 : round2(avg(counts)),
                counts.length == 0 ? 0 : median(counts));
    }

    // ============================================================
    // 工具
    // ============================================================

    private String displayName(String label, Map<String, Object> props, String keyProp, Object keyValue) {
        List<String> pref = List.of("title", "name", "cnName", "display", "displayName");
        for (String k : pref) {
            Object v = props.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        if (keyValue != null && !keyValue.toString().isBlank()) return keyValue.toString();
        return (keyProp != null && !keyProp.isBlank())
                ? (label + "#" + keyProp)
                : label;
    }

    private static double num(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (Exception e) { return 0.0; }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double avg(long[] sorted) {
        if (sorted.length == 0) return 0.0;
        long sum = 0;
        for (long x : sorted) sum += x;
        return (double) sum / sorted.length;
    }

    private static long median(long[] sorted) {
        if (sorted.length == 0) return 0;
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) return sorted[mid];
        return (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
