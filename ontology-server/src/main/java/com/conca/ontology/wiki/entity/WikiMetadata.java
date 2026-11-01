package com.conca.ontology.wiki.entity;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

/**
 * Wiki 手工元数据表实体（wiki_metadata，P10；service/store 直接以本实体为领域载体，原 model 层已移除）。
 * tags 为 JSON 列（NULL = 无标签）；updated_at 为行级写入时间（API 不输出，业务侧不设置，恒由 WikiMetaStore 写入时刷新）。
 *
 * @author tianbaoxing
 * @date 2026-10-24
 */
@TableName(value = "wiki_metadata", autoResultMap = true)
public class WikiMetadata {

    @TableId(value = "md_name", type = IdType.INPUT)
    private String mdName;

    private String category;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private LocalDateTime updatedAt;

    public String getMdName() {
        return mdName;
    }

    public void setMdName(String mdName) {
        this.mdName = mdName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
