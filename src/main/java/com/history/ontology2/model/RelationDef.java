package com.history.ontology2.model;

import java.util.List;

/** 关系定义：声明在 fromClass（发起方）一侧，toClass 为目标类；props 为关系自身属性 */
public record RelationDef(
        String name,
        String cnName,
        String toClass,
        String description,
        String example,
        List<PropertyDef> props) {
}
