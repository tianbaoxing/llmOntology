package com.conca.ontology.dedup.converter;

import com.conca.ontology.dedup.entity.RuleDedup;
import com.conca.ontology.dedup.model.DedupRuleset;

/**
 * {@link RuleDedup} ↔ {@link DedupRuleset} 转换器（P2）。DedupRulesStore 持久化用。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
public final class DedupRulesetConverter {

    private DedupRulesetConverter() {
    }

    public static DedupRuleset toModel(RuleDedup e) {
        return new DedupRuleset(e.getId(), e.getName(), e.getVersion(), e.getUpdatedAt(),
                e.getSuffixTable(), e.getLabelConfig(), e.getGlobalConflictProps());
    }

    public static RuleDedup toEntity(DedupRuleset r) {
        RuleDedup e = new RuleDedup();
        e.setId(r.id());
        e.setName(r.name());
        e.setVersion(r.version());
        e.setUpdatedAt(r.updatedAt());
        e.setSuffixTable(r.suffixTable());
        e.setLabelConfig(r.labelConfig());
        e.setGlobalConflictProps(r.globalConflictProps());
        return e;
    }
}
