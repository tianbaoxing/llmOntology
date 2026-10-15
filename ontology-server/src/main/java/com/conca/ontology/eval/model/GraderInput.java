package com.conca.ontology.eval.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
public record GraderInput(Map<String, Object> params, AgentRecord record) {

    public String query() {
        return str("query");
    }

    public String response() {
        return str("response");
    }

    public Object referenceResponse() {
        return params.get("reference_response");
    }

    public double tolerance() {
        Object v = params.get("tolerance");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    public String rubric() {
        return str("rubric");
    }

    public Integer judgeRuns() {
        Object v = params.get("judge_runs");
        return v instanceof Number n ? n.intValue() : null;
    }

    public List<String> expectedSkills() {
        return stringList("expected_skills");
    }

    public List<String> expectedTools() {
        return stringList("expected_tools");
    }

    public List<String> expectedMcps() {
        return stringList("expected_mcps");
    }

    public boolean hasExpectedSkillToolMcp() {
        return !(expectedSkills().isEmpty() && expectedTools().isEmpty() && expectedMcps().isEmpty());
    }

    private String str(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String key) {
        Object v = params.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object e : list) {
                if (e != null) {
                    String s = String.valueOf(e);
                    if (s != null && !s.isBlank()) out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }
}
