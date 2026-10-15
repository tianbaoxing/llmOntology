package com.conca.ontology.eval.model;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
public record CustomGraderDefinition(
        String name,
        String description,
        String mode,
        String prompt,
        String createdAt,
        String updatedAt) {

    public static final String MODE_CRITERIA = "criteria";
    public static final String MODE_TEMPLATE = "template";
}
