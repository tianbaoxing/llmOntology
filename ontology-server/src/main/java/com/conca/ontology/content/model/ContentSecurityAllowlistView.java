package com.conca.ontology.content.model;
import java.util.List;

/** allowlist 视图（已加入 allowlist 的规则 ID 数组，无全局 version） */
public record ContentSecurityAllowlistView(
        List<String> ruleIds) {
}
