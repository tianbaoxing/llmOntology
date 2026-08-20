package com.conca.ontology.service.ontologyquery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.conca.ontology.model.ProfileEntry;
import com.conca.ontology.model.PropertyDef;
import com.conca.ontology.service.ProfileService;
import com.conca.ontology.service.SchemaService;

/**
 * 面向自然语言的本体问答服务：
 * 先看 schema，再决定是否查询图谱；图谱证据不足时回查 ontology-profile。
 */
@Service
public class OntologyAskService {

    private static final Pattern QUOTED_TEXT_RE = Pattern.compile("[\"“”'‘’《](.+?)[\"“”'‘’》]");
    private static final List<String> COUNT_WORDS = List.of("多少", "几个", "数量", "总数", "count");
    private static final List<String> LIST_WORDS = List.of("哪些", "有哪些", "列出", "列表", "清单", "样例", "示例");
    private static final List<String> EXIST_WORDS = List.of("存在", "有没有", "是否有", "有吗", "是否存在");
    private static final List<String> PROFILE_WORDS = List.of("数据来源", "来源", "文件", "构建目的", "目的", "领域", "范围", "设计", "规则", "结果", "报告");
    private static final List<String> CONCEPT_WORDS = List.of("概念", "概念层", "类", "schema", "本体", "属性", "关系", "节点类型");

    private final SchemaService schemaService;
    private final ProfileService profileService;
    private final OntologyQueryExecutionService queryExecutionService;

    public OntologyAskService(SchemaService schemaService, ProfileService profileService,
                              OntologyQueryExecutionService queryExecutionService) {
        this.schemaService = schemaService;
        this.profileService = profileService;
        this.queryExecutionService = queryExecutionService;
    }

    public Map<String, Object> ask(String question, Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        Map<String, Object> schemaView = schemaService.schemaView();
        SchemaMatch match = matchSchema(question, schemaView);

        if (isProfileQuestion(question)) {
            return answerFromProfile(question, match, schemaView);
        }
        if (isConceptQuestion(question)) {
            return answerConcept(question, match, schemaView);
        }

        Map<String, Object> graphResult = answerGraph(question, match, schemaView, safeLimit);
        if ((boolean) graphResult.get("found")) {
            return graphResult;
        }
        Map<String, Object> profileResult = answerFromProfile(question, match, schemaView);
        if ((boolean) profileResult.get("found")) {
            return profileResult;
        }

        Map<String, Object> response = baseResponse(question, match, schemaView);
        response.put("found", false);
        response.put("evidenceSource", "none");
        response.put("answer", "当前本体中不存在该信息。");
        response.put("cypher", graphResult.get("cypher"));
        response.put("graphEvidence", graphResult.get("graphEvidence"));
        response.put("profileEvidence", List.of());
        return response;
    }

    private Map<String, Object> answerConcept(String question, SchemaMatch match, Map<String, Object> schemaView) {
        Map<String, Object> response = baseResponse(question, match, schemaView);
        List<Map<String, Object>> classes = classList(schemaView);
        List<Map<String, Object>> relations = relationList(schemaView);
        List<Map<String, Object>> evidence = new ArrayList<>();
        String answer;

        if (match.classView != null) {
            Map<String, Object> classDetail = schemaService.classView(stringValue(match.classView.get("name")));
            evidence.add(classDetail);
            if (match.property != null) {
                answer = classDetail.get("cnName") + "（" + classDetail.get("name") + "）包含属性 "
                        + match.property.get("cnName") + "（" + match.property.get("name") + "）。";
            } else {
                answer = classDetail.get("cnName") + "（" + classDetail.get("name") + "）"
                        + " 的 keyProp 是 " + classDetail.get("keyProp")
                        + "，当前定义了 " + ((List<?>) classDetail.get("properties")).size()
                        + " 个属性、" + ((List<?>) classDetail.get("relations")).size() + " 个出边关系。";
            }
        } else if (match.relation != null) {
            evidence.add(match.relation);
            answer = "关系 " + match.relation.get("cnName") + "（" + match.relation.get("name") + "）"
                    + " 从 " + match.relation.get("fromClass") + " 指向 " + match.relation.get("toClass") + "。";
        } else {
            evidence.addAll(classes.stream().limit(10).toList());
            answer = "当前 schema 中有 " + classes.size() + " 个类、" + relations.size()
                    + " 个关系定义。";
        }

        response.put("found", !evidence.isEmpty());
        response.put("evidenceSource", !evidence.isEmpty() ? "schema" : "none");
        response.put("answer", !evidence.isEmpty() ? answer : "当前本体中不存在该概念信息。");
        response.put("cypher", null);
        response.put("graphEvidence", List.of());
        response.put("profileEvidence", List.of());
        response.put("schemaEvidence", evidence);
        return response;
    }

