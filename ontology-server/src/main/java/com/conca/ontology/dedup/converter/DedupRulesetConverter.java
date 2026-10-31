package com.conca.ontology.dedup.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.conca.ontology.dedup.dto.DedupExactMode;
import com.conca.ontology.dedup.dto.DedupLabelConfigDTO;
import com.conca.ontology.dedup.dto.DedupRulesetDTO;
import com.conca.ontology.dedup.entity.DedupLabelConfigPO;
import com.conca.ontology.dedup.entity.RuleDedup;
import com.conca.ontology.shared.web.ApiException;

/**
 * {@link RuleDedup} ↔ {@link DedupRulesetDTO} 转换器。
 * label_config JSON 列以 {@link DedupLabelConfigPO} 为存储格式（entity 自包含），
 * exactModes 存储侧为枚举名字符串，读取遇未知值 fail-fast（存量数据损坏应显式暴露而非静默丢弃）。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
public final class DedupRulesetConverter {

    private DedupRulesetConverter() {
    }

    public static DedupRulesetDTO toDto(RuleDedup e) {
        return new DedupRulesetDTO(e.getId(), e.getName(), e.getVersion(), e.getUpdatedAt(),
                e.getSuffixTable(), toLabelConfigDto(e.getLabelConfig()), e.getGlobalConflictProps());
    }

    public static RuleDedup toEntity(DedupRulesetDTO r) {
        RuleDedup e = new RuleDedup();
        e.setId(r.id());
        e.setName(r.name());
        e.setVersion(r.version());
        e.setUpdatedAt(r.updatedAt());
        e.setSuffixTable(r.suffixTable());
        e.setLabelConfig(toLabelConfigPo(r.labelConfig()));
        e.setGlobalConflictProps(r.globalConflictProps());
        return e;
    }

    /** DTO label 配置 → 存储 PO（DedupRulesStore.save 归一化后落库用） */
    public static Map<String, DedupLabelConfigPO> toLabelConfigPo(Map<String, DedupLabelConfigDTO> m) {
        if (m == null) return null;
        Map<String, DedupLabelConfigPO> out = new LinkedHashMap<>();
        for (Map.Entry<String, DedupLabelConfigDTO> en : m.entrySet()) {
            out.put(en.getKey(), toPo(en.getValue()));
        }
        return out;
    }

    private static Map<String, DedupLabelConfigDTO> toLabelConfigDto(Map<String, DedupLabelConfigPO> m) {
        if (m == null) return null;
        Map<String, DedupLabelConfigDTO> out = new LinkedHashMap<>();
        for (Map.Entry<String, DedupLabelConfigPO> en : m.entrySet()) {
            out.put(en.getKey(), toDto(en.getValue()));
        }
        return out;
    }

    private static DedupLabelConfigPO toPo(DedupLabelConfigDTO d) {
        if (d == null) return null;
        DedupLabelConfigPO p = new DedupLabelConfigPO();
        p.setNameProp(d.nameProp());
        p.setAuxProps(d.auxProps());
        p.setConflictProps(d.conflictProps());
        if (d.exactModes() == null) {
            p.setExactModes(null);
        } else {
            List<String> modes = new ArrayList<>(d.exactModes().size());
            for (DedupExactMode m : d.exactModes()) modes.add(m.name());
            p.setExactModes(modes);
        }
        return p;
    }

    private static DedupLabelConfigDTO toDto(DedupLabelConfigPO p) {
        if (p == null) return null;
        List<DedupExactMode> modes;
        if (p.getExactModes() == null) {
            modes = null;
        } else {
            modes = new ArrayList<>(p.getExactModes().size());
            for (String s : p.getExactModes()) modes.add(parseMode(s));
        }
        return new DedupLabelConfigDTO(p.getNameProp(), p.getAuxProps(), modes, p.getConflictProps());
    }

    private static DedupExactMode parseMode(String s) {
        try {
            return DedupExactMode.valueOf(s);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(500, "rule_dedup.label_config 存在未知的精确匹配模式 '" + s + "'（存量数据与当前代码不兼容）");
        }
    }
}
