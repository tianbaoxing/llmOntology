package com.conca.ontology.disjoint.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 互斥约束词典行（rule_disjoint，P2 调整：每条规则一行，无全局 version）。
 * 规则 ID（R001/R002...）为主键，字典序即展示序（原文件内声明顺序）；update_time 为行级写入时间（API 不输出）。
 */
@TableName("rule_disjoint")
public class RuleDisjoint {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String text;

    private LocalDateTime updateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
