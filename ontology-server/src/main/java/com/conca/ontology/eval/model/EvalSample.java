package com.conca.ontology.eval.model;

import java.util.List;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
public record EvalSample(
        String id,
        String query,
        String response,
        Object referenceResponse,
        Double tolerance,
        String rubric,
        List<Turn> turns,
        List<String> skills,
        List<String> tools,
        List<String> mcps) {

    public record Turn(String query, String response) {
    }

    public boolean isMultiTurn() {
        return turns != null && !turns.isEmpty();
    }

    public boolean hasPresetResponse() {
        if (isMultiTurn()) {
            return !turns.isEmpty() && turns.stream().allMatch(t -> t.response() != null && !t.response().isBlank());
        }
        return response != null && !response.isBlank();
    }
}
