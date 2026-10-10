package com.conca.ontology.content.model;
import java.util.List;

/** 全量规则视图（rule_content_security 多行表，无全局 version） */
public record ContentSecurityRulesView(
        List<ContentSecurityRule> rules) {
}
