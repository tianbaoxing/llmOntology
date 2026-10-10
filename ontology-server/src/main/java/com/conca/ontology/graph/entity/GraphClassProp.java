package com.conca.ontology.graph.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 图谱类属性定义表实体（graph_class_prop，P2；与 {@link com.conca.ontology.graph.model.PropertyDef} 互转）。
 * 复合主键 (class_name, prop_name)：无单一 @TableId，按业务键整组读写（insert / 按类名 Wrapper 删改）。
 */
@TableName("graph_class_prop")
public class GraphClassProp {

    private String className;

    private String propName;

    private String cnName;

    private String dataType;

    private Boolean required;

    private String description;

    /** 声明顺序 */
    private Integer ord;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getPropName() {
        return propName;
    }

    public void setPropName(String propName) {
        this.propName = propName;
    }

    public String getCnName() {
        return cnName;
    }

    public void setCnName(String cnName) {
        this.cnName = cnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
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
