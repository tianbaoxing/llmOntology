package com.conca.ontology.model.content;

import java.util.List;

public record PromptInjectionScanResult(
        ContentDecision decision,
        double confidence,
        ContentRiskLevel riskLevel,
        List<ContentSecurityRuleHit> reasons,
        List<ContentSecurityRuleHit> allowlistedHits,
        List<String> warnings
) {}