    private Map<String, Object> answerGraph(String question, SchemaMatch match,
                                            Map<String, Object> schemaView, int limit) {
        Map<String, Object> response = baseResponse(question, match, schemaView);
        String subject = extractSubject(question, match);
        boolean countQuestion = containsAny(question, COUNT_WORDS);
        boolean listQuestion = containsAny(question, LIST_WORDS);
        boolean existQuestion = containsAny(question, EXIST_WORDS);

        if (match.classView != null && subject == null && (countQuestion || listQuestion)) {
            String className = stringValue(match.classView.get("name"));
            String keyProp = stringValue(match.classView.get("keyProp"));
            String cypher = countQuestion
                    ? "MATCH (n:`" + className + "`) RETURN count(n) AS count"
                    : "MATCH (n:`" + className + "`) "
                    + "RETURN n.`" + keyProp + "` AS key, properties(n) AS props "
                    + "ORDER BY key LIMIT $limit";
            Map<String, Object> result = queryExecutionService.execute(question, cypher,
                    countQuestion ? Map.of() : Map.of("limit", limit));
            response.put("cypher", result.get("cypher"));
            response.put("graphEvidence", result.get("rows"));
            response.put("profileEvidence", List.of());
            if ((boolean) result.get("found")) {
                response.put("found", true);
                response.put("evidenceSource", "graph");
                response.put("answer", countQuestion
                        ? "当前本体中共有 " + valueFromSingleRow(result, "count") + " 个 " + className + " 节点。"
                        : "当前本体中查询到 " + result.get("count") + " 条 " + className + " 节点样例。");
                return response;
            }
            response.put("found", false);
            response.put("evidenceSource", "none");
            response.put("answer", "当前本体中不存在该类的实例数据。");
            return response;
        }

        if (subject == null) {
            response.put("found", false);
            response.put("evidenceSource", "none");
            response.put("answer", "当前问题缺少可定位的实例关键词，无法生成可靠查询。");
            response.put("cypher", null);
            response.put("graphEvidence", List.of());
            response.put("profileEvidence", List.of());
            return response;
        }

        Map<String, Object> candidate = findCandidateNode(question, subject, schemaView, limit);
        response.put("cypher", candidate.get("cypher"));
        response.put("graphEvidence", candidate.get("rows"));
        response.put("profileEvidence", List.of());
        if (!(boolean) candidate.get("found")) {
            response.put("found", false);
            response.put("evidenceSource", "none");
            response.put("answer", "当前图谱未查询到与“" + subject + "”相关的节点。");
            return response;
        }

        Map<String, Object> first = firstRow(candidate);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) first.get("props");
        String label = stringValue(first.get("label"));
        String nodeKey = displayValue(label, props, schemaView);

        if (existQuestion && match.property == null && match.relation == null && match.classView == null) {
            response.put("found", true);
            response.put("evidenceSource", "graph");
            response.put("answer", "图谱中存在与“" + subject + "”匹配的节点，命中节点为 " + nodeKey + "。");
            return response;
        }

        if (match.property != null && props.containsKey(match.property.get("name"))) {
            response.put("found", true);
            response.put("evidenceSource", "graph");
            response.put("answer", nodeKey + " 的 " + match.property.get("cnName") + " 是 "
                    + Objects.toString(props.get(match.property.get("name")), "空") + "。");
            return response;
        }

