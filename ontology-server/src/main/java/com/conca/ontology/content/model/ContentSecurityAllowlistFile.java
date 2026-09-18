package com.conca.ontology.content.model;
import java.util.List;

public record ContentSecurityAllowlistFile(
        int version,
        List<String> ruleIds
) {}
