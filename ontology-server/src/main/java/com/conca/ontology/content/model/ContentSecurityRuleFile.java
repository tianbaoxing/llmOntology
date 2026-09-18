package com.conca.ontology.content.model;
import java.util.List;

public record ContentSecurityRuleFile(
        int version,
        List<ContentSecurityRule> rules
) {}
