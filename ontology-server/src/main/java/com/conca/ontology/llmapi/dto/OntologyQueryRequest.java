package com.conca.ontology.llmapi.dto;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record OntologyQueryRequest(
        String question,
        @NotBlank(message = "cypher must not be blank") String cypher,
        Map<String, Object> params) {
}
