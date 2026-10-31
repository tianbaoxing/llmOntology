package com.conca.ontology.dedup.entity;

import java.util.List;

/**
 * rule_dedup 表 label_config JSON 列的存储格式（PO，entity 包自包含，不依赖 dto）。
 * 字段名与存量 JSON 完全一致（反序列化兼容）；exactModes 存枚举名字符串，
 * 与 API 侧 DedupLabelConfigDTO 的枚举经 DedupRulesetConverter 映射。
 *
 * @author tianbaoxing
 * @date 2026-10-31
 */
public class DedupLabelConfigPO {

    private String nameProp;

    private List<String> auxProps;

    private List<String> exactModes;

    private List<String> conflictProps;

    public String getNameProp() {
        return nameProp;
    }

    public void setNameProp(String nameProp) {
        this.nameProp = nameProp;
    }

    public List<String> getAuxProps() {
        return auxProps;
    }

    public void setAuxProps(List<String> auxProps) {
        this.auxProps = auxProps;
    }

    public List<String> getExactModes() {
        return exactModes;
    }

    public void setExactModes(List<String> exactModes) {
        this.exactModes = exactModes;
    }

    public List<String> getConflictProps() {
        return conflictProps;
    }

    public void setConflictProps(List<String> conflictProps) {
        this.conflictProps = conflictProps;
    }
}
