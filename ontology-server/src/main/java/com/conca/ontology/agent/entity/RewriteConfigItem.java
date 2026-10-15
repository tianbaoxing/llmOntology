package com.conca.ontology.agent.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 语义改写配置行（rewrite_config，P3：每项功能一行，无全局 version）。
 * feature 为主键（semantic/typo/term/context）；updated_at 为行级写入时间（API 不输出）。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName("rewrite_config")
public class RewriteConfigItem {

    @TableId(value = "feature", type = IdType.INPUT)
    private String feature;

    private Boolean enabled;

    private String prompt;

    private LocalDateTime updatedAt;

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
