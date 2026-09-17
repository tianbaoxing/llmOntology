package com.conca.ontology.model.content;

public record ContentSecurityRule(
        String id,
        String category,
        ContentSeverity severity,
        ContentDecision action,
        String description,
        String pattern,
        Boolean allowlisted
) {
    public boolean allowlistedEffective() {
        return Boolean.TRUE.equals(allowlisted);
    }
}
