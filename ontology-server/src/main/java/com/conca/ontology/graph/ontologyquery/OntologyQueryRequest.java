package com.conca.ontology.graph.ontologyquery;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record OntologyQueryRequest(
        String question,
        @NotBlank(message = "cypher must not be blank") String cypher,
        Map<String, Object> params) {
}
