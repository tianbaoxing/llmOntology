package com.conca.ontology.graph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.conca.ontology.graph.model.ProfileEntry;

/**
 * 构建期问询记录行（ontology_profile，P2 调整：每条问答一行，无全局 version）。
 * 自增 id 兼作顺序列：upsert 同 key 原位置覆盖（不动 id），新 key 追加在尾部。
 * updatedAt 存 {@link ProfileEntry#updatedAt()} 的 ISO-8601 原文（含纳秒精度）。
 */
@TableName("ontology_profile")
public class OntologyProfileItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String phase;

    /** 列名 item_key（key 是 MySQL 保留字）；唯一键，按 key upsert */
    private String itemKey;

    private String question;

    private String answer;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
