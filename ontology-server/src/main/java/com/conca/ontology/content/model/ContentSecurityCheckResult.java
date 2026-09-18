package com.conca.ontology.content.model;
public record ContentSecurityCheckResult(
        PromptInjectionScanResult injectionResult,
        boolean shouldBlock,
        String blockTip
) {}
