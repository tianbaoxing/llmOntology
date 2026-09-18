package com.conca.ontology.eval.model;

import java.util.Map;

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

    private String str(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
