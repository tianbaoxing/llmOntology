package com.conca.ontology.content.model;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
public enum ContentSeverity {
    INFO(0.1),
    LOW(0.2),
    MEDIUM(0.4),
    HIGH(0.7),
    CRITICAL(1.0);

    private final double weight;

    ContentSeverity(double weight) { this.weight = weight; }

    public double weight() { return weight; }
}
