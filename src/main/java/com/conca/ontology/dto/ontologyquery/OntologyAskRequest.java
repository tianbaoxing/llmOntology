package com.conca.ontology.dto.ontologyquery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record OntologyAskRequest(
        @NotBlank(message = "question must not be blank") String question,
        @Min(value = 1, message = "limit must be >= 1")
        @Max(value = 100, message = "limit must be <= 100")
        Integer limit) {
}
