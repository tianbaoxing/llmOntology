package com.conca.ontology.dedup.entity;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

/**
 * 去重规则集表实体（rule_dedup，P2；与 {@link com.conca.ontology.dedup.dto.DedupRulesetDTO} 互转）。
 * suffixTable / labelConfig / globalConflictProps 均为 JSON 列；updated_at 为 epoch 毫秒（API 契约即 long）。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName(value = "rule_dedup", autoResultMap = true)
public class RuleDedup {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String name;

    private Integer version;

    private Long updatedAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, List<String>> suffixTable;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, DedupLabelConfigPO> labelConfig;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> globalConflictProps;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, List<String>> getSuffixTable() {
        return suffixTable;
    }

    public void setSuffixTable(Map<String, List<String>> suffixTable) {
        this.suffixTable = suffixTable;
    }

    public Map<String, DedupLabelConfigPO> getLabelConfig() {
        return labelConfig;
    }

    public void setLabelConfig(Map<String, DedupLabelConfigPO> labelConfig) {
        this.labelConfig = labelConfig;
    }

    public List<String> getGlobalConflictProps() {
        return globalConflictProps;
    }

    public void setGlobalConflictProps(List<String> globalConflictProps) {
        this.globalConflictProps = globalConflictProps;
    }
}
