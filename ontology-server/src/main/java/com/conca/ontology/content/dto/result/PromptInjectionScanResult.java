package com.conca.ontology.content.dto.result;

import com.conca.ontology.content.model.ContentDecision;
import com.conca.ontology.content.model.ContentRiskLevel;

import java.util.List;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
public record PromptInjectionScanResult(
        ContentDecision decision,
        double confidence,
        ContentRiskLevel riskLevel,
        List<ContentSecurityRuleHit> reasons,
        List<ContentSecurityRuleHit> allowlistedHits,
        List<String> warnings
) {}
