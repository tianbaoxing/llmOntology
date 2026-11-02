package com.conca.ontology.content.dto;

import com.conca.ontology.content.enums.ContentDecision;
import com.conca.ontology.content.enums.ContentRiskLevel;

import java.util.List;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
public record PromptInjectionScanResultDTO(
        ContentDecision decision,
        double confidence,
        ContentRiskLevel riskLevel,
        List<ContentSecurityRuleHitDTO> reasons,
        List<ContentSecurityRuleHitDTO> allowlistedHits,
        List<String> warnings
) {}
