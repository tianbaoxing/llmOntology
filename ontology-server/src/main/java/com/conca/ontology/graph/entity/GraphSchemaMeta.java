package com.conca.ontology.graph.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 图谱 schema 元数据表实体（graph_schema_meta，单行 id=1，P2） */
@TableName("graph_schema_meta")
public class GraphSchemaMeta {

    /** 固定 1（单行） */
    public static final int SINGLETON_ID = 1;

    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    private Integer version;

    private LocalDateTime updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
