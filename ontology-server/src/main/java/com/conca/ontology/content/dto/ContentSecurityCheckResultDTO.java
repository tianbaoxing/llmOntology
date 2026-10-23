package com.conca.ontology.content.dto;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
public record ContentSecurityCheckResultDTO(
        PromptInjectionScanResultDTO injectionResult,
        boolean shouldBlock,
        String blockTip
) {}
