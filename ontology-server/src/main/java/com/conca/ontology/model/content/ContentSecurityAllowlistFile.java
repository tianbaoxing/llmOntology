package com.conca.ontology.model.content;

import java.util.List;

public record ContentSecurityAllowlistFile(
        int version,
        List<String> ruleIds
) {}
