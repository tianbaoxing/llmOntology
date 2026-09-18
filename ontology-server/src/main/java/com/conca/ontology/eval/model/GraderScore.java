package com.conca.ontology.eval.model;

import java.util.Map;

public record GraderScore(
        String name,
        double score,
        String reason,
        boolean error,
        Map<String, Object> metadata) {

    public static GraderScore of(String name, double score, String reason, Map<String, Object> metadata) {
        return new GraderScore(name, score, reason, false, metadata == null ? Map.of() : metadata);
    }

    public static GraderScore fail(String name, String reason) {
        return new GraderScore(name, 0.0, reason, true, Map.of());
    }
}
