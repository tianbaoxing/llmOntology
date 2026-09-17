package com.conca.ontology.model.content;

import java.util.List;

public record ContentSecurityCheckResult(
        List<String> forbiddenWordHits,
        PromptInjectionScanResult injectionResult,
        boolean shouldBlock,
        String blockTip
) {}
