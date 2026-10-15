package com.conca.ontology.content.dto.result;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
public record ContentSecurityCheckResult(
        PromptInjectionScanResult injectionResult,
        boolean shouldBlock,
        String blockTip
) {}
