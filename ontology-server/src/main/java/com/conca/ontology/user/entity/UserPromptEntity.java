package com.conca.ontology.user.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户提示词行（user_prompt，P5：每条提示词一行，无全局 version）。
 * 与 {@link com.conca.ontology.user.model.UserPromptEntry} record 经
 * {@link com.conca.ontology.user.model.UserPromptEntryConverter} 互转（userId 为行级新增字段，API 模型不含）。
 */
@TableName("user_prompt")
public class UserPromptEntity {

    /** 提示词主键（UUID，源 JSON items[].id） */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属用户（原按用户一文件的文件名） */
    private String userId;

    /** 标题（chat 页 # 菜单展示与过滤） */
    private String title;

    /** 提示词正文 */
    private String content;

    private LocalDateTime createdAt;

    /** 更新时间（列表倒序依据） */
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
