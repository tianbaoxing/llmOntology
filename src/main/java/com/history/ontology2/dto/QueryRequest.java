package com.history.ontology2.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record QueryRequest(
        @NotBlank(message = "cypher must not be blank") String cypher,
        Map<String, Object> params) {
}
