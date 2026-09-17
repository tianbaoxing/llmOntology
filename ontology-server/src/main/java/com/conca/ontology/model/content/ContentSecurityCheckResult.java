package com.conca.ontology.model.content;

public record ContentSecurityCheckResult(
        PromptInjectionScanResult injectionResult,
        boolean shouldBlock,
        String blockTip
) {}
