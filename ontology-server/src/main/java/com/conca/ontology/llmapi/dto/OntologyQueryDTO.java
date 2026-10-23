package com.conca.ontology.llmapi.dto;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * @author tianbaoxing
 * @date 2026-08-19
 */
public record OntologyQueryDTO(
        String question,
        @NotBlank(message = "cypher must not be blank") String cypher,
        Map<String, Object> params) {
}
