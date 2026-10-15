package com.conca.ontology.graph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 图谱类定义表实体（graph_class，P2；与 {@link com.conca.ontology.graph.model.ClassDef} 互转）
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName("graph_class")
public class GraphClass {

    @TableId(value = "name", type = IdType.INPUT)
    private String name;

    private String cnName;

    private String layer;

    private String keyProp;

    private String description;

    /** 声明顺序（保持文件内顺序） */
    private Integer ord;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCnName() {
        return cnName;
    }

    public void setCnName(String cnName) {
        this.cnName = cnName;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    public String getKeyProp() {
        return keyProp;
    }

    public void setKeyProp(String keyProp) {
        this.keyProp = keyProp;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getOrd() {
        return ord;
    }

    public void setOrd(Integer ord) {
        this.ord = ord;
    }
}
