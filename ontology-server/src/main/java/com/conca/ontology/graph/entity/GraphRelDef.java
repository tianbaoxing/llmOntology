package com.conca.ontology.graph.entity;

import java.util.List;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.conca.ontology.graph.model.PropertyDef;

/**
 * 图谱关系定义表实体（graph_rel_def，P2；与 {@link com.conca.ontology.graph.model.RelationDef} 互转）。
 * 复合主键 (from_class, rel_name)；props 为关系自身属性定义列表，JSON 列经 JacksonTypeHandler
 * 与 List&lt;PropertyDef&gt; 互转（泛型由字段声明解析）。autoResultMap 必开，否则查询时 typeHandler 不生效。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName(value = "graph_rel_def", autoResultMap = true)
public class GraphRelDef {

    private String fromClass;

    private String relName;

    private String cnName;

    private String toClass;

    private String description;

    private String example;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<PropertyDef> props;

    /** 声明顺序 */
    private Integer ord;

    public String getFromClass() {
        return fromClass;
    }

    public void setFromClass(String fromClass) {
        this.fromClass = fromClass;
    }

    public String getRelName() {
        return relName;
    }

    public void setRelName(String relName) {
        this.relName = relName;
    }

    public String getCnName() {
        return cnName;
    }

    public void setCnName(String cnName) {
        this.cnName = cnName;
    }

    public String getToClass() {
        return toClass;
    }

    public void setToClass(String toClass) {
        this.toClass = toClass;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public List<PropertyDef> getProps() {
        return props;
    }

    public void setProps(List<PropertyDef> props) {
        this.props = props;
    }

    public Integer getOrd() {
        return ord;
    }

    public void setOrd(Integer ord) {
        this.ord = ord;
    }
}
