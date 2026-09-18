package com.conca.ontology.content.dto.result;

import com.conca.ontology.content.model.ContentDecision;
import com.conca.ontology.content.model.ContentSeverity;

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
