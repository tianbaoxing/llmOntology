package com.conca.ontology.content.dto.result;

public record ContentSecurityCheckResult(
        PromptInjectionScanResult injectionResult,
        boolean shouldBlock,
        String blockTip
) {}
