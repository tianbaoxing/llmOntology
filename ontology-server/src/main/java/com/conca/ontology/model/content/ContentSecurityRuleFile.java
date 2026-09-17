package com.conca.ontology.model.content;

import java.util.List;

public record ContentSecurityRuleFile(
        int version,
        List<ContentSecurityRule> rules
) {}
