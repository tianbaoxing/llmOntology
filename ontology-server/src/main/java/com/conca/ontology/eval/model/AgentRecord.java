package com.conca.ontology.eval.model;

import java.util.List;
import java.util.Map;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
public record AgentRecord(
        String answer,
        List<Map<String, Object>> toolCalls,
        int rounds,
        long promptTokens,
        long completionTokens,
        long durationMs,
        String error,
        List<Map<String, Object>> dialogue) {

    public AgentRecord(String answer, List<Map<String, Object>> toolCalls, int rounds,
            long promptTokens, long completionTokens, long durationMs, String error) {
        this(answer, toolCalls, rounds, promptTokens, completionTokens, durationMs, error, List.of());
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}
