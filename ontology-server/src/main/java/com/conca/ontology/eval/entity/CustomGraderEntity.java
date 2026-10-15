package com.conca.ontology.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.conca.ontology.eval.model.CustomGraderDefinition;

/**
 * 自定义评测器行实体（custom_grader，P6 起替代 data/eval/custom-graders.json）。
 * 时间列存 ISO-8601 原文（record 内 String 原样透传保纳秒，对齐 skill_analysis 先例）。
 *
 * @author tianbaoxing
 * @date 2026-10-12
 */
@TableName("custom_grader")
public class CustomGraderEntity {

    @TableId(type = IdType.INPUT)
    private String name;

    private String description;

    private String mode;

    private String prompt;

    private String createdAt;

    private String updatedAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public static CustomGraderEntity of(CustomGraderDefinition d) {
        CustomGraderEntity e = new CustomGraderEntity();
        e.name = d.name();
        e.description = d.description();
        e.mode = d.mode();
        e.prompt = d.prompt();
        e.createdAt = d.createdAt();
        e.updatedAt = d.updatedAt();
        return e;
    }

    public CustomGraderDefinition toModel() {
        return new CustomGraderDefinition(name, description, mode, prompt, createdAt, updatedAt);
    }
}
