package com.conca.ontology.content.dto;
import com.conca.ontology.content.enums.ContentDecision;
import com.conca.ontology.content.enums.ContentSeverity;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
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
