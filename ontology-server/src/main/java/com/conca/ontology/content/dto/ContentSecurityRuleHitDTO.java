package com.conca.ontology.content.dto;

import com.conca.ontology.content.model.ContentDecision;
import com.conca.ontology.content.model.ContentSeverity;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
public record ContentSecurityRuleHitDTO(
        String ruleId,
        String category,
        ContentSeverity severity,
        ContentDecision action,
        String description,
        int matches,
        String snippet,
        String via
) {}
