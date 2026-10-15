package com.conca.ontology.content.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 内容安全规则行（rule_content_security，P3：每条规则一行，无全局 version）。
 * 规则 ID 为主键；ord 保留原文件声明顺序（扫描遍历序 = 展示序，新增规则追加尾部）；
 * allowlisted 为行级 allowlist 标记（原独立 allowlist 清单已合并进行）；
 * updated_at 为行级写入时间（API 不输出）。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName("rule_content_security")
public class RuleContentSecurity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String category;

    /** ContentSeverity 名（INFO/LOW/MEDIUM/HIGH/CRITICAL） */
    private String severity;

    /** ContentDecision 名（ALLOW/REVIEW/BLOCK） */
    private String action;

    private String description;

    private String pattern;

    private Boolean allowlisted;

    private Integer ord;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public Boolean getAllowlisted() {
        return allowlisted;
    }

    public void setAllowlisted(Boolean allowlisted) {
        this.allowlisted = allowlisted;
    }

    public Integer getOrd() {
        return ord;
    }

    public void setOrd(Integer ord) {
        this.ord = ord;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
