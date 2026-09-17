package com.conca.ontology.model.content;

public record ContentSecurityRuleHit(
        String ruleId,
        String category,
        ContentSeverity severity,
        ContentDecision action,
        String description,
        int matches,
        String snippet,
        String via
) {}
