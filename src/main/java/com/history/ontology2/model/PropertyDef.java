package com.history.ontology2.model;

/** 属性定义（节点或关系上） */
public record PropertyDef(
        String name,
        String cnName,
        String dataType,
        boolean required,
        String description) {
}
