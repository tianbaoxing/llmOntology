package com.history.ontology2.model;

import java.util.List;

/** 类（节点类型）定义。关系声明于发起方（fromClass = 本类）一侧，入边由全量定义推导。 */
public record ClassDef(
        String name,
        String cnName,
        String layer,
        String keyProp,
        String description,
        List<PropertyDef> properties,
        List<RelationDef> relations) {
}