        if (match.classView != null || match.relation != null) {
            String cypher = buildNeighborCypher(match);
            Map<String, Object> graph = queryExecutionService.execute(question, cypher,
                    Map.of("eid", first.get("eid"), "limit", limit));
            response.put("cypher", graph.get("cypher"));
            response.put("graphEvidence", graph.get("rows"));
            if ((boolean) graph.get("found")) {
                response.put("found", true);
                response.put("evidenceSource", "graph");
                response.put("answer", summarizeNeighborAnswer(nodeKey, graph, match, schemaView));
                return response;
            }
        }

        response.put("found", true);
        response.put("evidenceSource", "graph");
        response.put("answer", "图谱中命中了节点 " + nodeKey + "，但没有查到更直接的关联证据。");
        return response;
    }

    private Map<String, Object> findCandidateNode(String question, String subject,
                                                  Map<String, Object> schemaView, int limit) {
        Set<String> props = searchableProps(schemaView);
        if (props.isEmpty()) {
            return Map.of("question", question, "cypher", null, "rows", List.of(), "count", 0, "found", false);
        }
        String cypher = "MATCH (n) "
                + "WHERE any(p IN $props WHERE coalesce(toString(n[p]), '') CONTAINS $keyword) "
                + "RETURN labels(n)[0] AS label, elementId(n) AS eid, properties(n) AS props "
                + "LIMIT $limit";
        return queryExecutionService.execute(question, cypher,
                Map.of("props", List.copyOf(props), "keyword", subject, "limit", limit));
    }

    private String buildNeighborCypher(SchemaMatch match) {
        if (match.classView != null && match.relation != null) {
            return "MATCH (n)-[r:`" + match.relation.get("name") + "`]-(m:`" + match.classView.get("name") + "`) "
                    + "WHERE elementId(n) = $eid "
                    + "RETURN type(r) AS relType, properties(r) AS relProps, labels(m)[0] AS label, properties(m) AS props "
                    + "LIMIT $limit";
        }
        if (match.classView != null) {
            return "MATCH (n)-[r]-(m:`" + match.classView.get("name") + "`) "
                    + "WHERE elementId(n) = $eid "
                    + "RETURN type(r) AS relType, properties(r) AS relProps, labels(m)[0] AS label, properties(m) AS props "
                    + "LIMIT $limit";
        }
        return "MATCH (n)-[r:`" + match.relation.get("name") + "`]-(m) "
                + "WHERE elementId(n) = $eid "
                + "RETURN type(r) AS relType, properties(r) AS relProps, labels(m)[0] AS label, properties(m) AS props "
                + "LIMIT $limit";
    }

    private String summarizeNeighborAnswer(String nodeKey, Map<String, Object> graph,
                                           SchemaMatch match, Map<String, Object> schemaView) {
        List<String> values = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) graph.get("rows");
        for (Map<String, Object> row : rows.stream().limit(5).toList()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) row.get("props");
            values.add(displayValue(stringValue(row.get("label")), props, schemaView));
        }
        String joined = String.join("、", values.stream().filter(Objects::nonNull).toList());
        if (joined.isBlank()) {
            joined = "相关节点";
        }
        if (match.classView != null) {
            return nodeKey + " 关联到的 " + match.classView.get("cnName") + " 有：" + joined + "。";
        }
        return nodeKey + " 关联到的 " + match.relation.get("cnName") + " 结果有：" + joined + "。";
    }

    private Map<String, Object> answerFromProfile(String question, SchemaMatch match, Map<String, Object> schemaView) {
        Map<String, Object> response = baseResponse(question, match, schemaView);
        List<Map<String, Object>> hits = searchProfile(question, match);
        response.put("cypher", null);
        response.put("graphEvidence", List.of());
        response.put("profileEvidence", hits);
        response.put("found", !hits.isEmpty());
        response.put("evidenceSource", hits.isEmpty() ? "none" : "profile");
        response.put("answer", hits.isEmpty()
                ? "当前本体画像文件中未查询到相关记录。"
                : "根据 ontology-profile 记录，相关信息如下：" + stringValue(hits.get(0).get("answer")));
        return response;
    }

    private List<Map<String, Object>> searchProfile(String question, SchemaMatch match) {
        List<Map<String, Object>> items = profileItems();
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        addKeyword(keywords, quotedText(question));
        addKeyword(keywords, match.classView == null ? null : stringValue(match.classView.get("name")));
        addKeyword(keywords, match.classView == null ? null : stringValue(match.classView.get("cnName")));
        addKeyword(keywords, match.relation == null ? null : stringValue(match.relation.get("name")));
        addKeyword(keywords, match.relation == null ? null : stringValue(match.relation.get("cnName")));
        addKeyword(keywords, match.property == null ? null : stringValue(match.property.get("name")));
        addKeyword(keywords, match.property == null ? null : stringValue(match.property.get("cnName")));

        if (containsAny(question, List.of("来源", "文件"))) {
            keywords.add("data_source");
        }
        if (containsAny(question, List.of("目的", "用途"))) {
            keywords.add("purpose");
        }
        if (containsAny(question, List.of("领域", "场景"))) {
            keywords.add("domain");
        }
        if (containsAny(question, List.of("范围", "设计", "概念"))) {
            keywords.add("concept_design");
            keywords.add("concept_scope");
        }
        if (containsAny(question, List.of("规则", "抽取"))) {
            keywords.add("extract_rules");
        }
        if (containsAny(question, List.of("结果", "报告", "统计"))) {
            keywords.add("final_report");
        }

        return items.stream()
                .map(item -> scoredProfile(item, question, keywords))
                .filter(item -> ((Integer) item.get("_score")) > 0)
                .sorted(Comparator.<Map<String, Object>, Integer>comparing(item -> (Integer) item.get("_score"))
                        .reversed())
                .limit(5)
                .map(this::stripScore)
                .toList();
    }

    private Map<String, Object> scoredProfile(Map<String, Object> item, String question, List<String> keywords) {
        String full = (stringValue(item.get("phase")) + " " + stringValue(item.get("key")) + " "
                + stringValue(item.get("question")) + " " + stringValue(item.get("answer"))).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && full.contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 2;
            }
        }
        if (full.contains(question.toLowerCase(Locale.ROOT))) {
            score += 3;
        }
        Map<String, Object> copy = new LinkedHashMap<>(item);
        copy.put("_score", score);
        return copy;
    }

    private Map<String, Object> stripScore(Map<String, Object> item) {
        Map<String, Object> copy = new LinkedHashMap<>(item);
        copy.remove("_score");
        return copy;
    }

    private SchemaMatch matchSchema(String question, Map<String, Object> schemaView) {
        Map<String, Object> classView = bestAliasMatch(question, classList(schemaView), List.of("name", "cnName"));
        List<Map<String, Object>> propScope = classView == null ? allProperties(schemaView)
                : propertyList(classView);
        Map<String, Object> property = bestAliasMatch(question, propScope, List.of("name", "cnName"));
        Map<String, Object> relation = bestAliasMatch(question, relationList(schemaView), List.of("name", "cnName"));
        return new SchemaMatch(classView, property, relation);
    }

    private Map<String, Object> bestAliasMatch(String question, List<Map<String, Object>> items, List<String> keys) {
        Map<String, Object> best = null;
        int bestScore = -1;
        for (Map<String, Object> item : items) {
            for (String key : keys) {
                String alias = stringValue(item.get(key));
                if (alias.isBlank() || !question.contains(alias)) {
                    continue;
                }
                int score = alias.length();
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
        }
        return best;
    }

    private boolean isConceptQuestion(String question) {
        return containsAny(question, CONCEPT_WORDS) && !containsAny(question, PROFILE_WORDS);
    }

    private boolean isProfileQuestion(String question) {
        return containsAny(question, PROFILE_WORDS);
    }

    private boolean containsAny(String text, List<String> keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(word -> normalized.contains(word.toLowerCase(Locale.ROOT)));
    }

    private String extractSubject(String question, SchemaMatch match) {
        String quoted = quotedText(question);
        if (quoted != null) {
            return quoted;
        }
        if (match.classView != null && (containsAny(question, COUNT_WORDS) || containsAny(question, LIST_WORDS))) {
            return null;
        }
        List<String> markers = List.of("是什么", "属于什么", "有哪些", "有多少", "多少", "几个", "是否存在", "存在吗", "的", "在哪", "在哪里");
        int cut = Integer.MAX_VALUE;
        for (String marker : markers) {
            int idx = question.indexOf(marker);
            if (idx > 0) {
                cut = Math.min(cut, idx);
            }
        }
        String candidate = cut == Integer.MAX_VALUE ? question : question.substring(0, cut);
        candidate = candidate.replace("请问", "").replace("帮我查", "").replace("帮我看看", "")
                .replace("查询", "").replace("图谱里", "").replace("本体里", "").trim();
        if (candidate.isBlank()) {
            return null;
        }
        if (match.classView != null) {
            if (candidate.equals(stringValue(match.classView.get("name")))
                    || candidate.equals(stringValue(match.classView.get("cnName")))) {
                return null;
            }
        }
        return candidate.length() < 2 ? null : candidate;
    }

    private String quotedText(String question) {
        Matcher matcher = QUOTED_TEXT_RE.matcher(question);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private Set<String> searchableProps(Map<String, Object> schemaView) {
        Set<String> props = new LinkedHashSet<>();
        for (Map<String, Object> classView : classList(schemaView)) {
            for (Map<String, Object> prop : propertyList(classView)) {
                String dataType = stringValue(prop.get("dataType"));
                if (dataType.isBlank() || "string".equalsIgnoreCase(dataType)) {
                    props.add(stringValue(prop.get("name")));
                }
            }
        }
        return props;
    }

    private String displayValue(String label, Map<String, Object> props, Map<String, Object> schemaView) {
        if (props == null || props.isEmpty()) {
            return label;
        }
        for (Map<String, Object> classView : classList(schemaView)) {
            if (!label.equals(stringValue(classView.get("name")))) {
                continue;
            }
            String keyProp = stringValue(classView.get("keyProp"));
            Object value = props.get(keyProp);
            if (value != null) {
                return value.toString();
            }
        }
        return props.values().iterator().next().toString();
    }

    private Map<String, Object> baseResponse(String question, SchemaMatch match, Map<String, Object> schemaView) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("matchedClass", match.classView);
        response.put("matchedProperty", match.property);
        response.put("matchedRelation", match.relation);
        response.put("schemaVersion", schemaView.get("version"));
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> classList(Map<String, Object> schemaView) {
        return (List<Map<String, Object>>) schemaView.getOrDefault("classes", List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> relationList(Map<String, Object> schemaView) {
        return (List<Map<String, Object>>) schemaView.getOrDefault("relations", List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> propertyList(Map<String, Object> classView) {
        List<?> raw = (List<?>) classView.getOrDefault("properties", List.of());
        List<Map<String, Object>> props = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                props.add((Map<String, Object>) map);
            } else if (item instanceof PropertyDef p) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("name", p.name());
                normalized.put("cnName", p.cnName());
                normalized.put("dataType", p.dataType());
                normalized.put("required", p.required());
                normalized.put("description", p.description());
                props.add(normalized);
            }
        }
        return props;
    }

    private List<Map<String, Object>> allProperties(Map<String, Object> schemaView) {
        List<Map<String, Object>> props = new ArrayList<>();
        for (Map<String, Object> classView : classList(schemaView)) {
            props.addAll(propertyList(classView));
        }
        return props;
    }

    private List<Map<String, Object>> profileItems() {
        List<?> raw = (List<?>) profileService.load().getOrDefault("items", List.of());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> normalized = (Map<String, Object>) map;
                items.add(normalized);
            } else if (item instanceof ProfileEntry p) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("phase", p.phase());
                normalized.put("key", p.key());
                normalized.put("question", p.question());
                normalized.put("answer", p.answer());
                normalized.put("updatedAt", p.updatedAt());
                items.add(normalized);
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRow(Map<String, Object> result) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        return rows.get(0);
    }

    private Object valueFromSingleRow(Map<String, Object> result, String key) {
        Map<String, Object> row = firstRow(result);
        return row.get(key);
    }

    private void addKeyword(List<String> list, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            list.add(keyword);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record SchemaMatch(Map<String, Object> classView,
                               Map<String, Object> property,
                               Map<String, Object> relation) {
    }
}
